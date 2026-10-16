-- KEYS: 会话、手机号冷却；ARGV: 版本/操作/是否含 proof/操作 ID/执行期限/最小冷却。
local now = now_ms()
if redis.call('EXISTS', KEYS[1]) == 0 then return response(410, 'Session expired or deleted') end
fail_expired_operation(KEYS[1], now)
local state = redis.call('HGET', KEYS[1], 'state')
local operation = ARGV[2]
local allowed = operation == 'sms' and (state == 'CREATED' or state == 'SMS_CHALLENGE')
    or operation == 'login' and (state == 'SMS_SENT' or state == 'LOGIN_CHALLENGE')
if not allowed then return response(409, '当前流程状态不允许执行该操作') end
if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return response(409, '流程已变化，请重新读取状态') end
local challenge = redis.call('HGET', KEYS[1], 'challenge_json') or ''
local challenge_attempts = tonumber(redis.call('HGET', KEYS[1], 'challenge_attempts') or '0')
if challenge ~= '' then
    if redis.call('HGET', KEYS[1], 'pending_operation') ~= operation or ARGV[3] ~= '1' then
        return response(409, '请先完成对应的人机验证')
    end
    if challenge_attempts >= 3 then return response(429, '人机验证尝试次数已达上限') end
end
local counter = operation == 'sms' and 'sms_attempts' or 'login_attempts'
if tonumber(redis.call('HGET', KEYS[1], counter) or '0') >= 4 then return response(429, '操作尝试次数已达上限') end
local lease = tonumber(ARGV[5])
local session_ttl = redis.call('PTTL', KEYS[1])
-- 快过期的会话不再启动新上游调用；避免会话失效但短信仍被发出。
if session_ttl <= lease then return response(410, '会话剩余时间不足，请重新创建流程') end
if operation == 'sms' then
    local owner = redis.call('GET', KEYS[2])
    local id = redis.call('HGET', KEYS[1], 'id')
    if owner and (state ~= 'SMS_CHALLENGE' or owner ~= id) then
        return response(429, '请等待手机号短信冷却结束')
    end
    -- 验证继续提交只能使用本流程占有的冷却；不能越过其他流程的占有。
    local ttl = math.max(tonumber(ARGV[6]), lease)
    if not owner then
        if not redis.call('SET', KEYS[2], id, 'NX', 'PX', ttl) then return response(429, '请等待手机号短信冷却结束') end
    elseif redis.call('PTTL', KEYS[2]) < ttl then
        redis.call('PEXPIRE', KEYS[2], ttl)
    end
    redis.call('HSET', KEYS[1], 'resend_after', tostring(now + redis.call('PTTL', KEYS[2])))
end
if challenge ~= '' then redis.call('HINCRBY', KEYS[1], 'challenge_attempts', 1) end
redis.call('HINCRBY', KEYS[1], counter, 1)
redis.call('HINCRBY', KEYS[1], 'version', 1)
redis.call('HSET', KEYS[1], 'state', operation == 'sms' and 'SMS_PROCESSING' or 'LOGIN_PROCESSING',
    'operation_id', ARGV[4], 'operation_deadline', tostring(now + lease))
return response(200, '成功', snapshot(KEYS[1], now))

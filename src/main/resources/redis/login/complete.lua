-- 只有本次执行者、且结果在期限内到达，才能更新状态；HSET 不改变原 TTL。
local now = now_ms()
if redis.call('EXISTS', KEYS[1]) == 0 then return response(410, 'Session expired or deleted') end
if redis.call('HGET', KEYS[1], 'operation_id') ~= ARGV[1] then return response(409, '操作结果已失效，不允许覆盖当前状态') end
if fail_expired_operation(KEYS[1], now) then return response(409, '操作已超时，迟到结果不予保存') end
local previous = redis.call('HGET', KEYS[1], 'state')
local data = cjson.decode(ARGV[2])
local allowed = previous == 'SMS_PROCESSING' and (data.state == 'SMS_SENT' or data.state == 'SMS_CHALLENGE')
    or previous == 'LOGIN_PROCESSING' and (data.state == 'LOGGED_IN' or data.state == 'LOGIN_CHALLENGE')
if not allowed then return response(409, '当前状态不能接收该操作结果') end
local resend_after = redis.call('HGET', KEYS[1], 'resend_after') or '0'
if data.state == 'SMS_SENT' then
    local owner = redis.call('GET', KEYS[2])
    local id = redis.call('HGET', KEYS[1], 'id')
    if owner and owner ~= id then return response(409, '手机号冷却已由其他流程占有') end
    local duration = tonumber(data.cooldown_ms)
    if not owner then redis.call('SET', KEYS[2], id, 'NX', 'PX', duration)
    elseif redis.call('PTTL', KEYS[2]) < duration then redis.call('PEXPIRE', KEYS[2], duration) end
    resend_after = tostring(now + redis.call('PTTL', KEYS[2]))
end
redis.call('HSET', KEYS[1], 'state', data.state, 'action_type', data.action_type,
    'challenge_json', data.challenge_json, 'aigis_session_id', data.aigis_session_id,
    'pending_operation', data.pending_operation, 'challenge_attempts', data.challenge_attempts,
    'resend_after', resend_after, 'operation_id', '', 'operation_deadline', '')
redis.call('HINCRBY', KEYS[1], 'version', 1)
return response(200, '成功', snapshot(KEYS[1], now))

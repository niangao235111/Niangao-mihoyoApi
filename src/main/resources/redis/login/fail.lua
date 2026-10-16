if redis.call('EXISTS', KEYS[1]) == 0 then return response(410, 'Session expired or deleted') end
local state = redis.call('HGET', KEYS[1], 'state')
if redis.call('HGET', KEYS[1], 'operation_id') ~= ARGV[1]
    or (state ~= 'SMS_PROCESSING' and state ~= 'LOGIN_PROCESSING') then
    return response(409, '失败回调已失效')
end
-- 保留手机号冷却；上游失败可能是结果不明，而不是短信一定未发送。
redis.call('HSET', KEYS[1], 'state', 'FAILED', 'operation_id', '', 'operation_deadline', '')
redis.call('HINCRBY', KEYS[1], 'version', 1)
return response(200, '成功')

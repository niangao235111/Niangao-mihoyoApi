-- KEYS: 会话 Hash、容量 ZSet；ARGV: id/mobile/area/device/TTL/容量上限。
local now = now_ms()
local ttl = tonumber(ARGV[5])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
if redis.call('EXISTS', KEYS[1]) == 1 then return response(409, '会话 ID 已存在') end
if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[6]) then return response(429, '有效登录流程会话已达到上限') end
redis.call('HSET', KEYS[1],
    'id', ARGV[1], 'mobile', ARGV[2], 'area_code', ARGV[3], 'device_id', ARGV[4],
    'state', 'CREATED', 'expires_at', tostring(now + ttl), 'version', '0',
    'action_type', '', 'challenge_json', '', 'aigis_session_id', '', 'pending_operation', '',
    'sms_attempts', '0', 'login_attempts', '0', 'challenge_attempts', '0',
    'resend_after', '0', 'operation_id', '', 'operation_deadline', '')
redis.call('PEXPIRE', KEYS[1], ttl)
redis.call('ZADD', KEYS[2], now + ttl, ARGV[1])
-- 索引只保留到最新会话过期，清理失效索引不需要扫描 Redis 全库。
if redis.call('PTTL', KEYS[2]) < ttl then redis.call('PEXPIRE', KEYS[2], ttl) end
return response(200, '成功', snapshot(KEYS[1], now))

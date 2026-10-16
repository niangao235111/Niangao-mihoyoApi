local existed = redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[1])
if existed == 0 then return response(410, 'Session expired or deleted') end
-- 此处不访问手机号冷却 Key，删除或重建会话都不能跳过冷却。
return response(200, '成功')

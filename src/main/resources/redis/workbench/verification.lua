-- 所有状态更新均检查父会话；退出、轮换或过期后，迟到请求不能重新创建验证记录。
if redis.call('EXISTS', KEYS[1]) == 0 then return nil end
local remaining = redis.call('PTTL', KEYS[1])
if remaining <= 0 then return nil end
local op = ARGV[1]
local function expire(ms) redis.call('PEXPIRE', KEYS[2], math.min(ms, remaining)) end
if op == 'read' then return redis.call('HGET', KEYS[2], ARGV[2]) end
if op == 'failure' then
    local proof = redis.call('HGET', KEYS[2], 'proof') or ''
    if proof ~= ARGV[2] then return 'stale' end
    redis.call('HSET', KEYS[2], 'failure', ARGV[3])
    redis.call('HDEL', KEYS[2], 'proof')
    expire(300000)
    return 'ok'
end
if op == 'reserve' then
    if redis.call('HGET', KEYS[2], 'failure') ~= ARGV[3] then return 'changed' end
    local t = redis.call('TIME')
    local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
    local state = redis.call('HGET', KEYS[2], 'state')
    local created = tonumber(redis.call('HGET', KEYS[2], 'created') or '0')
    if (state == 'creating' or state == 'verifying') and now - created < 120000 then return 'busy' end
    if now - created < 10000 then return 'cooldown' end
    redis.call('HSET', KEYS[2], 'task_id', ARGV[2], 'state', 'creating', 'created', tostring(now))
    redis.call('HDEL', KEYS[2], 'task', 'proof')
    expire(120000)
    return 'ok'
end
if op == 'ready' then
    if redis.call('HGET', KEYS[2], 'task_id') ~= ARGV[2] or redis.call('HGET', KEYS[2], 'state') ~= 'creating' then return nil end
    redis.call('HSET', KEYS[2], 'state', 'ready', 'task', ARGV[3])
    expire(300000)
    return 'ok'
end
if op == 'claim' then
    if redis.call('HGET', KEYS[2], 'task_id') ~= ARGV[2] or redis.call('HGET', KEYS[2], 'state') ~= 'ready' then return nil end
    local task = redis.call('HGET', KEYS[2], 'task')
    local t = redis.call('TIME')
    redis.call('HSET', KEYS[2], 'state', 'verifying', 'created', tostring(tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)))
    redis.call('HDEL', KEYS[2], 'task')
    expire(60000)
    return task
end
if op == 'pass' then
    if redis.call('HGET', KEYS[2], 'task_id') ~= ARGV[2] or redis.call('HGET', KEYS[2], 'state') ~= 'verifying' then return nil end
    redis.call('HSET', KEYS[2], 'state', 'passed', 'proof', ARGV[3])
    redis.call('HDEL', KEYS[2], 'failure', 'task')
    expire(300000)
    return 'ok'
end
if op == 'clear' then
    if redis.call('HGET', KEYS[2], 'task_id') ~= ARGV[2] then return nil end
    redis.call('HDEL', KEYS[2], 'task', 'proof')
    redis.call('HSET', KEYS[2], 'state', 'failed')
    return 'ok'
end
return nil

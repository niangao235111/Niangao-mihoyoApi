if redis.call('EXISTS', KEYS[1]) == 0 then return nil end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local values = redis.call('HMGET', KEYS[1], 'encrypted', 'csrf', 'created_at', 'expires_at', 'absolute_expires_at')
for i = 1, 5 do
    if not values[i] then redis.call('DEL', KEYS[1]); return nil end
end
local expires, absolute = tonumber(values[4]), tonumber(values[5])
if not expires or not absolute or now >= expires or now >= absolute then
    redis.call('DEL', KEYS[1]); return nil
end
if ARGV[1] == '1' then
    expires = math.min(now + tonumber(ARGV[2]), absolute)
    redis.call('HSET', KEYS[1], 'expires_at', tostring(expires))
    redis.call('PEXPIRE', KEYS[1], expires - now)
    values[4] = tostring(expires)
end
return values

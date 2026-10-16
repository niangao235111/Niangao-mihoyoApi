local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if redis.call('EXISTS', KEYS[1]) == 1 then return nil end
local absolute = now + tonumber(ARGV[4])
local expires = math.min(now + tonumber(ARGV[3]), absolute)
redis.call('HSET', KEYS[1], 'encrypted', ARGV[1], 'csrf', ARGV[2],
    'created_at', tostring(now), 'expires_at', tostring(expires), 'absolute_expires_at', tostring(absolute))
redis.call('PEXPIRE', KEYS[1], expires - now)
return {ARGV[1], ARGV[2], tostring(now), tostring(expires), tostring(absolute)}

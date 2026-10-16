-- 所有脚本共用：使用 Redis 时间，不信任各个 Java 实例的本地时间。
local function now_ms()
    local value = redis.call('TIME')
    return tonumber(value[1]) * 1000 + math.floor(tonumber(value[2]) / 1000)
end
local function response(code, message, session)
    local result = {code = code, message = message}
    if session then result.session = session end
    return cjson.encode(result)
end
local function snapshot(key, now)
    local values = redis.call('HGETALL', key)
    local result = {}
    for i = 1, #values, 2 do result[values[i]] = values[i + 1] end
    result._server_time = tostring(now)
    return result
end
local function fail_expired_operation(key, now)
    local state = redis.call('HGET', key, 'state')
    if state == 'SMS_PROCESSING' or state == 'LOGIN_PROCESSING' then
        local deadline = tonumber(redis.call('HGET', key, 'operation_deadline') or '0')
        if deadline <= now then
            redis.call('HSET', key, 'state', 'FAILED', 'operation_id', '', 'operation_deadline', '')
            redis.call('HINCRBY', key, 'version', 1)
            return true
        end
    end
    return false
end

local now = now_ms()
if redis.call('EXISTS', KEYS[1]) == 0 then return response(410, 'Session expired or deleted') end
-- 执行者进程中断后，请求期限已过则失败关闭，不自动重新发短信。
fail_expired_operation(KEYS[1], now)
return response(200, '成功', snapshot(KEYS[1], now))

# 后端完整测试报告

日期：2026-10-16。测试工作目录：D:\develop\Niangao-mihoyo。
最终执行 `mvn clean verify`，使用本地配置的真实MySQL genshinImpact库及真实Redis，模拟米游社上游；所有HTTP测试使用随机端口。没有启动或占用固定17892，没有发送真实短信或代解真实验证码。

## 最终结果
后端 77 项全部通过；失败 0，错误 0，跳过 0。前端33项测试和生产构建通过，后端JAR打包通过。

| 测试类 | 用例 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| GenshinArchiveIntegrationTest | 20 | 0 | 0 | 0 |
| GenshinArchiveStorageIntegrationTest | 6 | 0 | 0 | 0 |
| GenshinDeviceServiceTest | 3 | 0 | 0 | 0 |
| ConsoleLogSafetyTest | 2 | 0 | 0 | 0 |
| LoginSessionMapperTest | 19 | 0 | 0 | 0 |
| MihoyoApiIntegrationTest | 9 | 0 | 0 | 0 |
| RedisWorkbenchSessionMapperTest | 6 | 0 | 0 | 0 |
| WorkbenchHttpIntegrationTest | 5 | 0 | 0 | 0 |
| WorkbenchSessionTest | 7 | 0 | 0 | 0 |
## 覆盖范围

- 短信登录：流程创建/状态/删除、正常链路、GT3/GT4任务、凭证解析、未注册/上游拒绝、未知字段/类型/超限体积、错误Result及容器兜底。
- Redis流程：固定TTL、冷却、容量、多实例并发、操作超时、旧响应回写、退出/过期后不得重建、凭证不落明文。
- 工作台：HttpOnly/SameSite/Secure/Path Cookie、恢复/续期/轮换/退出、CSRF、来源检查、明确SToken过期撤销、普通异常保留会话。
- 账号与档案：官服/B服、游戏UID归属、国际服拒绝、真实SDK、DS对实际请求字节签名、稳定设备及真实格式指纹缓存、错误阶段与脱敏、部分缺失字段及资源URL过滤。
- 人工验证：创建/提交模拟闭环、场景/trace/设备头、证明加密保存及附加、重复/跨会话/错误challenge/登出拒绝、再次风控停止。
- MySQL：四张已建表的真实保存和回读、账号/服务器隔离、空记录、旧请求不能覆盖新结果、角色列表刷新保留详情、部分详情成功、整批SQL失败事务回滚、数据库失败保留旧数据、查询记录无原始敏感消息。随机测试账号执行后仅清理自身记录。
- 完整API覆盖检查：4个业务Controller实际声明的20条方法/路径均出现在完整接口文档和20条HTTP示例中。

## 最终JAR启动检查

使用生产配置模板、独立于项目的工作目录、随机端口启动打包JAR，实际检查未登录会话401、Redis登录流程create/status/delete成功、未登录saved档案401。检查不调用sms或verify，不发送短信。日志使用脱敏业务信息，未把配置凭证放入运行包。

前后端分离检查：JAR内没有BOOT-INF/classes/static或public前端资源。运行JAR包含Spring、Tomcat、JDBC、MySQL、Redis与SDK等必要依赖；删除这些依赖会导致无法运行。

## 边界

这是自动回归、真实数据库/Redis和本机JAR启动检查，不是云服务器实际部署、压测、全链路性能基准或用户真实短信/验证码验证。用户曾反馈官服人工验证流程可用；本次未重放用户真实凭证。云端Linux/systemd/Nginx模板需按真实域名、证书和连接配置部署后复核。

生成文件瘦身后target/surefire-reports与临时日志会被删除，本报告保留最终汇总。需要完整原始JUnit报告时重新执行mvn clean verify。配置改动过程中曾因Redis本机地址依赖旧默认值而连接失败，现已将该地址明确保留于忽略的本地配置，最终干净回归和生产模板启动检查均成功。
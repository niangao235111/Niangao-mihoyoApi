# 后端测试报告

## 首次公开发布验证

日期：2026-10-17（北京时间）。执行 `mvn -B test`，88 项测试全部通过，失败 0、错误 0、跳过 0。

使用真实 MySQL / Redis 和本地模拟米游社上游；HTTP 集成测试采用随机端口，没有发送真实短信。此结果验证应用行为，不代表真实米游社网络或风控始终可用。

| 测试类 | 用例 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| GenshinArchiveIntegrationTest | 20 | 0 | 0 | 0 |
| GenshinArchiveStorageIntegrationTest | 6 | 0 | 0 | 0 |
| GenshinAvatarTest | 4 | 0 | 0 | 0 |
| GenshinDeviceServiceTest | 3 | 0 | 0 | 0 |
| ReadOnlyRetryTest | 7 | 0 | 0 | 0 |
| ConsoleLogSafetyTest | 2 | 0 | 0 | 0 |
| LoginSessionMapperTest | 19 | 0 | 0 | 0 |
| MihoyoApiIntegrationTest | 9 | 0 | 0 | 0 |
| RedisWorkbenchSessionMapperTest | 6 | 0 | 0 | 0 |
| WorkbenchHttpIntegrationTest | 5 | 0 | 0 | 0 |
| WorkbenchSessionTest | 7 | 0 | 0 | 0 |

## 覆盖范围

- 登录流程：创建、状态、删除、短信与验证码调用、GT3 / GT4 验证结果、凭证解析、上游拒绝与请求参数校验。
- Redis 登录状态：固定 TTL、冷却、容量、并发锁、操作超时、旧响应回写保护，以及过期后不得重建会话。
- 工作台会话：加密凭证、Cookie 属性、恢复 / 续期 / 退出、CSRF 与来源校验；明确凭证过期才撤销，普通网络错误保留会话。
- 原神档案：官服 / B 服识别、账号归属、国际服拒绝、SDK 请求签名、设备指纹、角色装备数据和图片 URL 校验。
- 头像兼容：优先读取上游 `AvatarUrl`，兼容 `avatar_url`；缺失字段和不可信图片地址不直接透传。
- 安全验证：模拟创建、提交与后续请求附加证明；会话 / 角色绑定、重复提交、失效和再次风控处理。
- MySQL 保存：总览 / 角色 / 装备保存回读、账号隔离、旧请求不能覆盖新结果、基础列表刷新保留详情、部分成功与事务回滚。
- 网络重试：最多额外 2 次、只读 POST 请求体重放、共享时间预算、错误脱敏；短信不自动重试，HTTP / 业务拒绝、证书错误及中断不重试。

## 复现方式

准备 JDK 17+、Maven 和隔离的 MySQL / Redis 环境，执行建表 SQL，按 README 注入配置后运行：

```shell
mvn clean verify
```

测试可能创建并清理自身测试数据，测试账号需具备相应增删改查权限。不要连接生产环境执行完整集成测试。

真实短信登录、本人完成安全验证及真实上游请求需手动验证；自动测试不会替用户完成这些操作。前端独立维护，不属于本仓库的测试范围。

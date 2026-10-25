# Niangao-mihoyoApi

基于 Java 17 / Spring Boot 的米游社与原神国服 API 后端。首次公开发布主要完成 **短信登录、账号数据总览、已拥有角色与装备详情**，供独立部署的网页或其他客户端通过 HTTP 调用。

本项目为社区个人项目，与米哈游 / HoYoverse 无官方关联。上游接口、凭证有效期和安全验证要求由米哈游决定；当前仅支持原神国服官服与 B 服。

## 已实现功能

2026-10-18 新增祈愿记录同步、明细分析及 UIGF 导入导出。需额外执行 `db/mysql/020_create_genshin_wishes.sql`；接口和升级说明见 [祈愿功能文档](docs/genshin-wish-api.md)。前端为独立项目，未打入本后端。

| 模块 | 内容 |
| --- | --- |
| 米游社登录 | 创建登录流程、发送短信、提交验证码、查询状态、删除流程；支持人工完成 GT3 / GT4 验证后提交结果 |
| 关联账号 | 使用授权凭证读取绑定游戏账号；区分官服 `cn_gf01` 与 B 服 `cn_qd01`，拒绝国际服 |
| 账号总览 | 成就总数、活跃天数、深境螺旋、幻想真境剧诗，以及上游提供的其他统计与探索数据 |
| 角色与装备 | 已拥有角色列表、等级与命座、天赋、武器、圣遗物与面板详情；具体字段取决于上游返回 |
| 战绩安全验证 | 创建验证任务 → 用户本人完成验证 → 提交结果 → 重试读取，不提供自动解题 |
| 工作台会话 | HttpOnly Cookie、CSRF 与来源校验；Redis 加密保存凭证，支持恢复、续期及退出 |
| 档案保存 | MySQL 保存最近成功的总览、角色与装备数据及查询记录；支持离线读取已保存档案，不保存每日快照 |
| 异常处理 | 返回操作阶段与脱敏错误；只读请求的临时网络错误最多额外重试 2 次，短信发送与验证码提交不自动重试 |

短信登录流程固定 10 分钟有效；本站工作台会话默认 7 天无活动到期、创建后最长 30 天。这些期限不代表米哈游凭证的官方有效期，上游凭证失效时仍需重新登录。

## 技术栈与目录

Java 17、Spring Boot 3.5.16、Spring MVC、Spring JDBC、Redis / Lua、MySQL、Maven，以及固定版本 UIGF Java SDK。

```text
src/main/       后端接口、业务逻辑、上游客户端、配置及 Redis Lua
src/test/       单元测试与 MySQL / Redis 集成测试
db/mysql/       手工执行的四张档案表建表 SQL
deploy/         环境变量、生产配置与 systemd 模板
scripts/        本地密钥初始化与后端打包脚本
docs/           接口、部署、测试及数据存储说明
requests/       可在 HTTP 客户端执行的请求示例
vendor/         固定 SDK 的本地 Maven 仓库
licenses/       第三方许可证
```

仓库只发布后端。前端独立部署；后端不提供网页首页。`vendor/maven` 中的 SDK 尚未从 Maven Central 获取，必须保留以便在新机器构建。

## 快速开始

### 1. 准备环境与数据库

安装 JDK 17+、Maven、MySQL 和 Redis。建议使用 MySQL 8 与 Redis 7；先创建 `genshinImpact` 数据库，再执行：

```text
db/mysql/010_create_genshin_archive.sql
```

建表脚本创建账号、查询记录、最新总览、最新角色四张表。应用不会自动初始化数据库，已有表请先核对结构，避免覆盖数据。

### 2. 配置服务

完整模板见 [环境变量示例](deploy/backend.env.example) 和 [生产配置示例](deploy/application-production.example.yaml)。至少设置：

| 环境变量 | 用途 |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接与账号 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` / `REDIS_PASSWORD` | Redis 连接；按实际情况填写密码 |
| `MIHOYO_PHONE_KEY_SECRET` | 稳定的随机 HMAC 密钥，用于手机号索引 |
| `WORKBENCH_ENCRYPTION_KEY` | 32 字节随机值的 Base64，用于凭证加密 |
| `WORKBENCH_ALLOWED_ORIGINS` | 允许访问工作台的前端来源，多个以逗号分隔 |
| `WORKBENCH_COOKIE_SECURE` | HTTPS 生产环境设为 `true` |
| `SERVER_ADDRESS` / `SERVER_PORT` | 默认 `127.0.0.1:17892`；按部署环境调整 |

环境变量示例文件不会自动加载，需由运行环境、IDEA 或部署脚本注入。不要把真实密码和密钥写入源码。多实例需共享 Redis 和稳定密钥；更换加密密钥会影响已有会话。

本地也可使用被 Git 忽略的 `.local/mysql-local.yaml`、`.local/redis-local.yaml`、`.local/workbench-local.yaml`。应用会自动导入这三个可选文件；[密钥初始化脚本](scripts/init-workbench-session.ps1) 可创建本地工作台密钥。

### 3. 构建与启动

```shell
mvn -DskipTests package
java -jar target/niangao-mihoyo-0.1.0-SNAPSHOT.jar
```

默认后端地址为 `http://127.0.0.1:17892`。建议生产使用 HTTPS 同源反向代理，将 `/api/` 请求转发到后端；MySQL 和 Redis 保持内网访问。详细步骤见 [部署文档](docs/deployment.md)。

## 接口文档与调用流程

- [完整接口文档：20 个接口、请求参数、返回值、错误码和验证流程](docs/backend-api.md)
- [完整 HTTP 请求示例](requests/backend-all.http)
- [MySQL 表结构与保存规则](docs/genshin-archive-storage.md)
- [测试记录与覆盖范围](docs/backend-test-report.md)

典型调用流程：

1. `/api/mihoyo/login/start` 创建流程，通过 `/sms` 与 `/verify` 完成短信登录；如要求安全验证，由本人完成后提交验证结果。
2. 使用登录返回的凭证调用 `/api/workbench/session`，建立加密工作台会话。
3. 获取绑定账号，选择官服或 B 服角色，通过工作台接口读取总览、角色列表和角色装备详情。
4. 工作台实时查询成功后保存到 MySQL；`/api/workbench/genshin/saved` 可读取此前成功保存的档案。

`/api/genshin/*` 提供直接传凭证的实时查询，不保存档案。工作台接口需携带会话 Cookie，POST 还需 `X-Workspace-CSRF`；浏览器跨来源调用需正确设置凭证与来源，推荐同源代理。请按完整接口文档填写请求，不要把登录凭证放进 URL 或日志。

## 测试与当前状态

```shell
mvn clean verify
```

完整集成测试需要可连接的 MySQL / Redis，以及已初始化的四张表。请使用隔离测试环境；测试采用随机 HTTP 端口与测试账号数据，上游为本地模拟，不会发送真实短信。

首次发布验证结果见 [测试记录](docs/backend-test-report.md)。自动测试验证本项目行为，不能保证真实上游持续可用。真实查询仍可能遇到网络超时、战绩未公开、安全验证或凭证失效。

尚未提供网站用户注册、国际服支持、每日历史快照或自动验证码识别。

## 参考项目与致谢

| 项目 | 用途 |
| --- | --- |
| [UIGF-org/mihoyo-api-sdk](https://github.com/UIGF-org/mihoyo-api-sdk) | Java SDK 来源及接口定义；固定提交 `7c3107f95b72e7324af966b2e8989eb8375e5fc7` |
| [seriaati/genshin.py](https://github.com/seriaati/genshin.py) | 米游社 / HoYoLAB 战绩接口、请求与验证处理的对照参考 |
| [XYRQ-Dev/miyoushe-tool](https://github.com/XYRQ-Dev/miyoushe-tool) | 登录及米游社协议流程的对照参考 |
| [UIGF-org/mihoyo-api-collect](https://github.com/UIGF-org/mihoyo-api-collect) | 社区收集的米哈游 API 资料 |

上述均为社区项目，各自遵循原仓库许可证；参考不代表这些项目参与维护本服务。随仓库分发的 UIGF SDK 许可证保留在 [licenses/UIGF-SDK-LICENSE.txt](licenses/UIGF-SDK-LICENSE.txt)，依赖说明见 [vendor/README.md](vendor/README.md)。游戏名称、图片和相关素材权利归各自权利人所有。

## 反馈问题

提交 Issue 时请提供操作步骤、HTTP 状态、返回的 `stage` / `retcode` 与脱敏日志。不要提交手机号、短信验证码、Cookie、SToken、数据库密码或服务器密钥。仅查询本人或已获授权账号，遵循上游服务规则。

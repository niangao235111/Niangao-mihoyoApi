# Niangao-backend

Java17 / Spring Boot3.5.16独立后端，支持米哈游短信登录、Cookie工作台会话、原神国服官服/B服账号总览、角色装备、本人完成安全验证，以及MySQL保存最新成功档案。

本仓库仅包含后端代码、测试、SQL、必要依赖及部署文档，不包含前端。网页或其它客户端通过HTTP请求后端接口。

## 文档

- [全部20个接口：参数、返回、错误码和流程](docs/backend-api.md)
- [完整后端测试报告](docs/backend-test-report.md)
- [云服务器部署](docs/deployment.md)
- [MySQL四张表与保存规则](docs/genshin-archive-storage.md)
- [完整HTTP请求示例](requests/backend-all.http)
- [固定UIGF SDK及来源](vendor/README.md)

## 配置与运行

1. 安装JDK17+和Maven，IDEA打开项目后Reload Maven。`.mvn`使用HTTPS仓库，vendor保留未发布到Central的固定SDK，不能删除。
2. 准备可连接的MySQL和Redis；MySQL创建 `genshinImpact` 库并手工执行 `db/mysql/010_create_genshin_archive.sql`。已有相同四张表时不需要重复创建；应用禁用SQL自动初始化。
3. 在 `deploy/backend.env.example`、`deploy/application-production.example.yaml` 中查看完整配置模板。需要数据库、Redis、实际前端来源、稳定的PHONE_KEY_SECRET和32字节Base64 AES密钥；示例不含实际凭证。
4. 本地可配置标准环境变量，或将敏感配置分别保存到忽略的 `.local/mysql-local.yaml`、`.local/redis-local.yaml`、`.local/workbench-local.yaml`。应用自动导入这三个可选文件。`scripts/init-workbench-session.ps1` 可创建本地AES密钥，不覆盖已有密钥。
5. 在IDEA运行NiangaoApplication，默认仅监听 `127.0.0.1:17892`；或使用下述Maven命令构建后运行JAR。部署步骤见部署文档。

```powershell
mvn -DskipTests package
java -jar target/niangao-mihoyo-0.1.0-SNAPSHOT.jar
```

敏感配置不提交Git；所有实例共享稳定密钥与Redis。生产模板启用Secure Cookie，因此使用HTTPS同源网关，配置真实WORKBENCH_ALLOWED_ORIGINS。前端独立部署，后端不提供网页首页、不包含静态工作台。

## 功能与数据

- 短信流程固定10分钟；工作台7天无活动到期、创建后最长30天。
- 仅支持国服官服cn_gf01与B服cn_qd01，国际服请求被拒绝。
- 工作台实时读取成功后分批保存总览、角色列表、装备详情；失败保留原数据和更新时间。
- saved接口只读数据库，返回最近10条查询记录；不保存每日快照。
- 登录凭证在Redis加密保存，不写入MySQL档案；退出保留档案但撤销会话访问。
- 战绩风控需要本人完成GT3验证；服务端提交结果后可重试受阻请求，不自动解题。
- 当前不实现网站邮箱注册、`/api/auth/*` 或Actuator健康接口。

## 完整测试

```powershell
mvn clean verify
```

完整测试需要已创建四张表的MySQL和Redis。测试使用随机HTTP端口、Redis命名空间及数据库测试账号，只清理自身数据；米游社上游为本地模拟，不发送真实短信或代解验证码。已有77项后端测试全部通过；范围和实际运行检查见测试报告。测试环境应与生产环境隔离。

## 后端发布包

```powershell
./scripts/package-backend.ps1 -OutputDirectory 'C:\path\outside-project\release'
```

脚本只构建Java后端并生成运行ZIP和SHA256；已构建可加-SkipBuild。它不需要Node或前端源码，不会打包本地配置、日志、IDE文件或Maven缓存。发布前应单独完成mvn clean verify。

## 目录

| 目录 | 内容 |
| --- | --- |
| src/main | Controller、Service、Mapper、Client、配置及Redis Lua |
| src/test | 单元测试与真实MySQL/Redis集成测试 |
| db/mysql | 手工建表SQL |
| deploy、scripts | Linux/systemd生产模板及后端打包工具 |
| docs、requests | 接口、测试、部署、数据库说明及HTTP示例 |
| vendor、licenses | 固定SDK仓库与第三方许可证 |

本地若存在workbench目录和前后端联合打包脚本，它们被.gitignore排除，不属于本后端仓库。

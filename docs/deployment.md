# 后端云服务器部署

本仓库仅包含Java后端，前端独立部署并通过HTTP调用本服务。部署需要Java17+、MySQL和Redis。

米哈游 SDK 的出站 HTTPS 客户端使用独立的 TLS 1.2 上下文，避免部分上游节点在 TLS 1.3 协商时超时。证书信任及主机名校验保持开启，不修改 JVM 全局 TLS 设置，短信发送也不会自动重试。此设置用于改善协议兼容性，不能保证上游网络和业务风控始终可用。

## 构建与运行包

执行 `mvn -DskipTests package` 得到 `target/niangao-mihoyo-0.1.0-SNAPSHOT.jar`。构建和运行后端不需要Node或前端目录。

Windows可用 `scripts/package-backend.ps1 -OutputDirectory <项目外绝对目录>` 构建并生成运行ZIP与SHA256。已经构建可加-SkipBuild。脚本不自动运行测试，发布前执行mvn clean verify。ZIP包含app.jar、config配置示例、bin/start.sh、systemd模板、SQL、许可证、接口文档和请求示例，不含实际配置/凭证。

源码内vendor/maven和licenses必须保留，固定UIGF SDK尚未发布Central。运行JAR已经包含必要依赖，运行服务器无需安装Maven或上传vendor。运行包包含完整依赖，体积大于源码；实际大小随依赖版本变化。

## 服务器准备

1. 安装Java17+，将运行包解压到 `/opt/niangao-mihoyo`。
2. 准备服务器可访问的MySQL、Redis；在MySQL中创建 `genshinImpact` 库，手工执行包内 `db/mysql/010_create_genshin_archive.sql`。已有表不需重复创建，脚本不会迁移旧结构；应用禁用SQL自动初始化。
3. 复制 `config/application-production.example.yaml` 为 `config/application-production.yaml`，复制 `config/backend.env.example` 为 `config/backend.env`，填入服务器实际连接、域名和密钥。不要使用本机虚拟机地址或示例密码。
4. AES密钥可用 `openssl rand -base64 32` 生成，PHONE_KEY_SECRET可用 `openssl rand -hex 32`。保持稳定并单独保存；更换AES密钥会使现有加密会话无法恢复，更换PHONE_KEY_SECRET会改变设备身份。迁移已有Redis会话时需安全迁移相同密钥。
5. `chmod 600 config/backend.env config/application-production.yaml`，在解压根目录执行 `bash bin/start.sh`，启动production profile。

数据库账户至少需要四张档案表的SELECT/INSERT/UPDATE权限，建表由另有权限的账户完成。完整集成测试还需清理随机测试数据的DELETE权限，测试应使用独立环境。Redis默认逻辑库1，此实现面向独立Redis，未验证Redis Cluster。数据库URL按服务器网络、认证和TLS要求配置。

## systemd

创建专用用户/组niangao，并保证它能读取应用与受限配置；按实际环境修改niangao-mihoyo.service中的用户、Java路径和工作目录。将服务文件复制至 `/etc/systemd/system/` 后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now niangao-mihoyo
sudo systemctl status niangao-mihoyo
sudo journalctl -u niangao-mihoyo -n 100 --no-pager
```

默认Java堆128～512MB，可按服务器资源调整。服务日志由journal保存，JAR体积不等于进程内存。模板中的用户、路径与内存参数需按实际环境调整。

## 容器健康检查

`GET /api/workbench/health` 是独立健康探针：不要求登录、不访问米哈游上游、只返回 `{"code":200,"message":"成功","data":{"status":"ok"}}`。生产用 Docker Compose 时后端服务配置：

```yaml
healthcheck:
  test: ["CMD", "bash", "-ec", "exec 3<>/dev/tcp/127.0.0.1/17892; printf 'GET /api/workbench/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3; head -1 <&3 | grep -q ' 200 '"]
  interval: 20s
  timeout: 5s
  retries: 6
  start_period: 60s
```

探针必须与包含该接口的 JAR 同批上线：旧 JAR 没有这个路径，提前改探针会让容器被判 unhealthy。此前探针复用 `/api/workbench/session` 并把 401 当作成功，每 20 秒写一条 `工作台接口失败 status=401, stage=unspecified, upstream_retcode=none` 告警（实测 180 条/小时）。应用侧已把该路径上的预期未登录 401 降到 DEBUG，`POST /session/renew` 等其他 401 仍按 WARN 记录。

只读根文件系统（`read_only: true`）还要给文件日志一个可写路径。默认值 `${user.dir}/.local/backend.log` 在容器里是 `/app/.local/backend.log`，不可写时会打印 `Failed to create parent directories` 的 logback ERROR（stdout 日志仍正常，只是没有文件副本）。在 `backend.environment` 里加 `APP_LOG_FILE: /tmp/backend.log`，与 `tmpfs: ['/tmp:size=64m,mode=1777']` 配合即可。


## 独立前端与HTTPS网关

后端默认仅监听127.0.0.1:17892，浏览器经HTTPS同源网关的/api/访问。前端静态文件由网页服务器独立提供，JAR不包含网页。Nginx代理示例（嵌入已有HTTPS server块）：

```nginx
location /api/ {
    client_max_body_size 16k;
    proxy_pass http://127.0.0.1:17892;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout 60s;
    proxy_send_timeout 30s;
    proxy_cache off;
}
```

proxy_pass不带末尾斜杠，保留/api路径。若网关在另一台机器，配置SERVER_ADDRESS监听可用内网地址并调整代理目标。证书、域名及443 server需按实际服务器配置。

FRONTEND_URL和WORKBENCH_ALLOWED_ORIGINS填真实HTTPS来源（包括协议与端口），例如https://example.com；来源白名单逗号分隔。production模板开启Cookie Secure，不用纯HTTP测试会话恢复。当前接口按同源代理设计，不提供任意跨域CORS。Nginx先行拒绝的超大请求可能返回其自身格式，后端错误统一为Result。

## 验证与运维

未登录访问 `GET /api/workbench/session` 应返回401的Result JSON，这表示路由正常响应，不能把401当作服务未启动。本仓库没有Actuator健康接口，/error也不是健康接口。后续使用已授权账号连接，刷新一次档案，确认MySQL保存，再调用saved接口确认只读回显；真实短信和人工验证由本人操作。

测试与接口说明见 [测试报告](backend-test-report.md)、[完整接口](backend-api.md)、[数据库说明](genshin-archive-storage.md)。日志、target、IDE、实际配置、缓存和前端目录不提交仓库。源码瘦身不删除必要SDK或测试，缺少构建产物时重新执行Maven即可。

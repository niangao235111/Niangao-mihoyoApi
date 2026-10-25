# 祈愿接口与升级说明

## 代码分层与本地运行约定

`wish` 已按项目三层架构整理：`controller/WishController` → `service/WishService` → `service/impl/WishServiceImpl` → `mapper/WishMapper`。上游调用在 `client/WishUpstreamClient`；请求模型在 `dto`、返回模型在 `vo`、祈愿记录在 `entity/WishRecord`，协议校验和卡池常量在 `util`。

本次为结构重构，HTTP 路径、JSON 字段和数据库表结构保持兼容，无需重新建表。106项后端回归测试通过。

日常本地后端仅使用站长在 IDEA 启动的17892；工作台5173代理到17892。修改后端后由站长重启IDEA中的后端进程，不另开常驻后端端口。后续前端修改仅限项目内简化workbench，不修改独立博客前端。

日期：2026-10-18。新增 7 个工作台接口，仅原神国服官服 / B 服。前端为独立项目，工具箱新增“祈愿分析”。

## 初始化与配置

先执行原 `010_create_genshin_archive.sql`，再执行 [020_create_genshin_wishes.sql](../db/mysql/020_create_genshin_wishes.sql)，新增祈愿明细与任务两张表，不改变原档案表。应用不自动建表。本地开发数据库已由集成测试执行；服务器未修改或部署。

自动授权请求 `POST https://api-takumi.miyoushe.com/binding/api/genAuthKey`，携带 SToken Cookie、DS1、`x-rpc-client_type=5`，body 包含 `auth_appid=webview_gacha`、`game_biz=hk4e_cn`、game_uid 与 region。固定 SDK 的 AuthKeyApiClient 指向旧 AuthKey A 路径，因此 B 请求显式构造，复用已有安全 transport；记录查询复用 SDK GachaClient。

默认 app_version=2.71.1 与 LK2 盐配对取自 [UIGF salt 列表](https://github.com/UIGF-org/mihoyo-api-collect/issues/1)，不代表该版本是最新版或永久有效。通过环境变量 `GENSHIN_WISH_AUTH_APP_VERSION` / `GENSHIN_WISH_AUTH_DS_SALT` 成对覆盖。不可套用战绩 DS2 盐。

AuthKey 在 Redis 加密缓存最多 30 分钟，这是本站策略，非官方 TTL。自动授权失败可粘贴本人游戏祈愿历史链接，或导入备份。链接仅解析允许参数，从不访问用户提供的任意地址。AuthKey 不返回浏览器、不写数据库或日志；原始链接不写浏览器持久存储。

## 通用参数与鉴权

前缀 `/api/workbench/genshin/wishes`。全部 POST、JSON，复用工作台 Cookie `ng_workbench`、`X-Workspace-CSRF` 与来源白名单。角色必须属于已验证的工作台账号，不能任意传 owner_stuid。

`role` 示例：

```json
{"game_uid":"500000001","region":"cn_qd01","game_biz":"hk4e_cn"}
```

官服 region=cn_gf01，B 服 region=cn_qd01；兼容工作台 hk4e_bilibili 选择器，上游国服 game_biz 统一 hk4e_cn。

| 方法与路径 | 请求体 | 返回 Result.data |
| --- | --- | --- |
| POST `/sync` | `{role, history_url?: string}` | 后台任务；未提供链接则自动授权。HTTP/code 均为 202 |
| POST `/sync/status` | `{role, task_id}` | 该账号的任务状态 |
| POST `/sync/cancel` | `{role, task_id}` | 设置取消标记，当前有限超时请求结束后停止 |
| POST `/records` | `{role, pool?, rank?, before_id?, limit?, from_date?, to_date?}` | `{list,next_id,has_more}` |
| POST `/summary` | role 对象本身 | `{total,pools,latest_task}` |
| POST `/import` | `{role, document: UIGF对象}` | 导入任务与新增/重复计数 |
| POST `/export` | role 对象本身 | UIGF 文档，位于 Result.data 内 |

其他成功请求 HTTP/code=200。`pool` 为 100/200/301/302/500，301 合并原始 400 记录；`rank` 为整数 3～5；limit 默认 50、最大 100。before_id 为字符串游标。时间筛选使用国服 YYYY-MM-DD，起止均包含。

任务字段：task_id、kind（SYNC/IMPORT）、status、cancel_requested、fetched_count、inserted_count、duplicate_count、progress、error_stage、error_reason、upstream_retcode、created_at、updated_at、finished_at。状态为 RUNNING/SUCCESS/PARTIAL/FAILED/CANCELLED/INTERRUPTED。SUCCESS 仅表示本次可查询区间拉取完成，不代表自开服以来历史完整。

记录字段：id（字符串）、gacha_type、pool_group、item_id、name、item_type、rank_type、count、time（国服当地时间）、source（UPSTREAM/IMPORT）。item_id 允许缺失，不依赖物品目录完整。

每卡池统计：total、five_stars、four_stars、pulls_since_last_five、average_five_interval、earliest_time、latest_time、history_complete=false，以及最近 50 条 five_star_history。首个五星前的截断区间不参与平均；没找到五星时距上次五星抽数为 null。不据此断言大保底、定轨或下一抽结果。

## 同步与存储

- MySQL：`ng_genshin_wish_record` 明细、`ng_genshin_wish_sync_run` 任务；复用账号表。每账号+记录 ID 唯一；相同 ID 内容冲突不覆盖旧数据。
- Redis：短期加密授权、带所有者校验的任务锁及限流。任务结束释放锁，锁到期后状态查询会将失联 RUNNING 标记为 INTERRUPTED。
- 单账号同一时间一项同步/导入，单实例最多两个后台同步，满载返回 429；成功申请任务后 10 秒冷却。
- 默认页间隔 750ms，通过 `GENSHIN_WISH_PAGE_DELAY_MS` 调整。每页 20 条，每池最多 2000 页，任务最多 20 分钟。
- 用 end_id 翻页；检查 UID/region 和游标严格前进，每页短事务落库。取消或退出后的迟到页不落库，网络等待不持有事务。
- 上游 HTTP 429 按 2 秒、4 秒退避，最多额外尝试两次；耗尽后停止，不轮换 IP 绕过限流。AuthKey 生成不自动重试。
- 当前每次从头回扫上游仍可读取的记录，幂等增加新数据，以补齐上次部分失败留下的旧缺口。没有“遇到任意重复页立即停止”的优化。
- 不创建每日快照，不自动迁移旧 gacha_* 表。无法从上游恢复的旧记录需要已有备份。
- 单次统计/导出最多 100000 条，超限返回 413；明细仍可分页读取。

## UIGF 导入导出

初版导入限 `info.version=v4.2`、hk4e 恰好一个账号、UID 等于当前所选账号、timezone=8、count=1，单次最多 20000 条。完整校验后单事务写入；内容冲突回滚整次导入。导入来源标记 IMPORT，不作为经官方验证的数据。

导入请求为 JSON 而非 multipart，只有此路径允许最大 5 MiB，其余接口仍为 16 KiB。Nginx 每一层需单独为该路径放宽，Docker 按现有后端服务地址替换 proxy_pass：

```nginx
location = /api/workbench/genshin/wishes/import {
    client_max_body_size 5m;
    proxy_pass http://127.0.0.1:17892;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
    proxy_cache off;
}
```

## 错误与测试

400 参数/文件错误、401 会话失效、403 归属/CSRF/来源拒绝、404 任务不存在、409 并发/数据冲突、413 超限、422 上游拒绝、429 本站并发/冷却、502 网络/响应异常、503 存储不可用。后台失败由任务 status 与 error_* 表达，查询状态本身成功仍返回 200；安全验证按对应阶段提示处理，不复用未证实适用的角色战绩证明。

新增协议与集成测试覆盖 SDK 请求与 DS1、链接编码、ID 精度、统计边界、真实 MySQL 幂等/冲突事务、部分失败补全、取消、注销、隔离、缓存加密、重复游标和 HTTP 鉴权/体积上限。自动测试上游全部模拟，不发短信；不能据此宣称真实国服/B服授权均已跑通。

设计与来源见 [设计文档](genshin-wish-design.md)，请求示例见 [wishes.http](../requests/wishes.http)。

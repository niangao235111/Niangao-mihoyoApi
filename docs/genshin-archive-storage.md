# 玩家档案建表说明

仅保留最新成功档案，不保存每日快照或历史装备版本。刷新成功更新对应数据，失败保留上次成功结果和原更新时间。查询记录只保留状态、进度和脱敏错误，不保存历史档案内容。

建表文件：[010_create_genshin_archive.sql](../db/mysql/010_create_genshin_archive.sql)。用户创建四张表后，已于2026-10-16完成持久化接入。本地 `.local/mysql-local.yaml` 目标库已由 `hmdp` 改为 `genshinImpact`，保留原凭证。应用导入该本地文件，SQL自动初始化为never，不自动建表或修改现有目录/图片表。

首次建表可在DataGrip打开SQL文件，检查 `USE genshinImpact` 后执行。已经创建四张表时无需再次执行。IF NOT EXISTS不会迁移旧版同名表。SQL按MySQL 8.0.16+编写，使用 [JSON](https://dev.mysql.com/doc/refman/8.0/en/json.html) 和 [CHECK约束](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)。本次代码接入未执行DDL。

| 表 | 用途 |
| --- | --- |
| ng_genshin_account | 米哈游账号归属、游戏UID和国服服务器 |
| ng_genshin_sync_run | 一次读取的进度、失败阶段及部分成功状态 |
| ng_genshin_overview | 最新成功总览与四项优先统计 |
| ng_genshin_character | 各角色最新基础信息、独立保存的武器/圣遗物/技能/面板 |

账号主键按 owner_stuid + region + game_uid 隔离，不能仅凭游戏 UID 提供读取。owner_stuid 从已验证服务端会话取得，不能接受浏览器任意指定。官服/B服统一保存 game_biz=hk4e_cn，分别使用 cn_gf01/cn_qd01。已有 gacha_items 的内部 id 不直接当作米哈游角色ID；日后展示目录可按 game_item_id 做可选关联，目录不完整时不应阻止保存玩家档案。

已实现的写入规则：

1. 只保存本站白名单 DTO，不保存原始上游响应或登录凭证。总览中的结构化统计与 overview_json 在同一事务由同一 DTO 生成。
2. 总览、基础列表、各批次装备分别成功才写；错误保留已有数据。缺失数字保持 NULL，不能当作 0。
3. 角色列表成功后，以 account 行锁和 roster_sync_id 比较阻止旧列表覆盖新列表；事务内将当前角色标记 in_roster，旧记录留存但不再作为当前列表展示。只有基础刷新时，不清除已有 detail_json，页面分别展示基础和详情时间。
4. source_sync_id/basic_sync_id/detail_sync_id 比较读取代次，旧响应不得覆盖新结果；sync_run 记录仅作内部追踪，不包含 Cookie/验证码。读取记录清理不应删除最新档案，因此这些代次列使用逻辑关联。
5. DATETIME 由应用以 UTC 读写，页面按用户时区展示。登出只撤销登录会话，档案保留，但无有效归属授权时不能读取。

第一版采用JSON保存复杂装备结构，角色基础字段和总览核心统计保留列。武器、圣遗物的精确属性依照米游社白名单响应保存，不从现有目录推算玩家面板。

## 页面与接口

进入「原神档案」只读取数据库；没有数据时点击「刷新档案」首次采集。刷新成功的总览、基础列表、每批装备分别提交事务；失败保留旧数据，页面显示原成功时间和失败信息。已保存装备也会在显式刷新后重新读取。页面保留前10位优先加载、搜索/筛选后按需补全的策略；未加载的角色装备不应宣称已全部刷新。

新增 `POST /api/workbench/genshin/saved`，请求为 `game_uid,game_biz,region`，受Cookie、同源、CSRF和已验证会话中的角色归属约束。返回 `uid,overview,characters,detail_updated_at,queries`；无成功数据的部分为null（JSON中省略），空角色列表则count=0。保存读取不调用米游社。queries返回该账号最近10条记录，可在页面展开查看。

工作台 `/api/workbench/genshin/overview`、`characters`、`characters/details` 实时查询并自动保存，人工验证成功后的重试同样保存。每个HTTP读取/装备批次单独产生sync_run，不将三个阶段伪装成一次完整账号刷新。兼容的 `/api/genshin/*` 手工凭证接口保持无状态；数据库档案统一经工作台会话访问。

网络请求在数据库事务外执行；落库事务锁定账号行，依据批次编号阻止迟到响应覆盖新数据。数据库不可用时返回503，不清空已有档案；初始建立查询记录失败时不继续请求米游社。登出保留档案但无法读取，重新连接相同米哈游账号可恢复。查询记录保留状态和固定错误分类，不写入原始上游消息或凭证。

## 运行与验证

新增Spring JDBC和MySQL Connector/J，默认 `genshin.storage.enabled=true`。连接来自忽略的 `.local/mysql-local.yaml`；部署可通过标准 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 覆盖。连接池最多5条连接，等待超时5秒，连接时间UTC。必须保持MySQL与Redis可访问，首次在IDEA Reload Maven，再重启NiangaoApplication。前后端依然分离。

真实MySQL/Redis测试已覆盖保存/回读、归属/服务器隔离、失败保留、部分成功、事务回滚和旧请求覆盖保护；详细范围见 [最终测试报告](backend-test-report.md)。浏览器也已用合成数据检查数据库优先读取和B服验证闭环，临时截图已清理。真实账号首次保存需重启后端后点击刷新档案。

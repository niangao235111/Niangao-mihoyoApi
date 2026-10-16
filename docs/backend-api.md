# 后端完整接口文档

更新：2026-10-16。对应当前实现，共20个业务接口；另有框架内部错误兜底 `/error`。
本地地址 `http://127.0.0.1:17892`，部署后经同源Nginx的 `/api/` 访问。所有示例均为占位数据。

## 1. 统一协议

- 除 `GET /api/workbench/session` 外，业务接口均为POST，使用 `Content-Type: application/json`。
- 字段采用snake_case；UID、通行证ID、角色ID均传字符串。数值不能代替字符串。
- 成功HTTP 200：`{"code":200,"message":"成功","data":{...}}`；具体成功message以实际响应为准。
- 失败HTTP状态与code相同：`{"code":422,"message":"安全错误说明","data":{"stage":"genshin_overview","upstream_retcode":1034,"reason":"upstream_verification_required"}}`。
- 无错误详情时data=null；VO中的可空字段为null时通常省略。缺失统计不等于0。
- 拒绝未知JSON字段、非法类型、缺失必填字段。API请求体最大16 KiB，超限413。
- `/api/` 返回 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`。
- 时间为ISO 8601 UTC，例如 `2026-10-16T01:02:03Z`；数据库DATETIME统一按UTC读写，页面按本地时区显示。
- 上游不自动跳转。仅凭证读取、关联账号及原神总览/角色/装备只读查询，在连接超时、连接中断等网络错误时最多额外重试2次，间隔250ms、500ms；同一次网页请求的只读阶段共享45秒预算，单次连接最多5秒。HTTP错误响应、业务拒绝/风控、无效JSON或证书校验失败不会触发自动重试。
- 短信发送、验证码提交、人工验证任务创建和验证结果提交均不自动重试。短信/登录结果不明时先调用`login/status`确认；状态读取失败应保留当前页面会话并阻止重复提交。已获取的凭证在连接失败后仅暂存页面内存，用户可点击重新连接；刷新页面会丢失这份暂存。
- 网络错误的`data`包含`stage`和`reason=upstream_network_error`；只读重试耗尽时还包含`retry_count`（额外重试次数，最多2）。阶段包括`login_sms`、`login_verify`、`stoken_to_ltoken`、`binding_roles`、`genshin_overview`、`genshin_characters`、`genshin_equipment`。非JSON代理错误页应按HTTP状态和当前操作提示，不应统一显示为未知服务错误。

### 1.1 身份和有效期

| 类型 | 用途 | 本站有效期/保存位置 |
| --- | --- | --- |
| 登录流程session_id | 申请短信、提交短信验证码 | 默认10分钟固定TTL，Redis；不滑动续期 |
| stoken/stuid/mid | 已授权米哈游通行证凭证 | 官方有效期由上游决定；本站不承诺官方TTL |
| ng_workbench Cookie | 工作台登录、保存档案访问 | 7天无活动到期、创建后最长30天；服务端保存Token哈希 |
| csrf_token | 工作台POST请求防护 | 绑定工作台会话，只在页面内存中持有 |
| verification_id | 战绩人工验证任务 | 最多5分钟且不超过父会话；一次提交即消费 |

工作台Cookie为HttpOnly、Path=/api、SameSite=Lax，生产HTTPS设置Secure。响应JSON不返回Cookie Token。凭证在Redis中使用AES-256-GCM加密；MySQL仅保存白名单档案和查询记录。

### 1.2 国服范围

仅支持原神国服官服 `game_biz=hk4e_cn, region=cn_gf01` 和B服 `game_biz=hk4e_cn, region=cn_qd01`。
兼容本站旧B服别名 `hk4e_bilibili/cn_qd01`。落库统一为hk4e_cn。`hk4e_global`、所有os_*及其它游戏不支持。
角色授权必须同时核对通行证归属、游戏UID及服务器，不能仅凭任意UID读取数据库。

## 2. 全部业务接口索引

| # | 方法 | 路径 | 作用 | 身份 |
| --- | --- | --- | --- | --- |
| 1 | POST | /api/mihoyo/login/start | 创建短信流程，不发送短信 | 大陆手机号 |
| 2 | POST | /api/mihoyo/login/sms | 发送短信/继续短信阶段验证 | session_id |
| 3 | POST | /api/mihoyo/login/verify | 提交短信码，取得米哈游凭证 | session_id |
| 4 | POST | /api/mihoyo/login/status | 查询登录流程状态 | session_id |
| 5 | POST | /api/mihoyo/login/delete | 删除登录流程 | session_id |
| 6 | POST | /api/mihoyo/game/accounts | 手工凭证查询绑定角色 | stoken/stuid/mid |
| 7 | POST | /api/genshin/overview | 无状态查询账号总览 | 凭证+角色选择器 |
| 8 | POST | /api/genshin/characters | 无状态查询角色列表 | 凭证+角色选择器 |
| 9 | POST | /api/genshin/characters/details | 无状态查询装备详情 | 凭证+选择器+角色ID |
| 10 | POST | /api/workbench/session | 用凭证创建/更换工作台会话 | stoken/stuid/mid |
| 11 | GET | /api/workbench/session | 恢复会话、取得CSRF与角色 | Cookie |
| 12 | POST | /api/workbench/session/renew | 活跃续期 | Cookie+CSRF |
| 13 | POST | /api/workbench/session/logout | 撤销会话并清Cookie | Cookie+CSRF |
| 14 | POST | /api/workbench/game/accounts | 重新查询/筛选绑定角色 | Cookie+CSRF |
| 15 | POST | /api/workbench/genshin/overview | 实时总览并保存 | Cookie+CSRF+选择器 |
| 16 | POST | /api/workbench/genshin/characters | 实时角色列表并保存 | Cookie+CSRF+选择器 |
| 17 | POST | /api/workbench/genshin/characters/details | 实时装备详情并保存 | Cookie+CSRF+选择器 |
| 18 | POST | /api/workbench/genshin/saved | 读取数据库档案及最近查询记录 | Cookie+CSRF+选择器 |
| 19 | POST | /api/workbench/genshin/verification/start | 创建战绩人工验证任务 | Cookie+CSRF+选择器 |
| 20 | POST | /api/workbench/genshin/verification/submit | 提交本人完成的GT3结果 | Cookie+CSRF+任务ID |

`/api/auth/*` 尚未实现；没有网站邮箱登录、用户注册或Swagger/Actuator接口。`/error` 是容器内部错误分发，不应作为健康检查或业务接口调用。

## 3. 短信登录流程

### 3.1 POST /api/mihoyo/login/start

请求：`{"mobile":"13800000000","area_code":"+86"}`。

| 字段 | 类型/必填 | 限制 |
| --- | --- | --- |
| mobile | string，必填 | 大陆11位手机号，正则1[3-9][0-9]{9} |
| area_code | string，可选 | 仅+86；省略时按+86 |

创建流程，不访问短信发送接口。成功data包含session_id、state=CREATED、expires_in、retry_after。expires_in为流程剩余秒数，不是官方凭证有效期。容量默认20个活动流程；达到限制429。手机号原文不写日志或Redis索引。

### 3.2 POST /api/mihoyo/login/sms

首次请求：`{"session_id":"start返回值"}`。

若收到state=SMS_CHALLENGE，由本人完成challenge，再向同一接口提交：

```json
{
  "session_id": "start返回值",
  "geetest_result": {
    "geetest_challenge": "组件结果",
    "geetest_validate": "组件结果",
    "geetest_seccode": "组件结果"
  }
}
```

只在确实点击发送时调用，可能发送真实短信。发送成功state=SMS_SENT，sent_new=true表示新发、false表示上游复用既有短信；message提供提示。手机号冷却默认60秒，多实例共享。超时或结果不明不自动重新发送。

### 3.3 POST /api/mihoyo/login/verify

请求：`{"session_id":"有效流程ID","captcha":"123456"}`。captcha必填，4～8位数字字符串。

可能返回LOGIN_CHALLENGE；本人完成后向本接口再次提交同一个session_id、短信captcha及geetest_result。pending_operation用于确认应该继续sms还是login，不能把某一阶段的验证结果提交到另一阶段。

成功state=LOGGED_IN，只有本次响应带credentials：

```json
{
  "credential_type": "stoken",
  "stoken": "敏感凭证",
  "stuid": "123456",
  "mid": "敏感账号标识",
  "login_ticket": "可选票据"
}
```

login_ticket可能省略。成功后应立即交换工作台会话；不要把这些字段写入浏览器存储、日志或公开请求示例。此接口不创建独立网站用户。手机号未注册时返回422，本项目不代为注册。

### 3.4 POST /api/mihoyo/login/status

请求：`{"session_id":"有效流程ID"}`。只查询状态，不再返回credentials。LOGGED_IN状态也不能重新取出此前凭证；丢失成功响应时需要重新授权。session不存在/过期返回410。

### 3.5 POST /api/mihoyo/login/delete

请求：`{"session_id":"流程ID"}`。成功data为 `{"message":"会话已清除"}`。删除本站短信流程，不撤销官方SToken、不退出已创建的工作台会话。对不存在/已过期流程的删除为幂等操作，仍返回200；短信冷却记录不随之清除。

### 3.6 LoginResponse完整字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| session_id | string | 登录流程ID |
| state | string | 下述状态之一 |
| expires_in | number | 固定TTL内剩余秒数 |
| retry_after | number | 会话记录的发送等待秒数；以429限制为准 |
| challenge | object，可选 | 上游GT3/GT4任务，需按真实版本初始化组件 |
| pending_operation | string，可选 | sms或login |
| aigis_session_id | string，可选 | 短期验证关联信息，不是工作台Token |
| verification_url | string，可选 | 配置的工作台验证地址 |
| sent_new | boolean，可选 | 是否新发短信 |
| message | string，可选 | 本站展示提示 |
| credentials | object，可选 | 仅verify成功时返回一次 |

状态：CREATED → SMS_PROCESSING → SMS_CHALLENGE或SMS_SENT → LOGIN_PROCESSING → LOGIN_CHALLENGE或LOGGED_IN。FAILED表示失败/结果不明/执行中断，不能自动重复原动作。处理中或阶段不匹配409；操作锁超时默认90秒，验证码验证尝试最多3次，超过429。

GT3提交字段为geetest_challenge、geetest_validate、geetest_seccode。GT4提交字段为captcha_id、lot_number、pass_token、gen_time、captcha_output，全部使用非空字符串；数值时间戳应先转字符串。组件额外字段必须剔除，否则未知字段400。captcha是短信码，captcha_output是GT4结果，不能混淆。官方真人/实名等限制无法由普通极验结果代替。

## 4. 工作台会话与账号

### 4.1 POST /api/workbench/session

请求：`{"stoken":"已授权凭证","stuid":"123456","mid":"账号标识"}`。可使用GameAccountsRequest中的可选字段，但创建会话时忽略筛选和调试DS，始终核验并保存全部国服绑定角色。

stoken/mid非空且最长8192字符，Cookie字段拒绝控制字符和分隔符；stuid为1～20位数字字符串。先向上游核验凭证与绑定关系，再创建加密Redis会话；失败不签发Cookie。成功设置 `Set-Cookie: ng_workbench=...; Path=/api; HttpOnly; SameSite=Lax`，HTTPS生产另含Secure。

成功data示例：

```json
{
  "connected": true,
  "account_label": "米哈游账号 · 3456",
  "csrf_token": "会话绑定的随机CSRF值",
  "created_at": "2026-10-16T01:02:03Z",
  "expires_at": "2026-10-23T01:02:03Z",
  "absolute_expires_at": "2026-11-15T01:02:03Z",
  "roles": []
}
```

JSON不返回Cookie Token或米哈游凭证。携带旧Cookie更换账号时，新会话创建成功后撤销旧会话及验证任务。

### 4.2 GET /api/workbench/session

无请求体，只带Cookie；成功返回同一会话摘要及csrf_token、过滤后的国服角色快照。成功恢复会滑动延长空闲有效期，但不会超过absolute_expires_at。401表示会话不存在/无法恢复，应重新连接。此路由不访问米游社，不向MySQL保存档案。

### 4.3 POST /api/workbench/session/renew

请求体 `{}`；Cookie和 `X-Workspace-CSRF: csrf_token`。成功返回更新后的会话摘要。前端只在实际用户活动时按15分钟节流调用，不设永久后台续期；服务端使用Redis时间决定剩余期限。

### 4.4 POST /api/workbench/session/logout

请求体 `{}`；有效会话需Cookie+CSRF。撤销Redis会话、加密凭证、短期战绩验证任务，Set-Cookie Max-Age=0。缺失或已失效Cookie可幂等退出。不会删除MySQL档案，也不撤销官方凭证。

### 4.5 工作台POST通用要求

除首次创建会话外，POST必须带Cookie和X-Workspace-CSRF。Origin若存在必须精确匹配配置白名单（包括协议和端口）；Sec-Fetch-Site=cross-site会拒绝。无Cookie401、错误CSRF403、外部Origin403。浏览器通过同源Nginx/Vite代理调用，不需要开放任意跨域来源。命令行可以不带Origin，但仍须Cookie/CSRF。

### 4.6 POST /api/workbench/game/accounts

请求 `{}` 获取两个国服渠道；也可 `{"game_biz":"hk4e_cn"}` 筛官服或 `{"game_biz":"hk4e_bilibili"}` 筛B服。这里game_biz作为本站兼容渠道筛选参数；响应中的B服实际game_biz仍可能是hk4e_cn，必须结合region判断。工作台DTO不接受空字符串筛选或额外凭证字段。

响应data：`{"auth_flow":"stoken_to_ltoken_to_roles","count":2,"list":[...]}`。

### 4.7 POST /api/mihoyo/game/accounts（无状态兼容接口）

请求：`{"stoken":"凭证","stuid":"123456","mid":"账号标识","game_biz":"hk4e_bilibili"}`。前三项必填；game_biz可省略、空白按未筛选；ds为可选调试头，格式数字,小写字母数字,32位小写十六进制，普通调用不要提供。

先以SToken换LToken，再取绑定角色；只返回国服官服/B服。未知渠道400，凭证失效422，HTTP/JSON异常502。不创建工作台Cookie，不保存MySQL档案。

GameAccount字段：game_biz、game_uid、region、region_name、nickname、level、game_name、is_chosen、is_official；除标识外的可选字段可能省略。game_uid始终为字符串，level为number，两个标记为boolean。响应不透传上游未知字段或LToken。

## 5. 角色选择器与实时档案

### 5.1 选择器

工作台使用：

```json
{"game_uid":"506005588","game_biz":"hk4e_cn","region":"cn_qd01"}
```

详情另外传 `"character_ids":["10000089"]`，1～40个ID字符串，每个1～10位数字；网页每批10个并按需读取。game_uid为1～20位数字字符串。只接受当前会话所属国服角色，国际服或不匹配组合400，未绑定UID403。

### 5.2 POST /api/workbench/genshin/overview

请求选择器；实时访问米游社成功后保存总览和查询记录。上游失败保留数据库已有值。返回GenshinOverview，见数据模型。数据库503时不会默默宣称已保存。

### 5.3 POST /api/workbench/genshin/characters

请求选择器；返回Characters，基础角色列表和上游提供的武器。成功后事务替换当前角色集合的in_roster标记，保留已有独立装备详情及其原更新时间。不能用基础列表空技能覆盖完整详情。

### 5.4 POST /api/workbench/genshin/characters/details

请求选择器+character_ids。返回Characters，其中成功角色detailed=true，包含技能、武器、圣遗物、属性。只允许上游返回请求中的角色；未请求角色502。缺失部分角色时已成功角色可保存，查询记录PARTIAL，网页保留其余旧数据并显示缺失提示。

### 5.5 无状态兼容路由

`POST /api/genshin/overview`、`/characters`、`/characters/details` 请求为选择器加stoken、stuid、mid（与账号查询相同限制）。只实时查询，不保存MySQL、不使用工作台人工验证证明。详情仍需character_ids。

网页应使用workbench路由，避免反复把米哈游凭证传给浏览器。服务端每次实时查询仍核对实际绑定关系；LToken、设备指纹、DS和Cookie不返回网页。

## 6. 保存档案及查询记录

### POST /api/workbench/genshin/saved

请求选择器。仅从MySQL读取，不请求米游社；必须有有效工作台会话和该角色归属。

```json
{
  "uid": "506005588",
  "overview": {"uid":"506005588","achievements":1572,"updated_at":"2026-10-16T01:02:03Z"},
  "characters": {"uid":"506005588","count":1,"list":[{"id":"10000089","name":"芙宁娜","detailed":true}],"updated_at":"2026-10-16T01:02:03Z"},
  "detail_updated_at": {"10000089":"2026-10-16T01:02:03Z"},
  "queries": []
}
```

示例省略了角色的部分可选字段。首次未保存时overview/characters省略，detail_updated_at为空对象、queries为空数组。成功读过空列表时characters.count=0，有明确的列表更新时间。

页面打开只调用saved；用户点击刷新才实时查询。每个部分各自保存最新成功版本，不做每日快照。详情与基础信息可能不是同一时刻，不能把列表时间当成装备更新时间。登出保留数据，重新连接相同账号可读；其他米哈游账号即使使用相同游戏UID也不能读到该归属记录。

QueryRecord（最近10条，按id倒序）：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| id | number | 内部读取代次 |
| status | string | RUNNING/SUCCESS/PARTIAL/FAILED/CANCELLED |
| overview_succeeded / roster_succeeded | boolean | 此HTTP请求是否成功保存对应部分 |
| expected_detail_count | number，可选 | 本批计划读取详情数 |
| succeeded_detail_count / failed_detail_count | number | 本批成功/失败详情数 |
| error_stage | string，可选 | 安全的阶段标识 |
| upstream_retcode | number，可选 | 上游业务码 |
| error_reason | string，可选 | 本站固定错误分类，无原始消息或凭证 |
| started_at / finished_at | string | 开始/结束UTC时间，未结束时后者省略 |

每个总览、列表或装备批次单独一条记录，不表示一次完整账号同步；未加载的角色装备不计入本批。进程被强制中断时历史记录可能保持RUNNING，不能据此认为仍有后台任务在执行。

## 7. 战绩人工安全验证

### 7.1 POST /api/workbench/genshin/verification/start

先读取实时档案，得到1034/5003/10035/10041的验证类失败，再请求本接口。请求为同一角色选择器；没有该角色受阻上下文409，账号已变化409，申请间隔不足10秒429，并发处理中409。

成功data：

```json
{
  "verification_id":"00000000-0000-4000-8000-000000000000",
  "expires_at":"2026-10-16T01:07:03Z",
  "challenge":{"gt":"组件站点标识","challenge":"当前任务挑战","new_captcha":1,"success":1,"use_v4":false}
}
```

服务端固定使用国服战绩createVerification，is_high=true，保持原请求设备、场景和trace。当前档案只支持GT3，意外返回其它版本会明确502；不使用海外验证接口。expires_at为客户端提示，服务端期限不超过父会话TTL。

### 7.2 POST /api/workbench/genshin/verification/submit

```json
{
  "verification_id":"00000000-0000-4000-8000-000000000000",
  "geetest_challenge":"本人完成组件后得到",
  "geetest_validate":"本人完成组件后得到",
  "geetest_seccode":"本人完成组件后得到"
}
```

verification_id必须小写UUID格式，三项结果非空且各不超过2048字符。challenge必须对应当前任务；不匹配403，重复提交/过期/跨会话410，上游拒绝422。领取后任务即消费，即使提交失败也需重新获取；不会自动重试。

成功data为message提示。上游通过后的challenge仅加密存服务端，后续同账号/服务器/设备档案请求携带x-rpc-challenge。前端应重试受阻读取一次并继续未完成装备批次；再次被风控则停止，等待手动操作。提交成功只证明验证接口接受结果，不保证上游必然放行所有档案。

## 8. 档案数据模型

### 8.1 GenshinOverview

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| uid | string | 所选游戏UID |
| nickname / avatar | string，可选 | 昵称、允许域名的HTTPS头像 |
| level | number，可选 | 冒险等级 |
| achievements / active_days | number，可选 | 成就数、活跃天数 |
| spiral_abyss | string，可选 | 上游当前深渊层间，例如12-3 |
| theater | object | max_act幕数、unlocked是否解锁、has_data是否有记录，各字段可省略 |
| extra | array | Metric列表：key、label、value，包含角色数/宝箱/神瞳等实际返回项 |
| explorations | array | name、percentage（0～100百分比）、level（可选） |
| updated_at | string | 此次总览成功读取时间 |

剧诗未解锁、没有记录与字段未知分别处理；不把未知成就或幕数补成0。深渊/剧诗这里是概要，不包含历期逐场战斗。

### 8.2 Characters与Character

Characters：uid(string)、count(number)、list(Character[])、updated_at(成功读取时间)。

Character：id(string)、name、icon、element、rarity、level、constellation、friendship、weapon、skills、artifacts、properties、detailed(boolean)。基础请求中技能/圣遗物/属性可能为空，detailed=false；不代表没有装备。

- element通常为Anemo/Geo/Electro/Dendro/Hydro/Pyro/Cryo。
- constellation是已解锁命座数，friendship是好感等级，未知则省略。
- weapon：id、name、icon、rarity、level、refinement、main_property、sub_property。
- skills[]：id、name、level、type、icon；type 1/2/3通常对应普攻/战技/爆发，保留上游值。
- artifacts[]：id、name、icon、rarity、level、position、position_name、set_name、main_property、sub_properties[]。
- Property：type(string属性ID)、name、value(string，保留上游百分号/格式化值)。
- 图片仅展示后端允许的HTTPS资源；不返回javascript等危险协议。
- 不生成SSS/ACE等第三方评分；数据库目录资料不用于推算玩家实际装备面板。

## 9. 错误码与恢复方式

| HTTP/code | 常见场景 | 处理 |
| --- | --- | --- |
| 400 | 非法/缺失字段、未知字段、数字代替ID字符串、国际服、渠道组合错误 | 修正请求 |
| 401 | 工作台过期、无法解密、明确SToken转换阶段-100/-101 | 重新连接，不覆盖已有数据库档案 |
| 403 | Origin/CSRF拒绝、UID不属于账号、验证码任务不匹配 | 校正来源、刷新会话或选择器 |
| 404 | 路由不存在 | 核对路径，不把/error当业务接口 |
| 405 | 方法不支持 | 使用文档规定方法，查看Allow头 |
| 409 | 阶段不匹配、处理中、未触发战绩验证、角色变化 | 等待/查询状态/重新读取当前角色 |
| 410 | 短信流程或验证任务过期、已消费 | 重新创建相应流程 |
| 413 | 请求体超过16 KiB | 减小批次/移除额外字段 |
| 415 | 非application/json | 修正Content-Type |
| 422 | 上游业务拒绝、战绩未公开、风控或官方身份验证 | 按reason和stage处理，不循环重试 |
| 429 | 登录容量、短信冷却、验证次数或申请间隔限制 | 等待后由用户决定再操作 |
| 500 | 未预期服务器错误 | 查看脱敏日志 |
| 502 | 上游网络/非JSON/字段异常或不支持的验证任务 | 保留旧数据，稍后重试 |
| 503 | Redis/MySQL不可用、加密密钥配置错误 | 检查服务和配置 |

常见stage：stoken_to_ltoken、binding_roles、genshin_device_fp、genshin_overview、genshin_characters、genshin_equipment、genshin_verification_create、genshin_verification_submit。

reason=upstream_verification_required表示战绩风控，保留工作台会话。reason=archive_storage_unavailable表示档案数据库异常。明确SToken转换失效时工作台返回401、reason=mihoyo_credentials_expired；普通502、战绩风控或LToken场景错误不等于官方SToken失效。verification_trace是可选诊断关联值，不是凭证，不需要由浏览器提供。

SDK的HttpRetryPolicy WARN不能单独当成失败依据；看业务日志中的实际stage、http_status和upstream_retcode。

## 10. 调用示例与部署说明

开发环境可用IDEA的 [完整请求示例](../requests/backend-all.http)。示例只声明请求，不会自动发送短信；手机号和凭证必须替换成本人合法取得的值。

建议流程：start → sms（必要时人工验证）→ verify → workbench/session → 保存HttpOnly Cookie与内存CSRF → genshin/saved → 用户点击刷新时overview/characters/details → 风控时verification/start与submit → 受阻读取重试。

工作台Cookie请求示例：

```bash
curl -X POST 'http://127.0.0.1:17892/api/workbench/genshin/saved' \
  -H 'Content-Type: application/json' \
  -H 'X-Workspace-CSRF: 替换为csrf_token' \
  --cookie 'ng_workbench=替换为Cookie值' \
  --data '{"game_uid":"506005588","game_biz":"hk4e_cn","region":"cn_qd01"}'
```

云服务器依赖Java17+、MySQL已创建四张表、Redis及官方国服服务出站访问；后端与前端分离，JAR不含网页。具体部署和上传清单见 [部署说明](deployment.md)，测试结果见 [后端测试报告](backend-test-report.md)。官方凭证和验证码不能放在上传包、HTTP样例或接口文档中。

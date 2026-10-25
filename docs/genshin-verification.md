# 战绩网页安全验证

2026-10-19 本地调整：创建与提交 GT3 任务统一使用官方浏览器网页协议。

- GET `/game_record/card/wapi/createVerification?is_high=true`
- POST `/game_record/card/wapi/verifyVerification`，仅提交极验原样返回的三个 `geetest_*` 字段。
- 网页 DS1：`MD5(salt + timestamp + nonce)` 对应的规范串为 `salt=…&t=…&r=…`，网页版本 `2.3.0`。该公开协议盐来自官方网页，并非用户密钥。
- 战绩查询仍使用原来的 App DS2；两种协议不混用。
- 保留同一任务的账号 Cookie、设备 ID、设备指纹、触发接口路径及 trace；凭证仅加密保存在服务端。
- 非零上游 retcode（包括 10306）一律拒绝，不保存放行凭证、不自动重试。任务只能提交一次。

核对来源：[官方战绩网页](https://webstatic.mihoyo.com/app/community-game-records/index.html) 当日加载的 `bundle_546fbd77404c448f92cc.js`，模块 1220 的网页签名配置、模块 25 的网页/App 分支与 `$geetest` 的创建/提交流程。

此前实现采用 `/game_record/app/card/wapi/` 与战绩 DS2 配置，与浏览器分支不一致。本次先消除该兼容差异；只有日志中的 10306 仍不足以证明其唯一原因。

验证要求：重启本地 Java 服务，等待旧任务失效，重新读取档案并由站长亲自完成一个新任务。须同时确认提交返回 0、后续档案可读取，才能确认真实上游问题解决。模拟接口回归测试只能验证协议与状态处理，不能代替真实人工验证。

后续人工复测：站长确认极验窗口显示成功后，米游社仍返回 10306。网页协议调整尚未解决实际问题，不能标记已修复。新增 `战绩安全验证拒绝` 诊断日志，仅保留上游 message 的中文说明和标点，屏蔽 ASCII 标识、数字、URL 及换行，同时记录响应是否包含 challenge、Set-Cookie 数量（不记录值）。下一次人工复测需核对该条日志以继续定位，不应只重复获取任务。

15:12 人工日志：submit 返回 10306、无 challenge、四条 Set-Cookie、message 不可用。继续修正了代码中确实存在的会话遗漏：创建响应的 Set-Cookie 经任务独立的 CookieManager 处理，合并 Cookie 保存到现有 AES-GCM 加密 Task，提交使用该 Task 的 Cookie。遵守域名、路径、Secure、删除及过期规则；不使用单例 Cookie 容器，其他账号和新任务不继承这些 Cookie。创建日志也记录 Set-Cookie 数量，错误说明增加 `msg` 字段兼容。

回归覆盖：模拟创建接口设置会话 Cookie，提交缺少该 Cookie 时测试失败；Cookie 域名、路径、过期、替换与任务隔离均有覆盖。上述日志仅证明服务端下发 Cookie，不能单凭其数量认定 10306 的唯一原因。会话修正仍待真实人工复测。

## GitHub 参考核对（2026-10-19）

15:17 人工复测仍为 10306、message 不可用、无 challenge；Cookie 修正没有解决实际问题。

- [genshin.py PR #183](https://github.com/seriaati/genshin.py/pull/183)：2024-04-20 作者在国服 `verify_mmt` 遇到完全相同的 10306 且无错误说明，人工解题后提交方案标为未完成，PR 于 2024-06-07 关闭，不能视为已解决。讨论提出沿用账号实际可信设备身份，但不是对此错误根因的证明。
- [genshin.py auth/client.py](https://github.com/seriaati/genshin.py/blob/master/genshin/client/components/auth/client.py)：当前 `verify_mmt` 有 `region_specific(Region.OVERSEAS)` 限制，不能当成已验证的国服提交实现。[routes.py](https://github.com/seriaati/genshin.py/blob/master/genshin/client/routes.py) 的国服创建路由使用 `is_high=false`；其请求头配置指向星铁，不能只抄参数套到原神。
- [UIGF SDK community.tsp](https://github.com/UIGF-org/mihoyo-api-sdk/blob/main/src/community.tsp)：验证路由位于社区服务 `/misc/api/`，不是国服战绩 `/game_record/.../card/wapi/` 的完整验证流程。
- [miyoushe-tool geetest.py](https://github.com/XYRQ-Dev/miyoushe-tool/blob/master/backend/app/services/geetest.py)：主要解析签到响应、返回风控提醒，没有找到战绩 10306 的提交修复。
- [UIGF error_code.md](https://github.com/UIGF-org/mihoyo-api-collect/blob/main/other/error_code.md)：记录了 1034 等常见错误，没有找到 10306 的正式含义。

本地核对发现：短信登录会话保存独立 `deviceId`，但 `WorkbenchSecret` 只保存 stoken/stuid/mid/roles，未保存登录设备；`GenshinDeviceService` 则按账号 HMAC 另外派生战绩设备 ID 并申请指纹。因此“战绩设备稳定”并不等于“沿用登录设备”或“已受上游信任”。后续应核对登录→工作台恢复→战绩读取的设备上下文连续性，不能断言改动这一点必然解决 10306，也不能伪造可信状态。

## 登录设备连续性修正（2026-10-19）

已确认另一处切换：创建工作台、重新查询账号原来会随机生成设备 ID，然后战绩读取再切换到账号派生 UUID。现已本地修正：

- 登录结果经 Redis 原子确认成功后，用完整 stoken/stuid/mid 的 HMAC 建立 10 分钟设备交接记录；记录仅含本站原始登录设备 ID，不保存登录凭证，也不从浏览器接收任意设备标识。
- 创建工作台时查询该交接记录，关联角色查询与 LToken 转换沿用登录设备，随后将设备 ID 一同保存进工作台 AES-GCM 密文。浏览器刷新、服务重启和短期交接记录过期均不改变已保存的设备。
- 档案读取、设备指纹申请、验证任务创建/提交及放行后的查询使用同一设备。设备指纹仍必须由上游返回，不能将登录设备连续性当作可信设备认证。
- 同时兼容原登录生成的 53 位设备标识和旧战绩 UUID；不把原登录设备重新转成 UUID。工作台祈愿授权也沿用保存的设备。
- 老会话没有原登录设备，导入的外部凭证也未提供设备来源，继续使用原有账号派生设备；不会猜测或冒用手机设备。新建会话日志输出 `工作台设备上下文已保存 source=login` 或 `source=legacy_fallback`，不输出具体 ID、指纹或凭证。

验证：`mvn -B verify` 通过，116 项测试、0 失败、0 错误。增加完整模拟上游流程，确认短信、登录、LToken、关联角色、指纹、验证创建/提交、放行读取的设备 ID 完全相同；同时覆盖旧密文恢复、会话隔离、短期交接过期、凭证 HMAC 隔离及禁止客户端指定服务端设备字段。

真实复测尚未完成。重启本地 Java 后，退出旧工作台会话并通过短信正常登录一次，确认 `source=login`，再查询档案；若仍出现安全验证，由用户本人完成一次新任务。只有实际提交返回 0 且后续档案读取成功才能判定 10306 已解决。本轮未部署服务器、未发送真实短信、未自动提交真实验证码。

## 后续人工结果与恢复条件（2026-10-19）

15:37:29 创建任务返回 0；15:37:42 提交仍返回 10306，message 不可用、无 challenge。设备连续性修正也未解决本站安全验证提交问题。

随后站长反馈：在官方米游社登录账号时，官方提示账号异常并要求人机验证；本人完成该验证后，已经可以正常看到数据。这是当前确认的数据恢复条件。此前反馈官方 App 可以查看，不能据此排除账号在重新登录或其他请求上下文中的风险校验。

当前状态分开记录：数据访问据站长反馈已恢复；本站 GT3 提交返回 10306 的兼容问题仍未解决，尚无本站 submit 返回 0 的真实证据。停止无依据地轮换协议或让用户重复提交验证码。再次遇到类似情况，应先由本人在官方米游社处理明确提示的账号异常，再回本站手动读取；这不是绕过验证，也不代表设备、Cookie 或签名调整已被证明是根因。

## 错误码归属修正（2026-10-25）

本地真实复测（工作台 + IDEA 后端，15:51 日志）显示：档案接口 `genshin_overview` / `genshin_characters` 上游返回 **5003**，不是 1034；创建验证任务返回 0 并下发真实 GT3（gt / challenge 各 32 字符、success=1、4 条 Set-Cookie），提交仍返回 10306、无 challenge、message 不可用。

对照官方战绩网页打包文件 `.local/official-record-bundle.js` 的错误码模块（模块 34）：

| 导出 | 值 | 官方网页行为 |
| --- | --- | --- |
| e | 1034 | 触发极验 `$geetest` 弹窗 |
| f | 10306 | 错误中间件里只 `resetErrorCode`，不弹验证、不提示 |
| d | 5003 | 直接提示「当前账号存在风险，暂无数据」，不弹验证 |
| b | 10103 | 提示「你的账号已被封禁，无法查看」 |
| a | 10101 | 查看他人战绩次数过多 |
| l | -10001 | 版本过低，请更新米游社 |

因此此前把 5003 归入「验证类失败」并弹出极验是错误映射：上游并未为该账号下发验证任务，`createVerification` 只是通用发码接口，`verifyVerification` 自然返回 10306。修正为：

- 5003 → `upstream_account_risk`，提示「当前账号存在风险，暂无数据」，不再申请验证；
- 10103 → `upstream_account_banned`；
- 1034 / 10035 / 10041 仍为 `upstream_verification_required`，保留极验流程；
- 工作台 `requiresVerification` 不再把 5003 当作验证触发码，并同步调整 5003 的前端用例。

同轮日志同时确认：创建响应的 4 条 Set-Cookie（acw_tc、aliyungf_tc、ltoken、ltuid）已正确合并进提交请求，之前的 Cookie 域策略修正生效；本次失败与 Cookie 无关。

### 换号对照：首次真实验证通过（2026-10-25 16:03）

同一份代码、同一台机器，仅切换米游社账号：

| 时间 | 账号 | 结果 |
| --- | --- | --- |
| 15:57 | A | overview / characters → 5003，`aigis_present=false`、响应 data 为空 |
| 16:03:46 | B | overview / characters → **1034** |
| 16:03:47 | B | createVerification → 0，真实 GT3（gt/challenge 各 32，success=1） |
| 16:03:57 | B | **verifyVerification → 0**（本项目首次真实提交成功） |
| 16:03:58–16:04:01 | B | characters / overview / equipment → 0，档案正常读取并入库 |

MySQL `ng_genshin_sync_run` 同步佐证：账号 1434 先两条 `FAILED / 1034 / upstream_verification_required`，随后三条 `SUCCESS`；账号 868 为 `FAILED / 5003`。

结论：

1. DS1 + 2.3.0、challenge_path、challenge_trace、Cookie 合并这套实现与官方网页一致，**10306 不是实现缺陷**；
2. 10306 是「对没有待验证任务的账号发起 verify」的必然结果，只会在 5003 账号上出现；
3. 5003 响应不携带任何验证任务（无 `x-rpc-aigis`、data 为空），佐证错误码归属修正正确；
4. 账号 A 的恢复路径仍是先在官方米游社完成账号异常验证，恢复后若返回 1034，则走既有极验流程即可。

`docs/TODO.md` 中「10306 待真实验证」的待办据此可以关闭；但账号 A 的风险状态属于上游账号侧，不随代码修复消失，也不做任何绕过尝试。

后端 117 项、工作台 36 项测试通过。真实恢复条件仍是先在官方米游社完成账号异常验证；若之后档案改为返回 1034，才轮到极验提交继续验证，10306 仍不作为已解决标记。

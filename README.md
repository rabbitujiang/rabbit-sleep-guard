# 兔酱睡眠守卫（Android + 自托管 MCP）

这是 [bella-and-c/sleepy-dog-lock](https://github.com/bella-and-c/sleepy-dog-lock) 的 Android / 自托管实现。它把同一份睡眠守卫状态同时交给：

- Android 手机上的无障碍服务；
- 服务器中的 Codex（固定 Bearer Token）；
- ChatGPT 官端或其他支持远程 MCP 的客户端（OAuth 2.1 + PKCE）。

Android 端会在电量、充电、网络或屏幕状态变化时上报，并每 30 分钟发送一次轻量心跳。服务器按最近一次上报时间判断手机是否在线，并通过 MCP 提供查询。

不需要 ntfy、Shizuku、Bark、Firebase 或数据库。服务器只需要 Node.js 22，运行时无第三方 npm 依赖。

## 它实际怎样工作

1. 在 ChatGPT 官端或服务器 Codex 中调用 `activate_sleep_guard`。不要求固定说“晚安”；工具说明会让模型结合“准备睡觉、要休息、放下手机”等对话自行判断。
2. 服务器保存“已开启、结束时间、拦截次数”等状态。
3. Android 无障碍服务在设备状态变化时同步，并用 30 分钟心跳确认在线；打开勾选的应用时会立即向服务器确认并显示完整守卫页。屏幕可以正常超时熄灭；再次按电源键亮屏时仍由守卫页接管，可选择申请临时解锁或回去睡觉。
4. 偷开依次进入 `first_warning`、`locked`、`refused_sleep`。当晚共有三次“申请临时解锁”机会；第三次申请写入后设置 `unlocks_revoked = true` 并隐藏按钮。
5. “申请临时解锁”写入独立的持久记录后会立即返回桌面，不会清掉偷开次数；从桌面或最近任务重新打开受限应用时，守卫依然会再次拦截。“回去睡觉”也会返回桌面，并按设置选择是否熄屏。
6. 调用 `deactivate_sleep_guard` 或到达设定起床时间后自动解除；默认结束时间固定为下一个北京时间早上 `06:30`。

凌晨 `01:00–06:30`（默认中国时区）即使忘了在聊天里说晚安，第一次打开受限应用也会自动启动守卫。手动解除后，当晚不会再次自动启动。

## 服务器部署

要求：Node.js 22、PM2、一个 HTTPS 域名。2 核 2 GB 的小服务器足够。

```bash
cd server
cp .env.example .env
npm run secrets
```

把 `npm run secrets` 打印的三个值分别填进 `.env`，同时填写真实域名：

```dotenv
PUBLIC_BASE_URL=https://sleep.example.com
ANDROID_DEVICE_TOKEN=一段随机值
CODEX_CONTROL_TOKEN=另一段随机值
OWNER_APPROVAL_CODE=第三段随机值
WAKE_HOUR=6
WAKE_MINUTE=30
```

启动并检查：

```bash
pm2 start pm2.config.cjs
pm2 save
curl https://sleep.example.com/health
```

建议同时启用 PM2 日志轮转，避免长期运行时访问日志持续占用磁盘：

```bash
pm2 install pm2-logrotate
pm2 set pm2-logrotate:max_size 10M
pm2 set pm2-logrotate:retain 7
pm2 set pm2-logrotate:compress true
```

`server/nginx.example.conf` 是 Nginx 反向代理示例。必须使用有效 HTTPS 证书，且不要把 `.env`、`server/data/` 或三个密钥提交到 Git。

## 连接服务器里的 Codex

Codex 可以直接用固定 Token，不需要每次走网页授权。在运行 Codex 的环境中设置：

```bash
export SLEEP_GUARD_CODEX_TOKEN='复制 CODEX_CONTROL_TOKEN 的值'
```

在 Codex 的 `config.toml` 中加入：

```toml
[mcp_servers.rabbit_sleep_guard]
url = "https://sleep.example.com/mcp"
bearer_token_env_var = "SLEEP_GUARD_CODEX_TOKEN"
```

重新打开 Codex 后，应能看到四个工具：

- `activate_sleep_guard`
- `deactivate_sleep_guard`
- `get_sleep_guard_status`
- `get_phone_status`

服务器上也附带不经过 MCP 的管理命令，便于脚本或排错：

```bash
SLEEP_GUARD_URL=https://sleep.example.com \
SLEEP_GUARD_CODEX_TOKEN="$SLEEP_GUARD_CODEX_TOKEN" \
node server/bin/sleep-guard-control.mjs status
```

最后一个参数可换成 `start` 或 `stop`。

## 连接 ChatGPT 官端

在 ChatGPT 的开发者模式 / MCP 连接设置中新增远程服务器，地址填写：

```text
https://sleep.example.com/mcp
```

官端会自动读取 OAuth 元数据、动态注册客户端并打开授权页。在授权页输入 `.env` 中的 `OWNER_APPROVAL_CODE`。授权成功后，官端与服务器 Codex 操作的是同一份状态。

这套 OAuth 实现包含：动态客户端注册、授权码流程、PKCE S256、短期授权码、90 天访问令牌和服务器所有者口令确认。要撤销全部官端授权，可以停止服务后删除 `server/data/auth.json` 再启动。

## Android 安装与首次设置

Android 8.0 及以上可用。项目的 `minSdk` 是 26，`targetSdk` 是 35。

1. 安装 APK，打开“兔酱睡眠守卫”。
2. 填写 HTTPS 服务器地址和 `.env` 中的 `ANDROID_DEVICE_TOKEN`，点“保存并测试连接”。
3. 点“打开无障碍设置”，启用“兔酱睡眠守卫”。这是识别前台应用和执行返回桌面的必要权限。
4. 如果希望点“回去睡觉”后真的熄屏，再点“允许设备管理锁屏”。不授予时该按钮仍会关闭守卫页并返回桌面。
5. 勾选要拦截的娱乐应用并保存。
6. 在 vivo / OriginOS 中允许后台运行，关闭该应用的电量优化限制。无障碍服务本身通常可以常驻，但 vivo 的后台管理仍建议放行。

睡眠守卫不读取输入控件或无障碍页面文字，只按勾选包名处理。新增的“让我们看屏幕”是独立、默认关闭的共享功能：在 App 明确开启后，伴侣可请求一张亮屏解锁时的屏幕图片（图片可能包含当前显示的文字），每次截图显示调用者提示；关闭开关立即停止本机截图。状态首页使用月光蓝紫的磨砂玻璃视觉，并直接显示本机电量、充电、温度、网络、屏幕、在线状态和最后上报时间。

没有变化时不会重复上传；30 分钟心跳每月最多约 1,440 次请求，状态变化上报只会额外覆盖同一份最新状态文件，不会累积历史记录。服务器在最后一次上报后的 40 分钟内判定手机在线。打开受限应用时的即时确认不依赖下一次心跳；网络临时不可用且最近一次有效状态为开启时，应用仍按开启状态执行。

## 生成 APK

### GitHub Actions（最省事）

仓库维护者先把长期保存的 release keystore 及其密码放进 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个 GitHub Actions Secrets。之后每次推送 Android 改动或手动运行 **Build installable Android APK**，都会用同一把固定密钥生成带明确版本号的 `rabbit-sleep-guard-vX.Y.Z`，可直接侧载安装并覆盖后续版本；App 首页和守卫页底部也会显示实际运行版本。

### Android Studio

用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后选择 **Build > Build APK(s)**。生成路径：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

本地构建 release APK 时，通过 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 环境变量传入同一套签名配置；keystore 和密码不进入仓库。

## 本地测试

服务器测试不需要安装依赖：

```bash
cd server
node --test
```

当前覆盖状态启动、三档偷开计数、三次临时解锁申请、第三次申请后撤销资格、重复开启不清零、解除、凌晨自动启动、过期、Android 与控制 API 共用状态、静态令牌 MCP，以及未授权客户端的 OAuth 发现入口。

另外覆盖手机状态输入校验、持久化、在线窗口、控制 API 和 `get_phone_status` MCP 查询。

## 数据文件

运行后服务器只在 `server/data/` 保存：

- `state.json`：当前守卫状态；
- `events.jsonl`：事件与拦截次数记录；
- `auth.json`：OAuth 客户端、授权码摘要与访问令牌摘要。
- `device-status.json`：手机最近一次上报的电池、网络、屏幕状态与服务器接收时间。

密钥本身只存在 `.env`。OAuth 访问令牌和授权码在磁盘中仅保存 SHA-256 摘要。

## 公开仓库与分享

仓库中的睡眠守卫服务器地址均使用 `sleep.example.com` 示例值，APK 也只带这个输入框占位提示，不包含部署者的服务器地址或令牌。朋友部署时需要自行复制 `server/.env.example`，填写自己的 HTTPS 域名，并独立生成三枚密钥。

提交或发布前建议运行：

```bash
git status --short --ignored
git grep -nE 'ANDROID_DEVICE_TOKEN|CODEX_CONTROL_TOKEN|OWNER_APPROVAL_CODE'
```

确认真实的 `.env`、`server/data/`、`artifacts/`、APK、签名文件仍显示为忽略项，搜索结果中的密钥只来自变量名、示例占位值或文档说明。GitHub Actions 只读取仓库 Secrets 中的 Android 签名材料，不读取部署密钥；APK 不包含服务器配置。


## 按需看屏幕（v0.5.0）

在手机“陪伴 · 看屏幕”中开启共享并确认说明。需要 Android 11+ 和已开启的无障碍服务；更新后如权限未刷新，可关闭再开启无障碍。调用 `get_phone_screen`，参数 `viewer` 为 `codex`、`claude` 或 `agy`，手机提示对应名称。名称由可信客户端声明，不作为鉴权凭据。

只在共享开启且亮屏解锁时每 3 秒检查请求，不连续录屏；熄屏/锁屏后停止网络轮询，亮屏恢复。单张图片缩放到长边最多 1600 像素并 JPEG 编码。保留系统对受保护页面的截图限制，失败明确返回错误，不用旧截图替代。不会开启点击、输入或其他遥控能力。

服务端按现有设备/控制令牌分权：设备仅轮询与上传，控制端请求与取回；全部图片仅存在内存、最长 60 秒，MCP 返回后即删除。请求 25 秒过期，MCP 最长等待约 20 秒；服务器重启直接取消全部请求，不回放。旧 APK 保持原睡眠守卫功能，但不能截图。

API：`POST /api/device/screen/poll`（enabled/available）、`POST /api/device/screen/result`（id 与 JPEG base64 data 或 error）；控制端 `GET /api/control/screen/status`、`POST /api/control/screen/request`（viewer）、`GET /api/control/screen/result/:id`。请勿把凭证放进 URL。反代如设上传限制，应允许 3MB JSON。

API 依据：[Android AccessibilityService.takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))。

现有会话尚未刷新 MCP 工具列表时，也可用本机脚本：`node /path/to/rabbit-sleep-guard/server/bin/phone-screen.mjs codex /path/to/workspace/screen.jpg`（另两位替换为 claude/agy）。脚本从本机环境文件取凭证，不输出凭证，只输出文件路径；随后用自己的图片读取工具读取，不能仅凭“取得文件”声称看过。此脚本保存的图片由调用方管理，服务器不留档。

## 应用使用时长（v0.6.0）

首页点「查看应用使用时长」，首次在系统设置授予「使用情况访问权限」，返回即刷新。支持今天、昨天和近七天，按时长降序显示各应用、包名、占比条及已记录的前台时长合计。页面延续现有月光蓝紫主题。

- 只在打开页面或点刷新时读取 Android `UsageStatsManager.queryEvents`；后台线程计算，不增加常驻服务、轮询或服务器上传。
- 时间范围按手机本地日历日计算；近七天包含今天。读取前一天的事件来衔接跨午夜会话，截断到所选范围；熄屏、锁屏、重启和应用切换结束当前计时。
- 这是单前台应用的估算，不含后台播放，不等于系统屏幕亮起时长；含桌面等系统界面，分屏及厂商差异可能影响结果。
- Android 只保留有限天数的事件。页面会显示本次最早可读记录，缺失事件不会补造，也不把“没有记录”解释成“完全没使用”。近七天是查询范围，不保证设备保存满七天。
- 权限未开、撤销、读取失败和无数据均有独立提示；不新增应用使用额度或自动封锁。
- 不写现有 `GuardPreferences`，保留连接、已选拦截应用、锁屏和看屏幕设置。沿用原 applicationId 与固定 release 签名覆盖安装，不需卸载。

独立算法回归：

```bash
mkdir -p /tmp/usage-tests
javac -d /tmp/usage-tests android/app/src/main/java/com/rabbit/sleepguard/UsageTimeline.java android/tests/UsageTimelineTest.java
java -cp /tmp/usage-tests UsageTimelineTest
```

依据：[Android UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[UsageEvents.Event](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)。真机验收应包含授予/撤销权限、跨日、熄屏、返回页面、旋转屏幕与覆盖升级后的原设置核对。

## 伴侣直接查询使用时长（v0.6.1，替代 0.6.0 的仅本机查看）

本版增加完整的手机→自托管服务→伴侣查询链路，无需打开统计页或截屏。

- 手机安装 v0.6.1、保存有效服务器连接并授予使用情况访问权限后，默认同步应用名称、包名、前台时长与统计时间范围；不传页面内容。可在统计页关闭「同步使用时长给伴侣」。
- 打开守卫/统计页、从权限设置返回、点「立即同步给伴侣」时尝试同步；无障碍服务运行期间约每 5 分钟自动同步。系统休眠、断网、后台服务被停止时会延后，查询必须以 `captured_at` 为准，不能当作实时值。
- 服务端只覆盖保存一份 `server/data/app-usage.json`，权限 0600，不累积历史。关闭同步或撤销权限后，下次成功上报会清空旧统计；离线时不能保证立即清空。
- 设备接口 `POST /api/device/usage` 使用原设备令牌，只允许设备写；控制接口 `GET /api/control/usage?range=today|yesterday|week` 使用原控制令牌，只允许控制端读。
- MCP 工具 `get_phone_app_usage` 接受 `range`（默认 today），静态控制令牌和现有 OAuth 均可查询。三位伴侣共用同一能力，不改动锁定/解除权限与守卫状态。
- 返回包含 `captured_at`、`received_at`、`age_seconds`、`stale`、`date_changed`、`timezone`、实际 `start/end`、`first_record_at`、`total_ms` 和应用排行。超过 10 分钟或手机本地日期已改变则标记过期；缺数据、权限未开、关闭共享、读取失败分别返回明确状态。
- 系统记录不足七天仍可能导致周统计不完整，前台估算口径沿用 v0.6.0；不以缺失记录推断没有使用。

安装仍沿用原固定签名与 applicationId；无需卸载或重填已有配置。覆盖升级后仍需在手机上验证真实统计与后台同步。

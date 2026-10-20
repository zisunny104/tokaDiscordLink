# tokaDiscordLink

連接伺服器與 Discord 的橋樑：機器人上線、玩家綁定自己的 Discord 帳號、同步暱稱，並讓其他插件收發 Discord 頻道的訊息與指令。

## 主要功能

- **機器人連線**：填入令牌與頻道後，機器人會連上 Discord，並在開服與關服時於聊天頻道發出提示。
- **帳號綁定**：玩家與 Discord 帳號一對一綁定，用一次性代碼確認，代碼預設 10 分鐘有效，連續輸入錯誤會暫時鎖定。
- **暱稱同步**：綁定後，Discord 暱稱會改成遊戲名稱，之後定期更新。
- **供其他插件使用**：聊天頻道的訊息與指令會轉成事件；其他插件也能透過 API 把訊息送到 Discord。本插件本身不轉送遊戲聊天。

## 玩家指令

| 指令 | 說明 |
| --- | --- |
| `/discord` 或 `/discord info` | 查看自己的綁定 |
| `/discord link` | 產生綁定代碼，到 Discord 輸入 `/link code:代碼` 完成綁定 |
| `/discord link <代碼>` | 例：`/discord link 123456`，輸入 Discord 的 `/link` 給你的代碼 |
| `/discord unlink` | 解除自己的綁定 |
| `/discord help` | 顯示說明 |

在 Discord 也可以反過來：輸入 `/link` 取得代碼，再到遊戲輸入 `/discord link <代碼>`；`/unlink` 解除綁定。
（圖：Discord 中輸入 `/link` 後機器人回覆代碼的畫面）

## 管理指令

| 指令 | 用法 | 說明 | 權限 |
| --- | --- | --- | --- |
| `/discord status` | `/discord status` | 查看機器人連線、頻道與綁定狀態 | `tokaDiscordLink.admin` |
| `/discord link` | `/discord link <玩家> <Discord使用者ID>` | 替玩家直接綁定（玩家需進過伺服器） | `tokaDiscordLink.admin` |
| `/discord unlink` | `/discord unlink <玩家>` | 解除玩家的綁定 | `tokaDiscordLink.admin` |
| `/discord info` | `/discord info <玩家>` | 查看玩家綁定的 Discord 使用者 ID | `tokaDiscordLink.admin` |

`tokaDiscordLink.use`（綁定自己的帳號）預設所有人，`tokaDiscordLink.admin` 預設 OP；後台也可執行管理指令。

初次設定：到 Discord Developer Portal 建立機器人並開啟 Message Content Intent，取得令牌，填入 `token` 與 `msg-channel-id` 後重新啟動。令牌等同密碼，請勿公開。暱稱同步需要機器人有「管理暱稱」權限，且角色高於對方；伺服器擁有者的暱稱無法更改。

## 設定（`config.yml`，變更後需重新啟動）

| key | 預設 | 說明 |
| --- | --- | --- |
| `token` | 空白 | 機器人令牌，留空代表停用串聯 |
| `msg-channel-id` | 空白 | 聊天頻道 ID，訊息與指令都在此，留空代表停用 |
| `cmd-channel-id` | 空白 | 指令結果頻道 ID，留空不使用 |
| `status` | 空白 | 機器人的「正在玩…」文字 |
| `discord-command-prefix` | `/` | 以此開頭的訊息視為指令，留空則不使用 Discord 指令 |
| `unknown-command-msg` | 見檔案 | 無人處理指令時的回覆（`%name` 發話者、`%msg` 指令） |
| `bot-start-msg`、`bot-stop-msg` | 連接成功／斷開連接 | 開關服提示，留空則不送 |
| `link.code-ttl-seconds` | `600` | 綁定代碼有效秒數 |
| `link.max-attempts`、`link.lockout-seconds` | `5`、`600` | 錯誤次數上限與鎖定秒數 |
| `nickname.mode` | `set` | `off` 不管暱稱；`set` 綁定時與定期同步；`enforce` 另在對方改回時立刻改回來 |
| `nickname.format` | `{name}` | 暱稱格式，`{name}` 為遊戲名稱，最長 32 字元 |
| `roles` | 空 | 權限群組對應 Discord 身分組 ID，目前預設不動身分組 |
| `sync-interval-seconds` | `300` | 暱稱同步間隔，至少 60 |
| `sync-interval-idle-seconds` | `1800` | 沒有可同步內容時的間隔，不小於上一項 |

## 串接

`dev.toka.discord.api.DiscordLink` 的靜態方法；機器人未就緒時呼叫會被忽略，不會出錯。

| 方法／事件 | 參數 | 用途 |
| --- | --- | --- |
| `isReady()` | 無 | 機器人是否已連線 |
| `sendMessage` | 文字［, 頻道 `msg`／`cmd`］ | 送出文字訊息 |
| `sendEmbedMessage` | 文字［, 顏色 `#RRGGBB`, 頻道］ | 送出嵌入訊息 |
| `deleteMessage` | 訊息 ID［, 頻道］ | 刪除訊息 |
| `setIdentityResolver` | `IdentityResolver` | 提供 Discord 帳號與玩家的對照 |
| `BotMessageEvent` | `getMsg()`、`getSenderId()`、`getSenderName()`、`getLinkedPlayer()` | 聊天頻道的一般訊息，可取消 |
| `BotCommandEvent` | `getCommand()`、`getSenderId()`、`getSenderName()`、`getLinkedPlayer()` | 帶前綴的指令；處理後呼叫 `setHandled(true)`，否則回覆未知指令 |

## 疑難排解

- **機器人沒有上線？** 確認 `token` 與 `msg-channel-id` 已填，且已開啟 Message Content Intent。
- **暱稱沒有變？** 檢查機器人是否有「管理暱稱」權限，且角色比對方高。
- **綁定代碼無效？** 代碼只能用一次且會過期，連續錯誤會暫時鎖定。

目前版本 0.3.1

### Plan 選用整合（0.3.2）

預設自動接入 Plan，提供 `bound`（玩家是否綁定 Discord）的布林狀態，不公開 Discord ID、名稱、群組、權杖或驗證碼。資料來自成功儲存後發布的 immutable 綁定存在快照，Provider 不取得儲存鎖、不讀磁碟/玩家/Discord 網路。機器人未啟動仍可展示已有的本機綁定。

使用官方 Plan API5.7-R0.2 provided，核對 Plan5.8 build3638 Builder API。Plan 缺少/停用/能力不足自動略過；支援啟用停用及插件卸載。Plan 模式與欄位可用下列管理指令即時套用，欄位走 tokaI18n `plan`。Plan 網頁權限與歷史資料由 Plan 管理；停用整合不會刪除已保存的狀態。


### Plan 模式與管理指令

預設 `plan.mode: auto`、`plan.fields: all`。`auto` 偵測到可用 Plan 就啟用；`enable` 未就緒時提示；`disable` 一定不發布。all 僅包含本插件核准的摘要欄位，沒有憑證、Discord ID 或私人座標。

管理員可用 `/discord plan status`、`/discord plan mode <auto|enable|disable>`、`/discord plan fields <all|none|欄位...>`；變更保存設定且即時套用，保留其他設定及註解。舊 `plan.enabled`／逐項布林仍相容，明確設定的新 mode／fields 優先。

API（主執行緒）：`planStatusJson()`、`configurePlan(String mode, String[] fields)`；null 表示該項沿用。

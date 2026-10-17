# tokaDiscordLink

**tokaDiscordLink 0.2.1** 是 Nukkit 伺服器與 Discord 之間的串聯：機器人連線、聊天與指令收發、遊戲與 Discord 帳號一對一綁定，以及綁定後的暱稱同步。不依賴其他遊戲插件；其他插件透過 `DiscordLink` API 與事件使用。本插件不收集任何使用統計。

## 安裝

- 需要 Java 21 與一個 Discord 機器人；在 Developer Portal 的 Bot 頁面開啟 **Message Content Intent**。
- 機器人需能看到並發言於設定的頻道；同步暱稱另需「管理暱稱」權限，且角色高於對方。
- 將 `tokaDiscordLink-0.2.1.jar` 放入 `plugins/`，啟動一次後填寫 `config.yml` 的 `token`、`msg-channel-id`，再重啟。未填時插件停用 Discord，不影響伺服器。
- 令牌等同密碼，請勿貼到聊天室或提交到 Git。
- 不可與舊的 `one` 插件同時執行。
- 使用共用套件 tokaStorage 與 tokaI18n，皆已內嵌於 JAR。

## 指令與權限

遊戲內：`/discord [link [代碼]｜unlink｜info｜status｜help]`；Discord 內：`/link [code]`、`/unlink`。代碼一次性、預設 10 分鐘有效，連續輸入錯誤會暫時鎖定。

| 權限 | 說明 | 預設 |
| --- | --- | --- |
| `tokaDiscordLink.use` | 綁定、解除與查看自己的綁定 | 所有人 |
| `tokaDiscordLink.admin` | 查看狀態，替別人綁定、解除與查看 | OP |

## 設定（`config.yml`）

| key | 預設 | 說明 |
| --- | --- | --- |
| `language` | `zh-TW` | 語言 |
| `token` | `""` | 機器人令牌，留空即停用 |
| `msg-channel-id` / `cmd-channel-id` | `""` | 聊天頻道／指令結果頻道 ID |
| `status` | `""` | 機器人狀態文字 |
| `discord-command-prefix` | `/` | Discord 指令前綴，留空代表不用 |
| `unknown-command-msg` | 見檔案 | 無人處理指令時的回覆 |
| `bot-start-msg` / `bot-start-color` | 連接成功／`#00EB00` | 開服提示 |
| `bot-stop-msg` / `bot-stop-color` | 斷開連接／`#FFA500` | 關服提示 |
| `storage.backend` | `sqlite` | `sqlite`、`json`、`yaml` |
| `link.code-ttl-seconds` | `600` | 綁定代碼有效秒數 |
| `link.max-attempts` / `lockout-seconds` | `5` / `600` | 可嘗試次數與鎖定秒數 |
| `nickname.mode` | `set` | `off`、`set`、`enforce` |
| `nickname.format` | `{name}` | 暱稱格式，最長 32 字元 |
| `roles` | `{}` | 權限群組對應 Discord 身分組 ID，空代表不動身分組 |
| `sync-interval-seconds` | `300` | 暱稱與身分組同步間隔，至少 60 |

綁定資料放在 `plugins/tokaDiscordLink/storage/<格式>/`。

## API

類別 `dev.toka.discord.api.DiscordLink`（機器人未就緒時安全地不做事）：`isReady()`、`sendMessage(文字[, 頻道])`、`sendEmbedMessage(文字, 顏色)`、`deleteMessage(id)`、`setIdentityResolver(...)`。事件（主執行緒）：`BotMessageEvent`、`BotCommandEvent`（處理後呼叫 `setHandled(true)`）。

## 版本紀錄

- 0.2.1：文件整理。
- 0.2.0：所有文字移入 tokaI18n `zh-TW`，新增 `/discord help`。
- 0.1.6：維護版本。
- 0.1.5：綁定資料移至 `storage/sqlite`（舊 `links/` 複製並比對），記錄綁定數。
- 0.1.4：讓 JDA 透過插件 class loader 找到日誌橋接。

授權：MIT。

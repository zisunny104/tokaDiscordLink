# tokaDiscordLink

整體拆分現況只維護於 [拆分主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)；本 README 維護本專案的設定、API 與使用限制。

遊戲與 Discord 之間的串聯，給 Nukkit 伺服器使用。負責機器人連線、聊天訊息與指令的收發、遊戲與 Discord 帳號的一對一綁定，以及綁定後的暱稱同步。它**不認識 zero**，也不依賴其他遊戲插件，其他插件透過 `DiscordLink` API 與事件使用它。

它取代舊的 `one` 插件的「機器人」部分。目前版本 0.2.1（所有文字抽到多語系檔，新增 `/discord help`）。

## 功能
- 機器人連線、聊天頻道與指令結果頻道的訊息收發、開關服提示。
- Discord 訊息與指令變成伺服器事件（`BotMessageEvent`、`BotCommandEvent`），讓其他插件處理。
- **帳號綁定**：一個 Discord 帳號對一個遊戲帳號，雙向、用一次性代碼驗證。
- **暱稱同步**：綁定後把 Discord 暱稱設成遊戲名稱（可關閉或強制）。
- **身分組同步**：遊戲權限群組對應到 Discord 身分組（單向，預設不設定）。
- 綁定資料用 [tokaStorage](https://github.com/zisunny104/tokaStorage) 儲存（SQLite、JSON 或 YAML）。
- 不收集、也不傳送任何使用統計。

## 需要什麼
- Nukkit 核心與 Java 21。
- 一個 Discord 機器人。在 Developer Portal 的 Bot 頁面開啟 **Message Content Intent**，沒有開機器人會連不上，日誌會提醒。插件只要求 `GUILD_MESSAGES` 與 `MESSAGE_CONTENT` 兩個 intent。
- 機器人要在你的 Discord 伺服器裡，而且看得到、能發言於你設定的頻道。要同步暱稱需要「管理暱稱」權限，且機器人的角色要比對方的最高角色高；伺服器擁有者的暱稱改不了。

## 安裝
1. 把 `tokaDiscordLink-0.2.1.jar` 放進伺服器的 `plugins/`。
2. 第一次啟動後會產生 `plugins/tokaDiscordLink/config.yml`，填入 `token`、`msg-channel-id`。
3. 重開伺服器。`token` 或 `msg-channel-id` 沒填時，插件會停用 Discord，並在日誌說明原因，不影響伺服器。

令牌（token）等同密碼，請不要貼到聊天室、截圖或提交到 Git。

## 設定檔
範本在 [`src/main/resources/config.yml`](src/main/resources/config.yml)，每個欄位都有說明。重點：

| 設定 | 說明 |
|---|---|
| `token`、`msg-channel-id`、`cmd-channel-id` | 機器人令牌與頻道 ID |
| `discord-command-prefix` | Discord 指令前綴，留空代表不用 |
| `storage.backend` | 綁定資料的儲存方式：`sqlite`（預設）、`json`、`yaml` |
| （資料位置） | 綁定資料在 `plugins/tokaDiscordLink/storage/sqlite/`。0.1.5 起，舊的 `links/` 資料夾會在啟動時複製過去並逐位元組比對，舊資料夾保留不刪；啟動日誌會寫「載入綁定共 N 個」 |
| `link.code-ttl-seconds`、`link.max-attempts`、`link.lockout-seconds` | 代碼有效時間（預設 600 秒）、可嘗試次數（5）、鎖定時間（600 秒） |
| `nickname.mode` | `off`（不管）、`set`（綁定後設定，預設）、`enforce`（被改掉後下次發言改回） |
| `nickname.format` | 暱稱格式，`{name}` 是遊戲名稱，最長 32 字元 |
| `roles` | 遊戲權限群組對應 Discord 身分組 ID，預設空，空的時候不會動任何身分組 |
| `sync-interval-seconds` | 暱稱與身分組的定期同步間隔，至少 60 |

## 多語系
所有玩家看得到的文字、後台日誌與 Discord 回覆都放在共用套件 [tokaI18n](https://github.com/zisunny104/tokaI18n)（內嵌進 jar）的語系檔 `lang/zh-TW.yml`，目前只有繁體中文。語言由 `config.yml` 的 `language` 決定；想改個別文字，把同名檔放進 `plugins/tokaDiscordLink/lang/`，只寫要覆蓋的 key。開關服提示、未知指令回覆等管理員自訂的文字仍在 `config.yml`。共用的多語系原則見[拆分主文件](https://github.com/zisunny104/zero/blob/master/docs/ARCHITECTURE.md)。

## 指令與權限
遊戲內 `/discord [link [代碼] | unlink | info | status | help]`，Discord 內 `/link [code]` 與 `/unlink`（回覆只有你看得到）。

| 權限 | 說明 | 預設 |
|---|---|---|
| `tokaDiscordLink.use` | 綁定、解除與查看自己的綁定 | 所有人 |
| `tokaDiscordLink.admin` | 查看狀態，替別人綁定、解除與查看 | OP |

## 帳號綁定
1. 從任一邊開始：遊戲裡輸入 `/discord link`，或 Discord 裡輸入 `/link`，會得到一次性代碼。
2. 在另一邊輸入代碼：遊戲裡 `/discord link 代碼`，或 Discord 裡 `/link 代碼`。
3. 代碼只能用一次、預設 10 分鐘內有效；代碼以雜湊保存，連續輸入錯誤會暫時鎖住。
4. 一個 Discord 帳號只能綁一個遊戲帳號，反過來也一樣。要換的話先解除（`/unlink` 或 `/discord unlink`），再重新驗證。
5. 綁定只認 Discord 使用者 ID，不看使用者名稱，改名不影響。

## 從 one 換過來
1. 停服，從 `plugins/` 移走 `one`。**不能同時執行**：本插件偵測到 `one` 還在時，會拒絕啟動並在日誌說明，因為同一個機器人會重複回應每一則訊息。
2. 欄位名稱與 `one` 相同，把 `token`、頻道 ID、`status`、前綴、開關服提示與顏色複製到本插件的 `config.yml`。多出來的有 `unknown-command-msg`、帳號綁定相關設定與 `config-version`。

## 給其他插件用的 API
類別 `dev.toka.discord.api.DiscordLink`，機器人沒準備好時所有方法都安全地什麼也不做：

```java
if (DiscordLink.isReady()) {
    DiscordLink.sendMessage("§a玩家加入了遊戲");                 // 聊天頻道，去掉顏色並加時間
    DiscordLink.sendMessage("結果", DiscordLink.CMD_CHANNEL);     // 指令結果頻道
    DiscordLink.sendEmbedMessage("伺服器重啟中", "#FFA500");       // 嵌入訊息
    DiscordLink.deleteMessage(messageId);
}
```

事件（在伺服器主執行緒呼叫）：
- `BotMessageEvent`：聊天頻道裡的一般訊息。`getSenderId()` 是 Discord 使用者 ID，`getSenderName()` 是顯示名稱。
- `BotCommandEvent`：以前綴開頭的指令。處理它的插件要呼叫 `setHandled(true)`；沒人處理時，本插件會回覆「不明的指令」。

`IdentityResolver` 介面回答「這個 Discord 使用者是哪位玩家」和反過來。本插件啟動時會把自己的綁定資料註冊成預設實作；其他插件可以用 `DiscordLink.setIdentityResolver(...)` 取代它（同時只會有一個）。

## 建置
需要 JDK 21、Maven，以及：
- **tokaStorage 0.1.3**：先用 [tokaStorage](https://github.com/zisunny104/tokaStorage) 的 `build.ps1` 安裝到你用的 Maven 倉庫。
- **Nukkit 核心**：任何你要執行的 Nukkit jar，以 `cn.nukkit:Nukkit:1.0-SNAPSHOT` 安裝到同一個 Maven 倉庫。`build.ps1` 的 `-NukkitJar` 參數會幫你做這件事。

```powershell
.\build.ps1 -Maven mvn -JdkHome "C:\path\to\jdk-21" -NukkitJar "C:\path\to\nukkit.jar" -Repository "C:\path\to\m2"
```

會跑測試、打包，並把 jar 與設定範本放進 `output\`。第一次需要網路下載 JDA；之後可加 `-Offline`。

## 還沒做的
- 身分組同步與遊戲的權限群組串接：等新的權限組完成後再接，目前 `roles` 預設是空的。
- zero 改用本插件的事件與 API，並接手 `list／c／ser` 指令。
- 實際連上 Discord 的長時間測試：目前做了編譯、單元測試，以及用假令牌確認 JDA 能啟動並拒絕無效令牌。

## 授權
本專案以 MIT 授權，見 [LICENSE](LICENSE)。打包時包含 JDA 與其他相依套件，各依其授權，清單見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。



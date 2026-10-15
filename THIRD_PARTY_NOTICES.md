# 第三方軟體

本專案的原始碼以 MIT 授權。打包出來的 jar 包含下列套件（部分已重新命名套件路徑以避免與其他插件衝突），各依其授權。授權名稱取自各套件 POM 的 `licenses` 欄位；完整條款請見各套件的專案頁。

| 套件 | 版本 | 授權 |
|---|---|---|
| [JDA](https://github.com/discord-jda/JDA) | 5.6.1 | Apache License 2.0 |
| [OkHttp](https://github.com/square/okhttp) | 4.12.0 | Apache License 2.0 |
| [Okio](https://github.com/square/okio)（含 okio-jvm） | 3.6.0 | Apache License 2.0 |
| [Kotlin 標準函式庫](https://github.com/JetBrains/kotlin)（stdlib、common、jdk7、jdk8） | 1.8.21／1.9.10 | Apache License 2.0 |
| [Jackson](https://github.com/FasterXML/jackson)（core、databind、annotations） | 2.18.3 | Apache License 2.0 |
| [nv-websocket-client](https://github.com/TakahikoKawasaki/nv-websocket-client) | 2.14 | Apache License 2.0 |
| [Apache Commons Collections](https://commons.apache.org/proper/commons-collections/) | 4.4 | Apache License 2.0 |
| [Google Tink](https://github.com/tink-crypto/tink-java) | 1.17.0 | Apache License 2.0 |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) | 4.28.2 | BSD 3-Clause |
| [Gson](https://github.com/google/gson) | 2.10.1 | Apache License 2.0 |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) | 2.4 | Apache License 2.0 |
| [SLF4J API](https://www.slf4j.org/) | 2.0.17 | MIT |
| [JetBrains Annotations](https://github.com/JetBrains/java-annotations) | 13.0 | Apache License 2.0 |
| [Error Prone Annotations](https://github.com/google/error-prone) | 2.22.0 | Apache License 2.0 |
| [JSR 305](https://code.google.com/archive/p/jsr-305/) | 3.0.2 | Apache License 2.0 |
| [tokaStorage](https://github.com/zisunny104/tokaStorage) | 0.1.3 | MIT |
| [Trove4j](https://bitbucket.org/trove4j/trove)（`net.sf.trove4j:core`，JDA 相依） | 3.1.0 | **GNU LGPL 2.1** |

## 關於 LGPL 套件（Trove4j）
JDA 相依 Trove4j，它以 LGPL 2.1 授權。本專案的打包把它與其他相依套件一起放進 jar 並重新命名了套件路徑，使用者因此不容易單獨替換這個函式庫。**本倉庫只發布原始碼，不附帶打包好的 jar**；如果你自行散布打包出來的 jar，請依 LGPL 2.1 的要求提供取得 Trove4j 原始碼的方式，並讓使用者能用修改過的版本重新建置，或改用不重新命名套件路徑的打包方式。這份說明不是法律意見。

## 與其他專案的關係
- 本專案取代舊的 `one` 插件的機器人部分，保留相同的設定欄位名稱。
- 沒有收集或傳送使用統計。

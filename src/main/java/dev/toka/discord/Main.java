package dev.toka.discord;

import cn.nukkit.IPlayer;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import dev.toka.discord.api.DiscordLink;
import dev.toka.discord.chat.DiscordService;
import dev.toka.discord.link.LinkService;
import dev.toka.discord.link.MemberSync;
import dev.toka.discord.link.PendingCodes;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

public final class Main extends PluginBase implements Listener {

    public static final String PERMISSION_USE = "tokaDiscordLink.use";
    public static final String PERMISSION_ADMIN = "tokaDiscordLink.admin";

    private LinkService links;
    private MemberSync sync;
    private DiscordService service;
    private String disabledReason;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        try {
            Backend backend = Backend.valueOf(this.getConfig().getString("storage.backend", "sqlite").toUpperCase(Locale.ROOT));
            Path directory = this.getDataFolder().toPath().resolve("links").toAbsolutePath().normalize();
            DocumentStore store = DocumentStore.open(new StoreConfig(backend, directory, 1));
            PendingCodes codes = new PendingCodes(System::currentTimeMillis,
                    this.getConfig().getInt("link.code-ttl-seconds", 600),
                    this.getConfig().getInt("link.max-attempts", 5),
                    this.getConfig().getInt("link.lockout-seconds", 600));
            this.links = new LinkService(store, System::currentTimeMillis, codes);
        } catch (Exception e) {
            this.getLogger().error("綁定資料無法開啟，Discord 串聯停用，原資料沒有被更動", e);
            this.disabledReason = "綁定資料無法開啟：" + e.getMessage();
            return;
        }
        DiscordLink.setIdentityResolver(this.links);
        DiscordLink.setLinkImporter((player, discordUserId) -> {
            if (!discordUserId.matches("\\d{15,25}")) {
                return DiscordLink.ImportResult.REJECTED;
            }
            switch (this.links.link(player, discordUserId)) {
                case LINKED:
                    return DiscordLink.ImportResult.IMPORTED;
                case ALREADY_LINKED:
                    return DiscordLink.ImportResult.ALREADY_LINKED;
                case STORAGE_FAILED:
                    return DiscordLink.ImportResult.REJECTED;
                default:
                    return DiscordLink.ImportResult.CONFLICT;
            }
        });
        this.sync = new MemberSync(this, this.links, this.getConfig());
        this.getServer().getPluginManager().registerEvents(this, this);

        Plugin one = this.getServer().getPluginManager().getPlugin("one");
        if (one != null && one.isEnabled()) {
            // Two bots with the same token would answer every message twice
            this.disabledReason = "舊的 one 插件還在執行，請先移除 one，再啟用本插件";
        } else if (this.getConfig().getString("token", "").isBlank()) {
            this.disabledReason = "config.yml 的 token 還沒有設定";
        } else if (this.getConfig().getString("msg-channel-id", "").isBlank()) {
            this.disabledReason = "config.yml 的 msg-channel-id 還沒有設定";
        }

        if (this.disabledReason != null) {
            this.getLogger().warning("Discord 串聯停用：" + this.disabledReason);
            return;
        }

        this.service = new DiscordService(this, this.links, this.sync);
        DiscordLink.register(this.service);
        this.service.start(this.getConfig());

        int interval = Math.max(60, this.getConfig().getInt("sync-interval-seconds", 300)) * 20;
        this.getServer().getScheduler().scheduleDelayedRepeatingTask(this, () -> {
            if (this.service.isReady()) {
                this.sync.syncAll();
            }
        }, interval, interval);
    }

    @Override
    public void onDisable() {
        if (this.service != null) {
            DiscordLink.unregister(this.service);
            this.service.stop(this.getConfig());
        }
        DiscordLink.setIdentityResolver(null);
        DiscordLink.setLinkImporter(null);
        if (this.links != null) {
            try {
                this.links.close();
            } catch (Exception e) {
                this.getLogger().warning("綁定資料關閉時發生錯誤：" + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.links == null || this.service == null || !this.service.isReady()) {
            return;
        }
        UUID player = event.getPlayer().getUniqueId();
        this.links.discordUserOf(player).ifPresent(discord -> this.sync.sync(player, discord));
    }

    // ------------------------------------------------------------------ the /discord command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(PERMISSION_ADMIN) || !(sender instanceof Player);
        if (this.links == null && !sub.equals("status")) {
            sender.sendMessage("綁定功能目前不能使用：" + this.disabledReason);
            return true;
        }

        switch (sub) {
            case "status":
                if (!admin) {
                    sender.sendMessage("您沒有權限查看狀態。");
                    return true;
                }
                this.status(sender);
                return true;
            case "link":
                this.link(sender, args, admin);
                return true;
            case "unlink":
                this.unlink(sender, args, admin);
                return true;
            case "info":
                this.info(sender, args, admin);
                return true;
            default:
                sender.sendMessage("用法：/discord link [代碼] | unlink | info"
                        + (admin ? " | status | link <玩家> <Discord使用者ID> | unlink <玩家> | info <玩家>" : ""));
                return true;
        }
    }

    private void status(CommandSender sender) {
        String state;
        if (this.service == null) {
            state = "停用（" + this.disabledReason + "）";
        } else if (this.service.isReady()) {
            state = "就緒";
        } else if (this.service.getFailure() != null) {
            state = "未就緒（" + this.service.getFailure() + "）";
        } else {
            state = "連線中";
        }
        sender.sendMessage("Discord：" + state);
        if (this.service != null && this.service.isReady()) {
            sender.sendMessage("聊天頻道：" + this.service.describeChannel(DiscordLink.MSG_CHANNEL));
            sender.sendMessage("指令結果頻道：" + this.service.describeChannel(DiscordLink.CMD_CHANNEL));
        }
        if (this.links != null) {
            sender.sendMessage("已綁定帳號：" + this.links.count() + " 個");
            sender.sendMessage("暱稱管理：" + this.sync.nicknameMode().name().toLowerCase(Locale.ROOT)
                    + "；身分組對照：" + this.sync.roleMappings() + " 項"
                    + (this.sync.roleMappings() == 0 ? "（空的，不會更動任何身分組）" : ""));
        }
        sender.sendMessage("權限串接：等待中（目前管理操作只認 OP；新的權限組確定後再接）");
    }

    private void link(CommandSender sender, String[] args, boolean admin) {
        if (args.length == 3) {
            // /discord link <player> <Discord user id>: only for operators
            if (!admin) {
                sender.sendMessage("替別人綁定需要 OP 權限。");
                return;
            }
            UUID target = this.resolve(sender, args[1]);
            if (target == null) {
                return;
            }
            String discordId = args[2].trim();
            if (!discordId.matches("\\d{15,25}")) {
                sender.sendMessage("Discord 使用者 ID 是一串數字，請在 Discord 開啟開發者模式後，對使用者按右鍵複製 ID。");
                return;
            }
            LinkService.Result result = this.links.link(target, discordId);
            sender.sendMessage(this.describe(result, target));
            if (result == LinkService.Result.LINKED) {
                this.getLogger().info("[綁定] " + sender.getName() + " 把 " + target + " 綁到 Discord " + discordId);
                this.sync.sync(target, discordId);
            }
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("用法：/discord link <玩家> <Discord使用者ID>");
            return;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (args.length == 1) {
            if (this.links.discordUserOf(uuid).isPresent()) {
                sender.sendMessage("您已經綁定 Discord 帳號了。要換的話，先輸入 /discord unlink。");
                return;
            }
            String code = this.links.codes().issue(PendingCodes.Side.GAME, uuid.toString());
            int minutes = Math.max(1, this.getConfig().getInt("link.code-ttl-seconds", 600) / 60);
            sender.sendMessage("您的綁定代碼：" + code + "（" + minutes + " 分鐘內有效，只能用一次）");
            sender.sendMessage("請到 Discord 輸入：/link code:" + code + "　不要把代碼給任何人。");
            if (this.service == null || !this.service.isReady()) {
                sender.sendMessage("注意：Discord 機器人目前沒有就緒，請稍後再到 Discord 輸入。");
            }
            return;
        }
        if (args.length == 2) {
            LinkService.Confirmation result = this.links.confirm(PendingCodes.Side.GAME, uuid.toString(), args[1]);
            switch (result.outcome()) {
                case LOCKED:
                    sender.sendMessage("輸入錯誤太多次，請稍後再試。");
                    return;
                case WRONG_OR_EXPIRED:
                    sender.sendMessage("代碼不正確或已經過期。請在 Discord 輸入 /link 取得新的代碼。");
                    return;
                default:
                    sender.sendMessage(this.describe(result.result(), result.match().player()));
                    if (result.result() == LinkService.Result.LINKED) {
                        this.sync.sync(result.match().player(), result.match().discordUser());
                    }
                    return;
            }
        }
        sender.sendMessage("用法：/discord link（產生代碼）或 /discord link <Discord 給您的代碼>");
    }

    private void unlink(CommandSender sender, String[] args, boolean admin) {
        UUID target;
        if (args.length >= 2) {
            if (!admin) {
                sender.sendMessage("替別人解除綁定需要 OP 權限。");
                return;
            }
            target = this.resolve(sender, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player) {
            target = ((Player) sender).getUniqueId();
        } else {
            sender.sendMessage("用法：/discord unlink <玩家>");
            return;
        }
        if (this.links.unlinkPlayer(target)) {
            sender.sendMessage("已解除綁定。要再綁定需要重新驗證。");
            if (args.length >= 2) {
                this.getLogger().info("[綁定] " + sender.getName() + " 解除了 " + target + " 的綁定");
            }
        } else {
            sender.sendMessage("沒有可以解除的綁定。");
        }
    }

    private void info(CommandSender sender, String[] args, boolean admin) {
        UUID target;
        if (args.length >= 2) {
            if (!admin) {
                sender.sendMessage("查看別人的綁定需要 OP 權限。");
                return;
            }
            target = this.resolve(sender, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player) {
            target = ((Player) sender).getUniqueId();
        } else {
            sender.sendMessage("用法：/discord info <玩家>");
            return;
        }
        String discord = this.links.discordUserOf(target).orElse(null);
        sender.sendMessage(discord == null ? "尚未綁定 Discord。輸入 /discord link 開始。" : "已綁定 Discord 使用者 ID：" + discord);
    }

    /** A player name to the UUID, with the reason when there is none. */
    private UUID resolve(CommandSender sender, String name) {
        Player online = Server.getInstance().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        IPlayer offline = Server.getInstance().getOfflinePlayer(name);
        if (offline == null || offline.getUniqueId() == null || !offline.hasPlayedBefore()) {
            sender.sendMessage("找不到玩家「" + name + "」，對方需要進過伺服器。");
            return null;
        }
        return offline.getUniqueId();
    }

    private String describe(LinkService.Result result, UUID player) {
        String name = Server.getInstance().getOfflinePlayer(player).getName();
        switch (result) {
            case LINKED:
                return "綁定完成：" + (name == null ? player : name);
            case ALREADY_LINKED:
                return "這兩個帳號早就綁定在一起了。";
            case PLAYER_HAS_OTHER:
                return "這個遊戲帳號已經綁定了另一個 Discord 帳號。要換的話，先 /discord unlink。";
            case DISCORD_HAS_OTHER:
                return "這個 Discord 帳號已經綁定了另一個遊戲帳號。要換的話，先到 Discord 輸入 /unlink。";
            default:
                return "綁定沒有儲存成功，請稍後再試，並通知管理員。";
        }
    }
}

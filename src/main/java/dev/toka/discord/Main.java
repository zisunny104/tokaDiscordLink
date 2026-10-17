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
import dev.toka.i18n.Messages;
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
    private Messages messages;

    /** The texts of the plugin, in the language of {@code language} in config.yml. */
    public Messages messages() {
        return this.messages;
    }

    /**
     * The links are kept in plugins/tokaDiscordLink/storage/sqlite/ like the other plugins. The folder links/ of an
     * earlier version is copied there once and compared byte by byte; the old folder stays where it is.
     */
    private Path storageDirectory(Backend backend) {
        Path folder = this.getDataFolder().toPath().toAbsolutePath().normalize();
        Path legacy = folder.resolve("links");
        Path target = folder.resolve("storage").resolve(backend.name().toLowerCase(Locale.ROOT));
        if (java.nio.file.Files.exists(target) || backend != Backend.SQLITE
                || !java.nio.file.Files.isRegularFile(legacy.resolve("documents.sqlite"))) {
            return java.nio.file.Files.exists(target) || !java.nio.file.Files.isDirectory(legacy) ? target : legacy;
        }
        Path staging = folder.resolve("storage").resolve(".copy-" + java.util.UUID.randomUUID());
        try {
            java.nio.file.Files.createDirectories(staging);
            try (var files = java.nio.file.Files.list(legacy)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!java.nio.file.Files.isRegularFile(file) || file.getFileName().toString().equals(".store.lock")) {
                        continue;
                    }
                    Path copy = staging.resolve(file.getFileName());
                    java.nio.file.Files.copy(file, copy);
                    if (java.nio.file.Files.mismatch(file, copy) != -1L) {
                        throw new java.io.IOException("copy differs: " + file.getFileName());
                    }
                }
            }
            java.nio.file.Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            this.getLogger().info(this.messages.text("log.storageCopied"));
            return target;
        } catch (java.io.IOException | RuntimeException error) {
            this.getLogger().warning(this.messages.text("log.storageCopyFailed", error.getMessage()));
            try (var leftover = java.nio.file.Files.walk(staging)) {
                for (Path file : (Iterable<Path>) leftover.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    java.nio.file.Files.deleteIfExists(file);
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // the folder with the copy is only a temporary one
            }
            return legacy;
        }
    }

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.messages = new Messages(this.getClass().getClassLoader(), this.getDataFolder().toPath(),
                this.getConfig().getString("language", "zh-TW"), line -> this.getLogger().warning(line));

        try {
            Backend backend = Backend.valueOf(this.getConfig().getString("storage.backend", "sqlite").toUpperCase(Locale.ROOT));
            Path directory = this.storageDirectory(backend);
            DocumentStore store = DocumentStore.open(new StoreConfig(backend, directory, 1));
            PendingCodes codes = new PendingCodes(System::currentTimeMillis,
                    this.getConfig().getInt("link.code-ttl-seconds", 600),
                    this.getConfig().getInt("link.max-attempts", 5),
                    this.getConfig().getInt("link.lockout-seconds", 600));
            this.links = new LinkService(store, System::currentTimeMillis, codes);
            this.getLogger().info(this.messages.text("log.linksLoaded", this.links.count()));
        } catch (Exception e) {
            this.getLogger().error(this.messages.text("log.storageOpenFailed"), e);
            this.disabledReason = this.messages.text("status.reason.storage", e.getMessage());
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
            this.disabledReason = this.messages.text("status.reason.onePlugin");
        } else if (this.getConfig().getString("token", "").isBlank()) {
            this.disabledReason = this.messages.text("status.reason.noToken");
        } else if (this.getConfig().getString("msg-channel-id", "").isBlank()) {
            this.disabledReason = this.messages.text("status.reason.noMsgChannel");
        }

        if (this.disabledReason != null) {
            this.getLogger().warning(this.messages.text("log.disabled", this.disabledReason));
            return;
        }

        this.service = new DiscordService(this, this.links, this.sync);
        DiscordLink.register(this.service);
        this.service.start(this.getConfig());

        this.scheduleSync();
    }

    /** The next round comes at the normal interval, or at the longer one when there is nothing to sync. */
    private void scheduleSync() {
        int base = Math.max(60, this.getConfig().getInt("sync-interval-seconds", 300));
        int idle = Math.max(base, this.getConfig().getInt("sync-interval-idle-seconds", 1800));
        this.getServer().getScheduler().scheduleDelayedTask(this, () -> {
            if (this.service.isReady()) {
                this.sync.syncAll();
            }
            this.scheduleSync();
        }, (this.sync.idle() ? idle : base) * 20);
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
                this.getLogger().warning(this.messages.text("log.linksCloseFailed", e.getMessage()));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (this.links != null) {
            // The last known name is display only; saving it is disk work, so it is off the server thread
            UUID id = event.getPlayer().getUniqueId();
            String name = event.getPlayer().getName();
            this.getServer().getScheduler().scheduleTask(this, () -> this.links.onLogin(id, name), true);
        }
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
        if (this.links == null && !sub.equals("status") && !sub.equals("help")) {
            sender.sendMessage(this.messages.text("err.bindingUnavailable", this.disabledReason));
            return true;
        }

        switch (sub) {
            case "help":
                for (String line : this.messages.lines("help.discord")) {
                    sender.sendMessage(line);
                }
                if (admin) {
                    for (String line : this.messages.lines("help.discordAdmin")) {
                        sender.sendMessage(line);
                    }
                }
                return true;
            case "status":
                if (!admin) {
                    sender.sendMessage(this.messages.text("err.noPermissionStatus"));
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
                sender.sendMessage(this.messages.text("cmd.discord.usage")
                        + (admin ? this.messages.text("cmd.discord.usageAdmin") : ""));
                return true;
        }
    }

    private void status(CommandSender sender) {
        String state;
        if (this.service == null) {
            state = this.messages.text("status.disabled", this.disabledReason);
        } else if (this.service.isReady()) {
            state = this.messages.text("status.ready");
        } else if (this.service.getFailure() != null) {
            state = this.messages.text("status.notReady", this.service.getFailure());
        } else {
            state = this.messages.text("status.connecting");
        }
        sender.sendMessage(this.messages.text("msg.status.discord", state));
        if (this.service != null && this.service.isReady()) {
            sender.sendMessage(this.messages.text("msg.status.msgChannel", this.service.describeChannel(DiscordLink.MSG_CHANNEL)));
            sender.sendMessage(this.messages.text("msg.status.cmdChannel", this.service.describeChannel(DiscordLink.CMD_CHANNEL)));
        }
        if (this.links != null) {
            sender.sendMessage(this.messages.text("msg.status.linked", this.links.count()));
            sender.sendMessage(this.messages.text("msg.status.nickname", this.sync.nicknameMode().name().toLowerCase(Locale.ROOT),
                    this.sync.roleMappings(),
                    this.sync.roleMappings() == 0 ? this.messages.text("status.rolesEmpty") : ""));
        }
        sender.sendMessage(this.messages.text("msg.status.permission"));
    }

    private void link(CommandSender sender, String[] args, boolean admin) {
        if (args.length == 3) {
            // /discord link <player> <Discord user id>: only for operators
            if (!admin) {
                sender.sendMessage(this.messages.text("err.needOpLink"));
                return;
            }
            UUID target = this.resolve(sender, args[1]);
            if (target == null) {
                return;
            }
            String discordId = args[2].trim();
            if (!discordId.matches("\\d{15,25}")) {
                sender.sendMessage(this.messages.text("err.invalidDiscordId"));
                return;
            }
            LinkService.Result result = this.links.link(target, discordId, this.nameOfPlayer(target));
            sender.sendMessage(this.describe(result, target));
            if (result == LinkService.Result.LINKED) {
                this.getLogger().info(this.messages.text("log.linkedByAdmin", sender.getName(), target, discordId));
                this.sync.sync(target, discordId);
            }
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(this.messages.text("msg.link.usageConsole"));
            return;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        if (args.length == 1) {
            if (this.links.discordUserOf(uuid).isPresent()) {
                sender.sendMessage(this.messages.text("msg.link.alreadyLinked"));
                return;
            }
            String code = this.links.codes().issue(PendingCodes.Side.GAME, uuid.toString());
            int minutes = Math.max(1, this.getConfig().getInt("link.code-ttl-seconds", 600) / 60);
            sender.sendMessage(this.messages.text("msg.link.code", code, minutes));
            sender.sendMessage(this.messages.text("msg.link.codeHint", code));
            if (this.service == null || !this.service.isReady()) {
                sender.sendMessage(this.messages.text("msg.link.botNotReady"));
            }
            return;
        }
        if (args.length == 2) {
            LinkService.Confirmation result = this.links.confirm(PendingCodes.Side.GAME, uuid.toString(), args[1]);
            switch (result.outcome()) {
                case LOCKED:
                    sender.sendMessage(this.messages.text("err.codeLocked", this.links.codes().lockoutSeconds()));
                    return;
                case WRONG_OR_EXPIRED:
                    sender.sendMessage(this.messages.text("err.codeWrong"));
                    return;
                default:
                    sender.sendMessage(this.describe(result.result(), result.match().player()));
                    if (result.result() == LinkService.Result.LINKED) {
                        UUID linked = result.match().player();
                        String linkedName = sender.getName();
                        this.getServer().getScheduler().scheduleTask(this, () -> this.links.updateName(linked, linkedName), true);
                        this.sync.sync(result.match().player(), result.match().discordUser());
                    }
                    return;
            }
        }
        sender.sendMessage(this.messages.text("msg.link.usage"));
    }

    private void unlink(CommandSender sender, String[] args, boolean admin) {
        UUID target;
        if (args.length >= 2) {
            if (!admin) {
                sender.sendMessage(this.messages.text("err.needOpUnlink"));
                return;
            }
            target = this.resolve(sender, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player) {
            target = ((Player) sender).getUniqueId();
        } else {
            sender.sendMessage(this.messages.text("msg.unlink.usageConsole"));
            return;
        }
        if (this.links.unlinkPlayer(target)) {
            sender.sendMessage(this.messages.text("msg.unlink.done"));
            if (args.length >= 2) {
                this.getLogger().info(this.messages.text("log.unlinkedByAdmin", sender.getName(), target));
            }
        } else {
            sender.sendMessage(this.messages.text("err.unlinkNone"));
        }
    }

    private void info(CommandSender sender, String[] args, boolean admin) {
        UUID target;
        if (args.length >= 2) {
            if (!admin) {
                sender.sendMessage(this.messages.text("err.needOpInfo"));
                return;
            }
            target = this.resolve(sender, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player) {
            target = ((Player) sender).getUniqueId();
        } else {
            sender.sendMessage(this.messages.text("msg.info.usageConsole"));
            return;
        }
        String discord = this.links.discordUserOf(target).orElse(null);
        sender.sendMessage(discord == null ? this.messages.text("msg.info.notLinked") : this.messages.text("msg.info.linked", discord));
    }

    /** A player name to the UUID, with the reason when there is none. */
    private UUID resolve(CommandSender sender, String name) {
        Player online = Server.getInstance().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        IPlayer offline = Server.getInstance().getOfflinePlayer(name);
        if (offline == null || offline.getUniqueId() == null || !offline.hasPlayedBefore()) {
            sender.sendMessage(this.messages.text("err.playerNotFound", name));
            return null;
        }
        return offline.getUniqueId();
    }

    private String nameOfPlayer(UUID player) {
        String name = Server.getInstance().getOfflinePlayer(player).getName();
        return name != null ? name : this.links.lastNameOf(player).orElse(null);
    }

    private String describe(LinkService.Result result, UUID player) {
        String name = this.nameOfPlayer(player);
        switch (result) {
            case LINKED:
                return this.messages.text("msg.link.done", name == null ? player : name);
            case ALREADY_LINKED:
                return this.messages.text("msg.link.already");
            case PLAYER_HAS_OTHER:
                return this.messages.text("err.link.playerHasOther");
            case DISCORD_HAS_OTHER:
                return this.messages.text("err.link.discordHasOther");
            default:
                return this.messages.text("err.link.saveFailed");
        }
    }
}

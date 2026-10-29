package dev.toka.discord;

import cn.nukkit.IPlayer;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.HandlerList;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import dev.toka.discord.api.DiscordLink;
import dev.toka.discord.chat.DiscordService;
import dev.toka.discord.link.LinkService;
import dev.toka.discord.link.LinkedGroup;
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
    private static volatile Main instance;
    private MemberSync sync;
    private DiscordService service;
    private String disabledReason;
    private Messages messages;
    private AutoCloseable plan;
    private dev.toka.discord.plan.PlanControl planControl;
    private Ui ui = new Ui();
    private boolean reloading;

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
        // A reload runs this again on the same object, so nothing from the last start may be left over
        this.disabledReason = null;
        this.service = null;
        this.links = null;
        this.sync = null;
        this.planControl = null;
        this.ui = new Ui();
        instance = this;
        this.saveDefaultConfig();
        suggest("discord",
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"help"}), CommandParameter.newType("page", true, CommandParamType.INT)},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"reload"})},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"info"}), CommandParameter.newType("player", true, CommandParamType.TARGET)},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"status"})},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"link"}), CommandParameter.newType("code", true, CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"link"}), CommandParameter.newType("player", CommandParamType.TARGET), CommandParameter.newType("discord_user_id", CommandParamType.STRING)},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"unlink"}), CommandParameter.newType("player", true, CommandParamType.TARGET)},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"plan"}), CommandParameter.newEnum("plan_action", new String[]{"status"})},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"plan"}), CommandParameter.newEnum("plan_action", new String[]{"mode"}), CommandParameter.newEnum("mode", new String[]{"auto", "enable", "disable"})},
                new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"plan"}), CommandParameter.newEnum("plan_action", new String[]{"fields"}), CommandParameter.newType("fields", true, CommandParamType.RAWTEXT)});
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
        if (this.getConfig().getBoolean("linked-group.enabled", true)) {
            LinkedGroup linkedGroup = new LinkedGroup(this, this.getConfig().getString("linked-group.name", "linked"));
            this.links.onChange(linkedGroup::set);
            this.getServer().getScheduler().scheduleDelayedTask(this, () -> linkedGroup.fill(this.links.all().keySet()), 200);
        }
        this.getServer().getPluginManager().registerEvents(new dev.toka.discord.link.BotCommands(this), this);
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
        this.getServer().getPluginManager().registerEvents(this.ui, this);
        refreshPlan();

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
        this.service.start(this.getConfig(), !this.reloading);

        this.scheduleSync();
    }

    private void suggest(String name, CommandParameter[]... forms) {
        Command registered = (Command) getCommand(name);
        if (registered == null) return;
        registered.getCommandParameters().clear();
        for (int i = 0; i < forms.length; i++) registered.addCommandParameters("choice" + i, forms[i]);
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
        this.shutdown(true);
    }

    /** Stops everything that onEnable started; a reload does it without the goodbye message in the Discord channel. */
    private void shutdown(boolean farewell) {
        if (instance == this) instance = null;
        closePlan();
        if (this.service != null) {
            DiscordLink.unregister(this.service);
            this.service.stop(farewell ? this.getConfig() : null);
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

    /** Re-reads config.yml and the texts, then starts again; the bot reconnects but does not announce it. */
    private void reload(CommandSender sender) {
        this.shutdown(false);
        this.getServer().getScheduler().cancelTask(this);
        HandlerList.unregisterAll((cn.nukkit.plugin.Plugin) this);
        this.reloadConfig();
        this.reloading = true;
        try {
            this.onEnable();
        } finally {
            this.reloading = false;
        }
        sender.sendMessage(this.messages.text("msg.reloaded"));
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
        if(planControl!=null&&planControl.command(sender,args,PERMISSION_ADMIN))return true;
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(PERMISSION_ADMIN) || !(sender instanceof Player);
        if (sub.equals("reload")) {
            if (admin) {
                this.reload(sender);
            } else {
                sender.sendMessage(this.messages.text("err.noPermissionStatus"));
            }
            return true;
        }
        if (this.links == null && !sub.equals("status") && !sub.equals("help")) {
            sender.sendMessage(this.messages.text("err.bindingUnavailable", this.disabledReason));
            return true;
        }

        switch (sub) {
            case "help":
                this.helpPage(sender, admin, args.length > 1 ? args[1] : "");
                return true;
            case "status":
                if (!admin) {
                    sender.sendMessage(this.messages.text("err.noPermissionStatus"));
                    return true;
                }
                if (sender instanceof Player self) {
                    this.statusWindow(self);
                } else {
                    for (String line : this.statusLines()) {
                        sender.sendMessage(line);
                    }
                }
                return true;
            case "role":
                if (!admin) {
                    sender.sendMessage(this.messages.text("err.noPermissionStatus"));
                } else {
                    this.roleCommand(sender, args);
                }
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

    /** Chat help in pages of six lines; the first page is what every player uses, the administrator lines follow. */
    private void helpPage(CommandSender sender, boolean admin, String requested) {
        java.util.List<String> lines = new java.util.ArrayList<>(this.messages.lines("help.discord"));
        lines.remove(0);
        if (admin) {
            lines.addAll(this.messages.lines("help.discordAdmin"));
        }
        int pages = Math.max(1, (lines.size() + 5) / 6);
        int page;
        try {
            page = Integer.parseInt(requested);
        } catch (NumberFormatException first) {
            page = 1;
        }
        page = Math.max(1, Math.min(page, pages));
        sender.sendMessage(this.messages.text("help.head", page, pages));
        for (String line : lines.subList((page - 1) * 6, Math.min(lines.size(), page * 6))) {
            sender.sendMessage(line);
        }
    }

    private java.util.List<String> statusLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
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
        lines.add(this.messages.text("msg.status.discord", state));
        if (this.service != null && this.service.isReady()) {
            lines.add(this.messages.text("msg.status.msgChannel", this.service.describeChannel(DiscordLink.MSG_CHANNEL)));
            lines.add(this.messages.text("msg.status.cmdChannel", this.service.describeChannel(DiscordLink.CMD_CHANNEL)));
        }
        if (this.links != null) {
            lines.add(this.messages.text("msg.status.linked", String.format(Locale.ROOT, "%,d", this.links.count())));
            lines.add(this.messages.text("msg.status.nickname", this.sync.nicknameMode().name().toLowerCase(Locale.ROOT),
                    this.sync.roleMappings(),
                    this.sync.roleMappings() == 0 ? this.messages.text("status.rolesEmpty") : ""));
        }
        lines.add(this.messages.text("msg.status.permission"));
        return lines;
    }

    /** The status as a window; the buttons run the same link and unlink code as the commands. */
    private void statusWindow(Player admin) {
        var window = new cn.nukkit.form.window.FormWindowSimple(this.messages.text("ui.status.title"), String.join("\n", this.statusLines()));
        window.addButton(Ui.button(this.messages.text("ui.status.link"), "textures/ui/color_plus"));
        window.addButton(Ui.button(this.messages.text("ui.status.unlink"), "textures/ui/cancel"));
        window.addButton(Ui.button(this.messages.text("ui.role.button"), "textures/ui/dressing_room_customization"));
        window.addButton(new cn.nukkit.form.element.ElementButton(this.messages.text("ui.status.close")));
        this.ui.show(admin, window, answer -> {
            int id = ((cn.nukkit.form.response.FormResponseSimple) answer).getClickedButtonId();
            if (id == 2 && this.sync != null) {
                this.roleWindow(admin);
            } else if (id == 0 && this.links != null) {
                this.linkWindow(admin);
            } else if (id == 1 && this.links != null) {
                this.unlinkWindow(admin);
            }
        });
    }

    // ------------------------------------------------------------------ group -> Discord role mapping

    /** Writes the mapping back into config.yml, keeping every comment: only the lines of the {@code roles:} block are replaced. */
    private void saveRoles() {
        java.nio.file.Path file = this.getDataFolder().toPath().resolve("config.yml");
        try {
            java.util.List<String> lines = new java.util.ArrayList<>(java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8));
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("roles:")) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                return;
            }
            int end = start + 1;
            while (end < lines.size() && (lines.get(end).startsWith(" ") || lines.get(end).startsWith("\t"))) {
                end++;
            }
            java.util.List<String> block = new java.util.ArrayList<>();
            var mapped = this.sync.roles();
            if (mapped.isEmpty()) {
                block.add("roles: {}");
            } else {
                block.add("roles:");
                mapped.forEach((group, role) -> block.add("  " + group + ": \"" + role + "\""));
            }
            lines.subList(start, end).clear();
            lines.addAll(start, block);
            java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failed) {
            this.getLogger().warning(this.messages.text("role.saveFailed", String.valueOf(failed.getMessage())));
        }
    }

    private void roleCommand(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "set" -> {
                if (args.length < 4) {
                    sender.sendMessage(this.messages.text("role.usage"));
                } else {
                    sender.sendMessage(this.setRole(args[2].toLowerCase(Locale.ROOT), args[3].trim()));
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(this.messages.text("role.usage"));
                } else {
                    sender.sendMessage(this.removeRole(args[2].toLowerCase(Locale.ROOT)));
                }
            }
            case "check" -> {
                sender.sendMessage(this.messages.text("role.checking", this.sync.accounts()));
                this.sync.check(difference -> sender.sendMessage(this.messages.text("role.difference", difference[0], difference[1],
                        this.messages.text(Boolean.parseBoolean(difference[2]) ? "role.gameOnly" : "role.discordOnly"))),
                        report -> sender.sendMessage(this.messages.text("role.checked", report.accounts.get(), report.differences.get(),
                                report.unknownGroups.get(), report.notFound.get())));
            }
            case "all" -> this.sync.guildRoles().forEach((id, name) -> sender.sendMessage(this.messages.text("role.line", name, id)));
            case "sync" -> {
                int accounts = this.sync.accounts();
                sender.sendMessage(this.messages.text("role.syncing", accounts, Math.max(1, accounts / 2)));
                this.sync.syncAll(report -> sender.sendMessage(this.messages.text("role.synced", report.accounts.get(), report.added.get(),
                        report.removed.get(), report.nicknames.get(), report.notFound.get(), report.unknownGroups.get(), report.failed.get())));
            }
            default -> {
                var mapped = this.sync.roles();
                sender.sendMessage(this.messages.text("role.listHead", mapped.size()));
                mapped.forEach((group, role) -> sender.sendMessage(this.messages.text("role.line", group, this.roleLabel(role))));
            }
        }
    }

    private String roleLabel(String role) {
        return this.roleLabel(role, "role.unknownRole");
    }

    private String roleLabel(String role, String unknownKey) {
        String name = this.sync.roleName(role);
        return name == null ? role + this.messages.text(unknownKey) : name + " (" + role + ")";
    }

    private String setRole(String group, String role) {
        if (!role.matches("\\d{15,25}")) {
            return this.messages.text("role.badId");
        }
        this.sync.setRole(group, role);
        this.saveRoles();
        return this.messages.text("role.set", group, this.roleLabel(role));
    }

    private String removeRole(String group) {
        if (!this.sync.roles().containsKey(group)) {
            return this.messages.text("role.none", group);
        }
        this.sync.removeRole(group);
        this.saveRoles();
        return this.messages.text("role.removed", group);
    }

    private void roleWindow(Player admin) {
        var mapped = this.sync.roles();
        var window = new cn.nukkit.form.window.FormWindowSimple(this.messages.text("ui.role.title"), this.messages.text("ui.role.hint"));
        window.addButton(new cn.nukkit.form.element.ElementButton(this.messages.text("ui.role.back")));
        window.addButton(Ui.button(this.messages.text("ui.role.add"), "textures/ui/color_plus"));
        window.addButton(Ui.button(this.messages.text("ui.role.sync"), "textures/ui/refresh"));
        java.util.List<String> groups = new java.util.ArrayList<>(mapped.keySet());
        for (String group : groups) {
            window.addButton(new cn.nukkit.form.element.ElementButton(this.messages.text("ui.role.entry", group, this.roleLabel(mapped.get(group), "role.unknownRole_btn"))));
        }
        this.ui.show(admin, window, answer -> {
            int id = ((cn.nukkit.form.response.FormResponseSimple) answer).getClickedButtonId();
            if (!admin.hasPermission(PERMISSION_ADMIN)) {
                return;
            }
            if (id == 0) {
                this.statusWindow(admin);
            } else if (id == 1) {
                this.roleEditWindow(admin, "", "");
            } else if (id == 2) {
                this.sync.syncAll();
                admin.sendMessage(this.messages.text("role.syncing"));
            } else if (id - 3 < groups.size()) {
                this.roleEditWindow(admin, groups.get(id - 3), mapped.get(groups.get(id - 3)));
            }
        });
    }

    private void roleEditWindow(Player admin, String group, String role) {
        boolean adding = group.isEmpty();
        var window = new cn.nukkit.form.window.FormWindowCustom(this.messages.text(adding ? "ui.role.add" : "ui.role.edit"));
        window.addElement(new cn.nukkit.form.element.ElementInput(this.messages.text("ui.role.group"), this.messages.text("ui.role.groupHint"), group));
        window.addElement(new cn.nukkit.form.element.ElementInput(this.messages.text("ui.role.roleId"), "", role));
        if (!adding) {
            window.addElement(new cn.nukkit.form.element.ElementToggle(this.messages.text("ui.role.delete"), false));
        }
        this.ui.show(admin, window, answer -> {
            var response = (cn.nukkit.form.response.FormResponseCustom) answer;
            if (!admin.hasPermission(PERMISSION_ADMIN)) {
                return;
            }
            String newGroup = response.getInputResponse(0).trim().toLowerCase(Locale.ROOT);
            if (!adding && response.getToggleResponse(2)) {
                admin.sendMessage(this.removeRole(group));
            } else if (newGroup.isEmpty()) {
                admin.sendMessage(this.messages.text("role.usage"));
            } else {
                if (!adding && !newGroup.equals(group)) {
                    this.sync.removeRole(group);
                }
                admin.sendMessage(this.setRole(newGroup, response.getInputResponse(1).trim()));
            }
            this.roleWindow(admin);
        });
    }

    private void linkWindow(Player admin) {
        var window = new cn.nukkit.form.window.FormWindowCustom(this.messages.text("ui.status.link"));
        window.addElement(new cn.nukkit.form.element.ElementInput(this.messages.text("ui.status.player"), "", ""));
        window.addElement(new cn.nukkit.form.element.ElementInput(this.messages.text("ui.status.discordId"), "", ""));
        this.ui.show(admin, window, answer -> {
            var response = (cn.nukkit.form.response.FormResponseCustom) answer;
            if (admin.hasPermission(PERMISSION_ADMIN)) {
                this.link(admin, new String[]{"link", response.getInputResponse(0).trim(), response.getInputResponse(1).trim()}, true);
            }
        });
    }

    private void unlinkWindow(Player admin) {
        var window = new cn.nukkit.form.window.FormWindowCustom(this.messages.text("ui.status.unlink"));
        window.addElement(new cn.nukkit.form.element.ElementInput(this.messages.text("ui.status.player"), "", ""));
        this.ui.show(admin, window, answer -> {
            String name = ((cn.nukkit.form.response.FormResponseCustom) answer).getInputResponse(0).trim();
            if (name.isEmpty()) {
                return;
            }
            var confirm = new cn.nukkit.form.window.FormWindowModal(this.messages.text("ui.status.confirmTitle"),
                    this.messages.text("ui.status.confirmBody", name), this.messages.text("ui.status.yes"), this.messages.text("ui.status.no"));
            this.ui.show(admin, confirm, reply -> {
                if (((cn.nukkit.form.response.FormResponseModal) reply).getClickedButtonId() == 0 && admin.hasPermission(PERMISSION_ADMIN)) {
                    this.unlink(admin, new String[]{"unlink", name}, true);
                }
            });
        });
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
    private void closePlan(){if(planControl!=null)planControl.registered(false);if(plan!=null)try{plan.close();}catch(Exception|LinkageError e){getLogger().warning(messages.text("plan.unavailable",e.getClass().getSimpleName()));}finally{plan=null;}}
    private void refreshPlan(){closePlan();
        if(planControl==null)planControl=new dev.toka.discord.plan.PlanControl(this,this::refreshPlan,()->java.util.Set.of("bound"),(key,args)->messages.text(key,args),"discord");var plugin=getServer().getPluginManager().getPlugin("Plan");
        if(links==null||!planControl.shouldRegister()||!planControl.options().has("bound"))return;
        try{var extension=new dev.toka.discord.plan.PlanExtension(links::hasBinding,key->messages.text(key));
            if(extension.register()){plan=extension;planControl.registered(true);getLogger().info(messages.text("plan.ready"));}else getLogger().warning(messages.text("plan.unsupported"));
        }catch(RuntimeException|LinkageError e){getLogger().warning(messages.text("plan.unavailable",e.getClass().getSimpleName()));}}
    @EventHandler public void planEnable(cn.nukkit.event.plugin.PluginEnableEvent e){if("Plan".equals(e.getPlugin().getName()))refreshPlan();}
    @EventHandler public void planDisable(cn.nukkit.event.plugin.PluginDisableEvent e){if("Plan".equals(e.getPlugin().getName()))closePlan();}    public String planStatusJson(){return planControl.statusJson();}
    public boolean configurePlan(String mode,String[] fields){return planControl.configure(mode,fields);}
}

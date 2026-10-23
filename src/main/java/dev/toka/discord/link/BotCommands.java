package dev.toka.discord.link;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.plugin.Plugin;
import dev.toka.discord.Main;
import dev.toka.discord.api.DiscordLink;
import dev.toka.discord.event.BotCommandEvent;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The commands written in the Discord message channel: list for everyone, c and ser for administrators.
 * <p>
 * Who writes is found by the user id of the link between a Discord account and a player, never by the Discord name. An administrator
 * is an operator, or a player whose permissions carry {@code toka.identity.admin}.
 */
public final class BotCommands implements Listener {
    private static final String ADMIN_NODE = "toka.identity.admin";
    private final Main plugin;

    public BotCommands(Main plugin) { this.plugin = plugin; }

    private String text(String key, Object... args) { return plugin.messages().text(key, args); }

    private static void reply(String name, String text) { DiscordLink.sendMessage("`" + name + "`" + text); }

    @EventHandler public void onBotCommand(BotCommandEvent event) {
        String[] args = event.getCommand();
        if (args.length == 0) return;
        String command = args[0].toLowerCase(Locale.ROOT);
        if (!command.equals("list") && !command.equals("c") && !command.equals("ser")) return;
        String name = event.getSenderName();
        UUID player = event.getLinkedPlayer().orElse(null);
        event.setHandled(true);
        if (player == null) { reply(name, text("bot.notLinked")); return; }
        if (command.equals("list")) { list(name); return; }
        // An administrator is looked up off the server thread: LuckPerms may have to read the player first.
        Thread check = new Thread(() -> {
            boolean admin = isAdministrator(player);
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                if (!admin) { reply(name, text("bot.needAdmin")); return; }
                if (command.equals("c")) runAs(name, args); else ser(name, player, args);
            });
        }, "tokaDiscordLink-bot-command");
        check.setDaemon(true);
        check.start();
    }

    private void list(String name) {
        Server server = plugin.getServer();
        StringBuilder online = new StringBuilder();
        for (Player p : server.getOnlinePlayers().values()) {
            if (online.length() > 0) online.append(", ");
            online.append(p.getDisplayName());
        }
        reply(name, text("bot.list", server.getOnlinePlayers().size(), server.getMaxPlayers(), online));
    }

    /** c <player> <command>: the command is carried out as that online player. */
    private void runAs(String name, String[] args) {
        if (args.length < 3) { reply(name, text("bot.usage.c")); return; }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) { reply(name, text("bot.noPlayer")); return; }
        String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        reply(name, text("bot.ranAs", target.getName(), command));
        if (anchorHere()) plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "anchor run " + target.getName() + " " + command);
        else plugin.getServer().dispatchCommand(target, command);
    }

    private void ser(String name, UUID player, String[] args) {
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "clean" -> {
                plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "anchor clean");
                reply(name, text("bot.cleaned"));
            }
            case "kill" -> {
                Player target = args.length < 3 ? null : plugin.getServer().getPlayerExact(args[2]);
                if (target == null) { reply(name, text("bot.noPlayer")); return; }
                if (anchorHere()) plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "anchor kill " + target.getName());
                else target.kill();
                reply(name, text("bot.killed", target.getName()));
            }
            case "test" -> reply(name, text("bot.test"));
            default -> { reply(name, text("bot.usage.ser")); return; }
        }
        plugin.getLogger().info(text("bot.logged", name, player, sub));
    }

    /** The administrator commands are tokaAnchor's; this only sends them on. */
    private boolean anchorHere() {
        Plugin anchor = plugin.getServer().getPluginManager().getPlugin("tokaAnchor");
        return anchor != null && anchor.isEnabled();
    }

    /** An operator, or a player with the administrator identity; for a player who is offline LuckPerms is asked. */
    private boolean isAdministrator(UUID player) {
        Server server = plugin.getServer();
        Player online = server.getOnlinePlayers().get(player);
        if (online != null) return online.isOp() || online.hasPermission(ADMIN_NODE);
        String name = server.getOfflinePlayer(player).getName();
        if (name != null && server.isOp(name)) return true;
        Plugin luckPerms = server.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) return false;
        try {
            ClassLoader loader = luckPerms.getClass().getClassLoader();
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader).getMethod("get").invoke(null);
            Object users = Class.forName("net.luckperms.api.LuckPerms", true, loader).getMethod("getUserManager").invoke(api);
            Object user = ((java.util.concurrent.CompletableFuture<?>) Class.forName("net.luckperms.api.model.user.UserManager", true, loader)
                    .getMethod("loadUser", UUID.class).invoke(users, player)).get(30, TimeUnit.SECONDS);
            Object cached = Class.forName("net.luckperms.api.model.PermissionHolder", true, loader).getMethod("getCachedData").invoke(user);
            Object data = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", true, loader).getMethod("getPermissionData").invoke(cached);
            Object result = Class.forName("net.luckperms.api.cacheddata.PermissionData", true, loader).getMethod("checkPermission", String.class).invoke(data, ADMIN_NODE);
            // Newer versions answer with a result that holds the Tristate.
            if (Class.forName("net.luckperms.api.cacheddata.Result", true, loader).isInstance(result))
                result = Class.forName("net.luckperms.api.cacheddata.Result", true, loader).getMethod("result").invoke(result);
            return Boolean.TRUE.equals(Class.forName("net.luckperms.api.util.Tristate", true, loader).getMethod("asBoolean").invoke(result));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            return false;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

package dev.toka.discord.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Keeps the permission group of players with a Discord link in step with the links: added on link, removed on unlink, filled in at start. */
public final class LinkedGroup {
    private final Plugin plugin;
    private final String group;

    public LinkedGroup(Plugin plugin, String group) {
        this.plugin = plugin;
        this.group = group;
    }

    private Plugin luckPerms() {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        return luckPerms != null && luckPerms.isEnabled() ? luckPerms : null;
    }

    public void set(UUID player, boolean linked) {
        if (luckPerms() == null) return;
        Server server = plugin.getServer();
        server.getScheduler().scheduleTask(plugin, () -> server.dispatchCommand(server.getConsoleSender(), "lp user " + player + " parent " + (linked ? "add " : "remove ") + group));
    }

    /** Adds the players who are not in the group yet; the ones who already are cause no command and no message. */
    public void fill(Collection<UUID> players) {
        Plugin luckPerms = luckPerms();
        if (luckPerms == null) return;
        Thread check = new Thread(() -> {
            List<UUID> missing = new ArrayList<>();
            for (UUID player : players) {
                try { if (!member(luckPerms, player)) missing.add(player); }
                catch (ReflectiveOperationException | LinkageError | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unknown) { missing.add(player); }
                catch (InterruptedException stopped) { return; }
            }
            Server server = plugin.getServer();
            int tick = 0;
            for (UUID player : missing) {
                tick += 3;
                server.getScheduler().scheduleDelayedTask(plugin, () -> server.dispatchCommand(server.getConsoleSender(), "lp user " + player + " parent add " + group), tick);
            }
        }, "tokaDiscordLink-linked-group");
        check.setDaemon(true);
        check.start();
    }

    /** Reads the player's groups through LuckPerms' API, reflectively so that it is not linked. */
    private boolean member(Plugin luckPerms, UUID player) throws ReflectiveOperationException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException, InterruptedException {
        ClassLoader loader = luckPerms.getClass().getClassLoader();
        Object api = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader).getMethod("get").invoke(null);
        Object users = Class.forName("net.luckperms.api.LuckPerms", true, loader).getMethod("getUserManager").invoke(api);
        Object user = ((java.util.concurrent.CompletableFuture<?>) Class.forName("net.luckperms.api.model.user.UserManager", true, loader)
                .getMethod("loadUser", UUID.class).invoke(users, player)).get(30, TimeUnit.SECONDS);
        Collection<?> nodes = (Collection<?>) Class.forName("net.luckperms.api.model.PermissionHolder", true, loader).getMethod("getNodes").invoke(user);
        Class<?> node = Class.forName("net.luckperms.api.node.Node", true, loader);
        for (Object each : nodes) if (("group." + group).equalsIgnoreCase((String) node.getMethod("getKey").invoke(each))) return true;
        return false;
    }
}

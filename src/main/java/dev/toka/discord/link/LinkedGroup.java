package dev.toka.discord.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.Collection;
import java.util.UUID;

/** Keeps the permission group of players with a Discord link in step with the links: added on link, removed on unlink, filled in at start. */
public final class LinkedGroup {
    private final Plugin plugin;
    private final String group;

    public LinkedGroup(Plugin plugin, String group) {
        this.plugin = plugin;
        this.group = group;
    }

    private boolean available() {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        return luckPerms != null && luckPerms.isEnabled();
    }

    public void set(UUID player, boolean linked) {
        if (!available()) return;
        Server server = plugin.getServer();
        server.getScheduler().scheduleTask(plugin, () -> server.dispatchCommand(server.getConsoleSender(), "lp user " + player + " parent " + (linked ? "add " : "remove ") + group));
    }

    /** One command every few ticks: LuckPerms works on them in the background. */
    public void fill(Collection<UUID> players) {
        if (!available()) return;
        Server server = plugin.getServer();
        int tick = 0;
        for (UUID player : players) {
            tick += 3;
            server.getScheduler().scheduleDelayedTask(plugin, () -> server.dispatchCommand(server.getConsoleSender(), "lp user " + player + " parent add " + group), tick);
        }
    }
}

package dev.toka.discord.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The permission groups of a player who may be offline, read through the LuckPerms API (reflectively, so it is not linked).
 * The groups are the ones the player has, inherited ones included, the same answer that {@code hasPermission("group.x")} gives for a player online.
 */
final class OfflineGroups {
    private OfflineGroups() { }

    private static Plugin luckPerms(Plugin plugin) {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        return luckPerms != null && luckPerms.isEnabled() ? luckPerms : null;
    }

    /** Which of the given groups the player has; null when LuckPerms cannot tell. Blocks: never call it on the server thread. */
    static Set<String> of(Plugin plugin, UUID player, Set<String> wanted) {
        Plugin luckPerms = luckPerms(plugin);
        if (luckPerms == null || Server.getInstance().isPrimaryThread()) return null;
        try {
            ClassLoader loader = luckPerms.getClass().getClassLoader();
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader).getMethod("get").invoke(null);
            Object users = Class.forName("net.luckperms.api.LuckPerms", true, loader).getMethod("getUserManager").invoke(api);
            Class<?> manager = Class.forName("net.luckperms.api.model.user.UserManager", true, loader);
            Object user = ((java.util.concurrent.CompletableFuture<?>) manager.getMethod("loadUser", UUID.class).invoke(users, player)).get(30, TimeUnit.SECONDS);
            Object data = Class.forName("net.luckperms.api.model.PermissionHolder", true, loader).getMethod("getCachedData").invoke(user);
            Object permissions = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", true, loader).getMethod("getPermissionData").invoke(data);
            var check = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", true, loader).getMethod("checkPermission", String.class);
            var truth = Class.forName("net.luckperms.api.util.Tristate", true, loader).getMethod("asBoolean");
            Set<String> found = new HashSet<>();
            for (String group : wanted) {
                if ((Boolean) truth.invoke(check.invoke(permissions, "group." + group))) found.add(group);
            }
            if (Server.getInstance().getPlayer(player).isEmpty()) {
                try { manager.getMethod("cleanupUser", Class.forName("net.luckperms.api.model.user.User", true, loader)).invoke(users, user); }
                catch (ReflectiveOperationException ignored) { /* the user stays loaded; harmless */ }
            }
            return found;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unknown) {
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

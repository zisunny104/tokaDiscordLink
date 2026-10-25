package dev.toka.discord.link;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.utils.Config;
import dev.toka.discord.Main;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Keeps the Discord side of a linked account in line with the game: the nickname, and the Discord roles that
 * belong to the groups the player has.
 * <p>
 * The nickname is taken over as soon as the accounts are linked ({@code nickname.mode: set}); with
 * {@code enforce} it is also put back when the member changes it. The roles only flow from the game to Discord,
 * and only the roles that are listed in {@code roles} are touched. The role mapping waits for the new permission
 * groups: while it is empty nothing is changed.
 */
public final class MemberSync {

    public enum NicknameMode { OFF, SET, ENFORCE }

    private final Main plugin;
    private final LinkService links;
    private final NicknameMode nicknameMode;
    /** sync: the game decides, roles are added and taken away; add: roles are only added; check: nothing is changed, only /discord role check reports */
    private final String roleMode;
    private final String nicknameFormat;
    /** group name in the game -> id of the Discord role */
    private final Map<String, String> roles = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile JDA jda;
    private volatile String guildId;
    private volatile Function<UUID, String> nameProvider = null;

    public MemberSync(Main plugin, LinkService links, Config config) {
        this.plugin = plugin;
        this.links = links;
        this.nicknameMode = parseMode(config.getString("nickname.mode", "set"));
        this.nicknameFormat = config.getString("nickname.format", "{name}");
        String mode = config.getString("role-mode", "sync").trim().toLowerCase(java.util.Locale.ROOT);
        this.roleMode = mode.equals("add") || mode.equals("check") ? mode : "sync";
        Object mapped = config.get("roles");
        if (mapped instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) mapped).entrySet()) {
                String role = String.valueOf(e.getValue()).trim();
                if (!role.isEmpty() && !role.equals("null")) {
                    this.roles.put(String.valueOf(e.getKey()), role);
                }
            }
        }
    }

    public static NicknameMode parseMode(String value) {
        switch (value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off":
                return NicknameMode.OFF;
            case "enforce":
                return NicknameMode.ENFORCE;
            default:
                return NicknameMode.SET;
        }
    }

    public NicknameMode nicknameMode() {
        return this.nicknameMode;
    }

    public int roleMappings() {
        return this.roles.size();
    }

    /** The group -> role id pairs now in force (a copy). */
    public Map<String, String> roles() {
        synchronized (this.roles) {
            return new LinkedHashMap<>(this.roles);
        }
    }

    /** Every role of the Discord server, id -> name (empty when the bot is not connected). */
    public Map<String, String> guildRoles() {
        JDA client = this.jda;
        Guild guild = client == null || this.guildId == null ? null : client.getGuildById(this.guildId);
        Map<String, String> found = new LinkedHashMap<>();
        if (guild != null) guild.getRoles().forEach(role -> { if (!role.isPublicRole() && !role.isManaged()) found.put(role.getId(), role.getName()); });
        return found;
    }

    public void setRole(String group, String roleId) {
        this.roles.put(group, roleId);
    }

    public void removeRole(String group) {
        this.roles.remove(group);
    }

    /** The name of a Discord role, or null when the role is not on the server (or the bot is not connected). */
    public String roleName(String roleId) {
        JDA client = this.jda;
        Guild guild = client == null || this.guildId == null ? null : client.getGuildById(this.guildId);
        Role role = guild == null || !roleId.matches("\\d{15,25}") ? null : guild.getRoleById(roleId);
        return role == null ? null : role.getName();
    }

    public static String nickname(String format, String name) {
        String result = format.replace("{name}", name).trim();
        // a nickname is at most 32 characters
        return result.length() > 32 ? result.substring(0, 32) : result;
    }

    /** Where to find the Discord server of the bot. */
    public void attach(JDA jda, String guildId) {
        this.jda = jda;
        this.guildId = guildId;
    }

    // TODO: reserved for another plugin that supplies the shown name (e.g. with a title)
    /** Another plugin can supply the name to show, for example one with a title in front of it. */
    public void setNameProvider(Function<UUID, String> provider) {
        this.nameProvider = provider;
    }

    private String nameOf(UUID player) {
        Function<UUID, String> provider = this.nameProvider;
        String name = provider == null ? null : provider.apply(player);
        if (name != null && !name.isBlank()) {
            return name;
        }
        Player online = Server.getInstance().getPlayer(player).orElse(null);
        if (online != null) {
            return online.getName();
        }
        return Server.getInstance().getOfflinePlayer(player).getName();
    }

    /** The nickname the member should have, or null when it is not managed. */
    public String expectedNickname(UUID player) {
        if (this.nicknameMode == NicknameMode.OFF) {
            return null;
        }
        String name = this.nameOf(player);
        return name == null || name.isBlank() ? null : nickname(this.nicknameFormat, name);
    }

    /** Brings the nickname and the roles of one linked account in line with the game. Never blocks the caller. */
    public void sync(UUID player, String discordUserId) {
        JDA client = this.jda;
        if (client == null || this.guildId == null) {
            return;
        }
        Guild guild = client.getGuildById(this.guildId);
        if (guild == null) {
            return;
        }
        // the game objects are read on the server thread; the Discord calls then run on the JDA threads
        this.plugin.getServer().getScheduler().scheduleTask(this.plugin, () -> {
            String nick = this.expectedNickname(player);
            Set<String> groups = this.currentGroups(player);
            guild.retrieveMemberById(discordUserId)
                    .queue(member -> this.apply(guild, member, nick, groups), error -> { });
        });
    }

    /** The mapped groups the player has now, or null when the player is offline (nothing is taken away then). */
    private Set<String> currentGroups(UUID player) {
        Player online = this.roles.isEmpty() ? null : Server.getInstance().getPlayer(player).orElse(null);
        if (online == null) {
            return null;
        }
        Set<String> groups = new HashSet<>();
        for (String group : this.roles().keySet()) {
            if (online.hasPermission("group." + group)) {
                groups.add(group);
            }
        }
        return groups;
    }

    private void apply(Guild guild, Member member, String nick, Set<String> groups) {
        if (nick != null && !nick.equals(member.getNickname()) && !member.isOwner()
                && guild.getSelfMember().canInteract(member)) {
            member.modifyNickname(nick).queue(null, error -> { });
        }

        if (groups == null) {
            return;
        }
        for (Map.Entry<String, String> mapping : this.roles().entrySet()) {
            Role role = guild.getRoleById(mapping.getValue());
            if (role == null) {
                this.complain("log.roleMissing", mapping.getValue(), mapping.getKey());
                continue;
            }
            if (!guild.getSelfMember().canInteract(role)) {
                this.complain("log.roleAbove", role.getName(), mapping.getKey());
                continue;
            }
            boolean should = groups.contains(mapping.getKey());
            boolean has = member.getRoles().contains(role);
            if (should && !has && !this.roleMode.equals("check")) {
                guild.addRoleToMember(UserSnowflake.fromId(member.getId()), role).queue(
                        done -> this.plugin.getLogger().info(this.plugin.messages().text("log.roleAdded", role.getName(), member.getEffectiveName())),
                        error -> this.complain("log.roleFailed", role.getName(), String.valueOf(error.getMessage())));
            } else if (!should && has && this.roleMode.equals("sync")) {
                guild.removeRoleFromMember(UserSnowflake.fromId(member.getId()), role).queue(
                        done -> this.plugin.getLogger().info(this.plugin.messages().text("log.roleRemoved", role.getName(), member.getEffectiveName())),
                        error -> this.complain("log.roleFailed", role.getName(), String.valueOf(error.getMessage())));
            }
        }
    }

    /**
     * Reads, changes nothing: for every linked player who is online, tells where the roles on Discord differ from the groups in the game.
     * The lines come back on the server thread.
     */
    public void check(java.util.function.Consumer<String[]> out) {
        JDA client = this.jda;
        Guild guild = client == null || this.guildId == null ? null : client.getGuildById(this.guildId);
        if (guild == null) {
            return;
        }
        for (Map.Entry<UUID, String> link : this.links.all().entrySet()) {
            Set<String> groups = this.currentGroups(link.getKey());
            if (groups == null) {
                continue;
            }
            String name = this.nameOf(link.getKey());
            guild.retrieveMemberById(link.getValue()).queue(member -> {
                for (Map.Entry<String, String> mapping : this.roles().entrySet()) {
                    Role role = guild.getRoleById(mapping.getValue());
                    if (role == null) {
                        continue;
                    }
                    boolean should = groups.contains(mapping.getKey());
                    boolean has = member.getRoles().contains(role);
                    if (should != has) {
                        String[] difference = {name, role.getName(), String.valueOf(should), String.valueOf(has)};
                        this.plugin.getServer().getScheduler().scheduleTask(this.plugin, () -> out.accept(difference));
                    }
                }
            }, error -> { });
        }
    }

    private final Set<String> complained = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A problem with a role is told once in the log (until the next reload), not on every round of the sync. */
    private void complain(String key, String first, String second) {
        if (this.complained.add(key + first + second)) {
            this.plugin.getLogger().warning(this.plugin.messages().text(key, first, second));
        }
    }

    /** A member wrote something: with {@code enforce} the nickname is put back at once. */
    public void onMemberSeen(Member member) {
        if (this.nicknameMode != NicknameMode.ENFORCE) {
            return;
        }
        UUID player = this.links.playerOf(member.getId()).orElse(null);
        if (player == null) {
            return;
        }
        this.plugin.getServer().getScheduler().scheduleTask(this.plugin, () -> {
            String nick = this.expectedNickname(player);
            if (nick != null && !nick.equals(member.getNickname()) && !member.isOwner()
                    && member.getGuild().getSelfMember().canInteract(member)) {
                member.modifyNickname(nick).queue(null, error -> { });
            }
        });
    }

    /** True when a sync round would change nothing: no linked account, or neither nicknames nor roles are managed. */
    public boolean idle() {
        return this.links.all().isEmpty() || (this.nicknameMode == NicknameMode.OFF && this.roles.isEmpty());
    }

    /** All the linked accounts, one after the other, spread over the given time so that the rate limits hold. */
    public void syncAll() {
        Map<UUID, String> all = this.links.all();
        long delay = 0;
        for (Map.Entry<UUID, String> entry : all.entrySet()) {
            final long at = delay;
            this.plugin.getServer().getScheduler().scheduleDelayedTask(this.plugin,
                    () -> this.sync(entry.getKey(), entry.getValue()), (int) at);
            delay += 10;
        }
    }
}

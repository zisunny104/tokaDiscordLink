package dev.toka.discord.link;

import com.google.gson.JsonObject;
import dev.toka.discord.api.IdentityResolver;
import dev.toka.storage.DocumentStore;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Who is linked to whom. A player has at most one Discord account and a Discord account at most one player.
 * <p>
 * The links are kept in a {@link DocumentStore} of tokaStorage (SQLite, JSON or YAML) and in memory. A link is
 * written to the store first, and only then does it count.
 */
public final class LinkService implements IdentityResolver, AutoCloseable {

    public enum Result {
        /** The link was made. */
        LINKED,
        /** The two were already linked to each other. */
        ALREADY_LINKED,
        /** The player is linked to another Discord account. */
        PLAYER_HAS_OTHER,
        /** The Discord account is linked to another player. */
        DISCORD_HAS_OTHER,
        /** The link could not be saved, nothing changed. */
        STORAGE_FAILED
    }

    private static final int SCHEMA = 1;

    private final DocumentStore store;
    private final LongSupplier clock;
    private final PendingCodes codes;

    private final Map<UUID, String> discordOfPlayer = new HashMap<>();
    private volatile java.util.Set<UUID> boundPlayers = java.util.Set.of();
    /** Lock-free immutable binding-presence view; contains no Discord identifiers. */
    private volatile java.util.function.BiConsumer<UUID, Boolean> changed = (player, linked) -> { };

    /** Told after a link is made (true) or removed (false). */
    public void onChange(java.util.function.BiConsumer<UUID, Boolean> listener) { this.changed = listener; }

    public boolean hasBinding(UUID player) { return boundPlayers.contains(player); }
    private final Map<String, UUID> playerOfDiscord = new HashMap<>();
    /** The last name a linked player was seen with. Display only, never used to find a link. */
    private final Map<UUID, String> lastNames = new HashMap<>();
    private final Map<UUID, Long> linkedAt = new HashMap<>();

    public LinkService(DocumentStore store, LongSupplier clock, PendingCodes codes) throws IOException {
        this.store = store;
        this.clock = clock;
        this.codes = codes;
        for (Map.Entry<String, JsonObject> entry : store.snapshot().entrySet()) {
            JsonObject json = entry.getValue();
            if (!json.has("schema") || json.get("schema").getAsInt() > SCHEMA) {
                throw new IOException("Unknown link schema in " + entry.getKey());
            }
            UUID player = UUID.fromString(json.get("player").getAsString());
            String discord = json.get("discord").getAsString();
            if (!entry.getKey().equals(player.toString())
                    || this.discordOfPlayer.putIfAbsent(player, discord) != null
                    || this.playerOfDiscord.putIfAbsent(discord, player) != null) {
                throw new IOException("Conflicting links in the store: " + entry.getKey());
            }
            this.linkedAt.put(player, json.has("linkedAt") ? json.get("linkedAt").getAsLong() : 0L);
            if (json.has("name") && !json.get("name").isJsonNull() && !json.get("name").getAsString().isBlank()) {
                this.lastNames.put(player, json.get("name").getAsString());
            }
        }
        this.boundPlayers = java.util.Set.copyOf(this.discordOfPlayer.keySet());
    }


    @Override
    public void close() throws IOException {
        this.store.close();
    }

    public PendingCodes codes() {
        return this.codes;
    }

    @Override
    public synchronized Optional<UUID> playerOf(String discordUserId) {
        return Optional.ofNullable(this.playerOfDiscord.get(discordUserId));
    }

    @Override
    public synchronized Optional<String> discordUserOf(UUID playerId) {
        return Optional.ofNullable(this.discordOfPlayer.get(playerId));
    }

    /** @return the last known name of a linked player, for display only */
    public synchronized Optional<String> lastNameOf(UUID playerId) {
        return Optional.ofNullable(this.lastNames.get(playerId));
    }

    /** Most names the offline name cache holds; when it is full it is cleared, which only costs new lookups. */
    public static final int NAME_CACHE_LIMIT = 2048;
    private final ConcurrentHashMap<UUID, String> nameCache = new ConcurrentHashMap<>();

    /** The cached name of a player, loaded with {@code loader} (an offline lookup, so off the server thread) when missing. */
    public String cachedName(UUID player, Function<UUID, String> loader) {
        String name = this.nameCache.get(player);
        if (name == null) {
            name = loader.apply(player);
            if (this.nameCache.size() >= NAME_CACHE_LIMIT) {
                this.nameCache.clear();
            }
            this.nameCache.put(player, name);
        }
        return name;
    }

    /** A player logged in with this name: refreshes a cached name, then remembers the name of a linked player. */
    public boolean onLogin(UUID player, String name) {
        if (name != null && !name.isBlank()) {
            this.nameCache.computeIfPresent(player, (id, old) -> name);
        }
        return this.updateName(player, name);
    }

    /**
     * Remembers the name a linked player has now. It writes to the store only when the name changed, so call it
     * off the server thread.
     *
     * @return true when the name was saved
     */
    public synchronized boolean updateName(UUID player, String name) {
        String discord = this.discordOfPlayer.get(player);
        if (discord == null || name == null || name.isBlank() || name.equals(this.lastNames.get(player))) {
            return false;
        }
        JsonObject json = this.document(player, discord, name, this.linkedAt.getOrDefault(player, 0L));
        try {
            this.store.save(player.toString(), json);
        } catch (IOException e) {
            return false;
        }
        this.lastNames.put(player, name);
        return true;
    }

    private JsonObject document(UUID player, String discord, String name, long linkedAt) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", SCHEMA);
        json.addProperty("player", player.toString());
        json.addProperty("discord", discord);
        json.addProperty("linkedAt", linkedAt);
        if (name != null && !name.isBlank()) {
            json.addProperty("name", name);
        }
        return json;
    }

    public synchronized int count() {
        return this.discordOfPlayer.size();
    }

    /** A copy of every link, player to Discord user. */
    public synchronized Map<UUID, String> all() {
        return new HashMap<>(this.discordOfPlayer);
    }

    /**
     * Links a player and a Discord user. An existing link of either of them is never replaced silently: the
     * old link has to be removed with {@link #unlinkPlayer(UUID)} first, which is why changing a link always means
     * a new code.
     */
    public synchronized Result link(UUID player, String discordUserId) {
        return this.link(player, discordUserId, null);
    }

    /** Same as {@link #link(UUID, String)}, and keeps the name the player has now (display only, may be null). */
    public synchronized Result link(UUID player, String discordUserId, String name) {
        String current = this.discordOfPlayer.get(player);
        if (current != null) {
            return current.equals(discordUserId) ? Result.ALREADY_LINKED : Result.PLAYER_HAS_OTHER;
        }
        if (this.playerOfDiscord.containsKey(discordUserId)) {
            return Result.DISCORD_HAS_OTHER;
        }

        long now = this.clock.getAsLong();
        JsonObject json = this.document(player, discordUserId, name, now);
        try {
            this.store.save(player.toString(), json);
        } catch (IOException e) {
            return Result.STORAGE_FAILED;
        }

        this.discordOfPlayer.put(player, discordUserId);
        this.boundPlayers = java.util.Set.copyOf(this.discordOfPlayer.keySet());
        this.playerOfDiscord.put(discordUserId, player);
        this.linkedAt.put(player, now);
        if (name != null && !name.isBlank()) {
            this.lastNames.put(player, name);
        }
        this.codes.cancel(PendingCodes.Side.GAME, player.toString());
        this.codes.cancel(PendingCodes.Side.DISCORD, discordUserId);
        this.changed.accept(player, true);
        return Result.LINKED;
    }

    /** @return true when the player was linked, and the link is gone */
    public synchronized boolean unlinkPlayer(UUID player) {
        String discord = this.discordOfPlayer.get(player);
        if (discord == null) {
            return false;
        }
        try {
            this.store.delete(player.toString());
        } catch (IOException e) {
            return false;
        }
        this.discordOfPlayer.remove(player);
        this.boundPlayers = java.util.Set.copyOf(this.discordOfPlayer.keySet());
        this.playerOfDiscord.remove(discord);
        this.lastNames.remove(player);
        this.linkedAt.remove(player);
        this.changed.accept(player, false);
        return true;
    }

    /** @return true when the Discord user was linked, and the link is gone */
    public synchronized boolean unlinkDiscord(String discordUserId) {
        UUID player = this.playerOfDiscord.get(discordUserId);
        return player != null && this.unlinkPlayer(player);
    }

    /**
     * Brings a code made by the other side to its end.
     *
     * @return the outcome of the code, and when it was right, the result of the link
     */
    public Confirmation confirm(PendingCodes.Side typedBy, String typer, String code) {
        PendingCodes.Match[] match = new PendingCodes.Match[1];
        PendingCodes.Outcome outcome = this.codes.confirm(typedBy, typer, code, match);
        if (outcome != PendingCodes.Outcome.OK) {
            return new Confirmation(outcome, null, null);
        }
        return new Confirmation(outcome, match[0], this.link(match[0].player(), match[0].discordUser()));
    }

    /** What typing a code led to. */
    public record Confirmation(PendingCodes.Outcome outcome, PendingCodes.Match match, Result result) {
    }
}

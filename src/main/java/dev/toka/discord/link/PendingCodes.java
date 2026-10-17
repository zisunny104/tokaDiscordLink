package dev.toka.discord.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The one-time codes of the linking. A code is made by one side (a player in the game, or a Discord user) and
 * typed in by the other side, which proves that the same person is behind both accounts.
 * <p>
 * Only the hash of a code is kept, the codes are kept in memory only, they run out, and whoever types a wrong code
 * too often is locked out for a while. A side has one open code, a new one replaces the old one.
 */
public final class PendingCodes {

    /** Without the characters that look like each other (0 O, 1 I L). */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 6;

    /** The side that made the code. */
    public enum Side { GAME, DISCORD }

    /** Who a code is for: the player (GAME) or the Discord user (DISCORD) that made it. */
    private record Entry(Side side, String owner, long expires) {
    }

    public enum Outcome { OK, WRONG_OR_EXPIRED, LOCKED }

    /** What a correct code gives: the player and the Discord user that belong together. */
    public record Match(UUID player, String discordUser) {
    }

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private final long ttlMillis;
    private final int maxAttempts;
    private final long lockoutMillis;

    private final Map<String, Entry> byHash = new HashMap<>();
    private final Map<String, String> hashOfOwner = new HashMap<>();
    private final Map<String, int[]> failures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();

    public PendingCodes(LongSupplier clock, long ttlSeconds, int maxAttempts, long lockoutSeconds) {
        if (ttlSeconds < 1 || maxAttempts < 1 || lockoutSeconds < 1) {
            throw new IllegalArgumentException("The times and the number of attempts must be positive");
        }
        this.clock = clock;
        this.ttlMillis = ttlSeconds * 1000;
        this.maxAttempts = maxAttempts;
        this.lockoutMillis = lockoutSeconds * 1000;
    }

    /** How long a locked-out owner must wait, in seconds. */
    public long lockoutSeconds() {
        return this.lockoutMillis / 1000;
    }

    private static String ownerKey(Side side, String owner) {
        return side + ":" + owner;
    }

    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void expire() {
        long now = this.clock.getAsLong();
        for (Iterator<Map.Entry<String, Entry>> it = this.byHash.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().expires() <= now) {
                this.hashOfOwner.remove(ownerKey(e.getValue().side(), e.getValue().owner()), e.getKey());
                it.remove();
            }
        }
        this.lockedUntil.values().removeIf(until -> until <= now);
        // failure counters have no expiry of their own; a bound keeps a flood of ids from growing them forever
        if (this.failures.size() > 10_000) {
            this.failures.clear();
        }
    }

    /**
     * Makes a code for a player or a Discord user. The previous code of the same side and owner stops working.
     *
     * @param owner the UUID of the player (GAME) or the id of the Discord user (DISCORD)
     * @return the code to show, it is not kept anywhere
     */
    public synchronized String issue(Side side, String owner) {
        this.expire();
        String key = ownerKey(side, owner);
        String old = this.hashOfOwner.remove(key);
        if (old != null) {
            this.byHash.remove(old);
        }

        String code;
        String hash;
        do {
            StringBuilder builder = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                builder.append(ALPHABET.charAt(this.random.nextInt(ALPHABET.length())));
            }
            code = builder.toString();
            hash = hash(code);
        } while (this.byHash.containsKey(hash));

        this.byHash.put(hash, new Entry(side, owner, this.clock.getAsLong() + this.ttlMillis));
        this.hashOfOwner.put(key, hash);
        return code;
    }

    /**
     * A code made by the other side is typed in.
     *
     * @param typedBy the side of the one who types: GAME means a player typed a code that a Discord user made
     * @param typer   the UUID of the player or the id of the Discord user who types
     * @param code    the typed code
     * @param match   filled with who belongs together when the code is right
     */
    public synchronized Outcome confirm(Side typedBy, String typer, String code, Match[] match) {
        this.expire();
        String who = ownerKey(typedBy, typer);
        if (this.lockedUntil.containsKey(who)) {
            return Outcome.LOCKED;
        }

        String hash = hash(code == null ? "" : code.trim());
        Entry entry = this.byHash.get(hash);
        // a code only works for the other side
        if (entry == null || entry.side() == typedBy) {
            int[] count = this.failures.computeIfAbsent(who, ignored -> new int[1]);
            if (++count[0] >= this.maxAttempts) {
                this.failures.remove(who);
                this.lockedUntil.put(who, this.clock.getAsLong() + this.lockoutMillis);
                return Outcome.LOCKED;
            }
            return Outcome.WRONG_OR_EXPIRED;
        }

        this.byHash.remove(hash);
        this.hashOfOwner.remove(ownerKey(entry.side(), entry.owner()), hash);
        this.failures.remove(who);
        match[0] = typedBy == Side.GAME
                ? new Match(UUID.fromString(typer), entry.owner())
                : new Match(UUID.fromString(entry.owner()), typer);
        return Outcome.OK;
    }

    /** Forgets the open code of a side, for example when the account is linked another way. */
    public synchronized void cancel(Side side, String owner) {
        String hash = this.hashOfOwner.remove(ownerKey(side, owner));
        if (hash != null) {
            this.byHash.remove(hash);
        }
    }
}

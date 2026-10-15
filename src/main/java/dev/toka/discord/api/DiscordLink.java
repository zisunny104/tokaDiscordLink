package dev.toka.discord.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What other plugins use of tokaDiscordLink. Every method is safe to call when Discord is not set up or not ready
 * yet: the message is dropped and nothing else happens, the same as with the plugin "one".
 * <p>
 * It is called through the class name, so a plugin that only wants Discord as an option can check
 * that the plugin is enabled before it touches this class.
 */
public final class DiscordLink {

    /** The channel of the chat. */
    public static final String MSG_CHANNEL = "msg";

    /** The channel of the command results. */
    public static final String CMD_CHANNEL = "cmd";

    /** The colour of an embed when none is given. */
    public static final String DEFAULT_COLOR = "#E7E7E7";

    /** What the plugin gives to this class so that the methods can reach the bot. */
    public interface Transport {
        boolean isReady();

        void sendMessage(String text, String channel);

        void sendEmbed(String text, String color, String channel);

        void deleteMessage(String messageId, String channel);
    }

    /** What importing an old link led to. */
    public enum ImportResult {
        /** The link was made. */
        IMPORTED,
        /** The two were already linked to each other. */
        ALREADY_LINKED,
        /** Either of them is linked to someone else, nothing was changed. */
        CONFLICT,
        /** The id is not a Discord user id, or linking is not available, nothing was changed. */
        REJECTED
    }

    /** Provided by tokaDiscordLink itself, for the migration of links that an older plugin kept. */
    public interface LinkImporter {
        ImportResult importLink(UUID player, String discordUserId);
    }

    private static volatile Transport transport;
    private static volatile IdentityResolver identityResolver;
    private static volatile LinkImporter importer;

    private DiscordLink() {
    }

    /** Called by tokaDiscordLink itself. */
    public static void register(Transport transport) {
        DiscordLink.transport = transport;
    }

    /** Called by tokaDiscordLink itself when it is disabled. */
    public static void unregister(Transport transport) {
        if (DiscordLink.transport == transport) {
            DiscordLink.transport = null;
        }
    }

    public static boolean isReady() {
        Transport current = transport;
        return current != null && current.isReady();
    }

    public static void sendMessage(String text) {
        sendMessage(text, MSG_CHANNEL);
    }

    public static void sendMessage(String text, String channel) {
        Transport current = transport;
        if (current != null && text != null) {
            current.sendMessage(text, channel);
        }
    }

    public static void sendEmbedMessage(String text) {
        sendEmbedMessage(text, DEFAULT_COLOR, MSG_CHANNEL);
    }

    public static void sendEmbedMessage(String text, String color) {
        sendEmbedMessage(text, color, MSG_CHANNEL);
    }

    public static void sendEmbedMessage(String text, String color, String channel) {
        Transport current = transport;
        if (current != null && text != null) {
            current.sendEmbed(text, color, channel);
        }
    }

    public static void deleteMessage(String messageId) {
        deleteMessage(messageId, MSG_CHANNEL);
    }

    public static void deleteMessage(String messageId, String channel) {
        Transport current = transport;
        if (current != null && messageId != null) {
            current.deleteMessage(messageId, channel);
        }
    }

    /**
     * Registers who answers the questions "which player is this Discord user" and the other way round. There is
     * one resolver; registering another one replaces it.
     */
    public static void setIdentityResolver(IdentityResolver resolver) {
        identityResolver = resolver;
    }

    public static Optional<IdentityResolver> getIdentityResolver() {
        return Optional.ofNullable(identityResolver);
    }

    /** Called by tokaDiscordLink itself. */
    public static void setLinkImporter(LinkImporter importer) {
        DiscordLink.importer = importer;
    }

    /**
     * Links a player to a Discord account that an older plugin already had linked. It never replaces a link that
     * exists, so a conflict is reported back and the data of the old plugin has to be sorted out by a person.
     * A new link is made with the codes, not with this.
     */
    public static ImportResult importLink(UUID player, String discordUserId) {
        LinkImporter current = importer;
        return current == null || player == null || discordUserId == null
                ? ImportResult.REJECTED : current.importLink(player, discordUserId);
    }
}

package dev.toka.discord.event;

import cn.nukkit.event.Cancellable;
import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;
import dev.toka.discord.api.DiscordLink;

import java.util.Optional;
import java.util.UUID;

/**
 * A chat message written in the Discord message channel. It is called on the server thread.
 */
public class BotMessageEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    private final String message;
    private final String messageId;
    private final String senderId;
    private final String senderName;
    private boolean cancelled;

    public BotMessageEvent(String message, String messageId, String senderId, String senderName) {
        this.message = message;
        this.messageId = messageId;
        this.senderId = senderId;
        this.senderName = senderName;
    }

    public static HandlerList getHandlers() {
        return handlers;
    }

    public String getMsg() {
        return this.message;
    }

    public String getMessageId() {
        return this.messageId;
    }

    /** The id of the Discord user. */
    public String getSenderId() {
        return this.senderId;
    }

    /** The name the Discord user has on the server. It can be changed by the user, do not use it to find a player. */
    public String getSenderName() {
        return this.senderName;
    }

    /** The same as {@link #getSenderId()}, the name it had in the plugin "one". */
    public String getSender() {
        return this.senderId;
    }

    /** The same as {@link #getSenderName()}, the name it had in the plugin "one". */
    public String getSenderRawName() {
        return this.senderName;
    }

    /** The player the Discord user is linked to, when an identity resolver is registered and knows the user. */
    public Optional<UUID> getLinkedPlayer() {
        return DiscordLink.getIdentityResolver().flatMap(resolver -> resolver.playerOf(this.senderId));
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}

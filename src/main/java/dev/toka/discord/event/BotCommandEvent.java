package dev.toka.discord.event;

import cn.nukkit.event.Event;
import cn.nukkit.event.HandlerList;
import dev.toka.discord.api.DiscordLink;

import java.util.Optional;
import java.util.UUID;

/**
 * A command written in the Discord message channel, after the prefix. It is called on the server thread.
 * <p>
 * A plugin that carries out the command calls {@link #setHandled(boolean)}. When nobody did, the sender gets the
 * answer for an unknown command. (The plugin "one" used the cancel flag for this, but it never worked.)
 */
public class BotCommandEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final String senderName;
    private final String senderId;
    private final String[] command;
    private boolean handled;

    public BotCommandEvent(String senderName, String senderId, String[] command) {
        this.senderName = senderName;
        this.senderId = senderId;
        this.command = command.clone();
    }

    public static HandlerList getHandlers() {
        return handlers;
    }

    /** The name the Discord user has on the server. It can be changed by the user, do not use it to find a player. */
    public String getSenderName() {
        return this.senderName;
    }

    /** The id of the Discord user. */
    public String getSenderId() {
        return this.senderId;
    }

    /** The words of the command, the first one is the command itself. */
    public String[] getCommand() {
        return this.command.clone();
    }

    public boolean isHandled() {
        return this.handled;
    }

    public void setHandled(boolean handled) {
        this.handled = handled;
    }

    /** The player the Discord user is linked to, when an identity resolver is registered and knows the user. */
    public Optional<UUID> getLinkedPlayer() {
        return DiscordLink.getIdentityResolver().flatMap(resolver -> resolver.playerOf(this.senderId));
    }
}

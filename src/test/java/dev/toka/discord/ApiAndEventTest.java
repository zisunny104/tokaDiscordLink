package dev.toka.discord;

import dev.toka.discord.api.DiscordLink;
import dev.toka.discord.api.IdentityResolver;
import dev.toka.discord.event.BotCommandEvent;
import dev.toka.discord.event.BotMessageEvent;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.*;

public class ApiAndEventTest {

    private static final class Recorder implements DiscordLink.Transport {
        final List<String> calls = new ArrayList<>();
        boolean ready = true;

        @Override
        public boolean isReady() {
            return this.ready;
        }

        @Override
        public void sendMessage(String text, String channel) {
            this.calls.add("message " + channel + " " + text);
        }

        @Override
        public void sendEmbed(String text, String color, String channel) {
            this.calls.add("embed " + channel + " " + color + " " + text);
        }

        @Override
        public void deleteMessage(String messageId, String channel) {
            this.calls.add("delete " + channel + " " + messageId);
        }
    }

    @After
    public void reset() {
        DiscordLink.setIdentityResolver(null);
    }

    @Test
    public void doesNothingWithoutABot() {
        DiscordLink.sendMessage("hi");
        DiscordLink.sendEmbedMessage("hi", "#000000");
        DiscordLink.deleteMessage("1");
        assertFalse(DiscordLink.isReady());
    }

    @Test
    public void passesEverythingToTheTransport() {
        Recorder recorder = new Recorder();
        DiscordLink.register(recorder);
        try {
            assertTrue(DiscordLink.isReady());
            DiscordLink.sendMessage("chat");
            DiscordLink.sendMessage("result", DiscordLink.CMD_CHANNEL);
            DiscordLink.sendEmbedMessage("title");
            DiscordLink.deleteMessage("42");
            DiscordLink.sendMessage(null);
            assertEquals(List.of(
                    "message msg chat",
                    "message cmd result",
                    "embed msg #E7E7E7 title",
                    "delete msg 42"), recorder.calls);
            recorder.ready = false;
            assertFalse(DiscordLink.isReady());
        } finally {
            DiscordLink.unregister(recorder);
        }
        assertFalse(DiscordLink.isReady());
    }

    @Test
    public void anotherTransportIsNotUnregisteredByMistake() {
        Recorder first = new Recorder(), second = new Recorder();
        DiscordLink.register(first);
        DiscordLink.register(second);
        DiscordLink.unregister(first);
        try {
            assertTrue(DiscordLink.isReady());
        } finally {
            DiscordLink.unregister(second);
        }
    }

    @Test
    public void aCommandIsNotHandledUntilSomeoneSaysSo() {
        BotCommandEvent event = new BotCommandEvent("小明", "123", new String[]{"list"});
        assertFalse(event.isHandled());
        event.setHandled(true);
        assertTrue(event.isHandled());
        event.getCommand()[0] = "changed";
        assertEquals("list", event.getCommand()[0]);
    }

    @Test
    public void aMessageCanBeCancelledAndKeepsTheOldNames() {
        BotMessageEvent event = new BotMessageEvent("你好", "9", "123", "小明");
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertEquals("123", event.getSender());
        assertEquals("123", event.getSenderId());
        assertEquals("小明", event.getSenderRawName());
        assertEquals("小明", event.getSenderName());
    }

    @Test
    public void theLinkedPlayerComesFromTheResolverOnly() {
        BotMessageEvent event = new BotMessageEvent("你好", "9", "123", "123");
        assertEquals(Optional.empty(), event.getLinkedPlayer());

        UUID player = UUID.randomUUID();
        DiscordLink.setIdentityResolver(new IdentityResolver() {
            @Override
            public Optional<UUID> playerOf(String discordUserId) {
                return "123".equals(discordUserId) ? Optional.of(player) : Optional.empty();
            }

            @Override
            public Optional<String> discordUserOf(UUID playerId) {
                return player.equals(playerId) ? Optional.of("123") : Optional.empty();
            }
        });
        assertEquals(Optional.of(player), event.getLinkedPlayer());
        // the name is never used to find a player
        assertEquals(Optional.empty(), new BotMessageEvent("你好", "9", "999", "123").getLinkedPlayer());
        assertEquals(Optional.empty(), new BotCommandEvent("123", "999", new String[]{"list"}).getLinkedPlayer());
    }
}

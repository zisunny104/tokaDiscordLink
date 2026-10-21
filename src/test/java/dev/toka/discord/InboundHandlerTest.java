package dev.toka.discord;

import dev.toka.discord.api.DiscordLink;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class InboundHandlerTest {

    @Test
    public void publishesPlainPayloadAndUnregistersOwner() {
        String owner = "test-inbound-handler";
        AtomicReference<String[]> received = new AtomicReference<>();
        try {
            DiscordLink.registerInboundHandler(owner, received::set);
            DiscordLink.publishInboundMessage("00000000-0000-0000-0000-000000000001", "hello", "message-1");
            assertArrayEquals(new String[]{"00000000-0000-0000-0000-000000000001", "hello", "message-1"},
                    received.get());

            DiscordLink.unregisterInboundHandler(owner);
            received.set(null);
            DiscordLink.publishInboundMessage("", "ignored", "message-2");
            assertNull(received.get());
        } finally {
            DiscordLink.unregisterInboundHandler(owner);
        }
    }
}

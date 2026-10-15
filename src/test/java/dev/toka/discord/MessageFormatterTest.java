package dev.toka.discord;

import dev.toka.discord.chat.MessageFormatter;
import org.junit.Test;

import java.time.LocalTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageFormatterTest {

    private static final LocalTime TIME = LocalTime.of(7, 5, 9);

    @Test
    public void removesEveryColorCode() {
        assertEquals("紅色粗體重設", MessageFormatter.stripColors("§c紅色§l粗體§r重設"));
        assertEquals("大寫", MessageFormatter.stripColors("§A大§K寫"));
        assertEquals("沒有顏色", MessageFormatter.stripColors("沒有顏色"));
    }

    @Test
    public void neutralizesMentions() {
        assertEquals("[At]everyone 你好", MessageFormatter.neutralizeMentions("@everyone 你好"));
    }

    @Test
    public void putsTheTimeInFront() {
        assertEquals("07:05:09 ", MessageFormatter.timeCode(TIME));
        assertEquals("07:05:09 [At]小明>>你好", MessageFormatter.chat("§a@小明§8>>§f你好", TIME));
    }

    @Test
    public void shortensAnEmbedTitleToWhatDiscordAccepts() {
        String title = MessageFormatter.embedTitle("x".repeat(400), TIME);
        assertEquals(MessageFormatter.MAX_EMBED_TITLE, title.length());
        assertTrue(title.endsWith("…"));
        assertEquals("07:05:09 **開服**", MessageFormatter.embedTitle("**開服**", TIME));
    }

    @Test
    public void readsAColorAndFallsBack() {
        assertEquals(0x00EB00, MessageFormatter.parseColor("#00EB00", 1));
        assertEquals(0xFFA500, MessageFormatter.parseColor("#FFA500", 1));
        assertEquals(1, MessageFormatter.parseColor("", 1));
        assertEquals(1, MessageFormatter.parseColor(null, 1));
        assertEquals(1, MessageFormatter.parseColor("紅色", 1));
    }
}

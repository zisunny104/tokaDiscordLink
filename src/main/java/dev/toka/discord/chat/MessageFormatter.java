package dev.toka.discord.chat;

import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * Turns a game message into the text that is sent to Discord: the colour codes are removed, the time is put in
 * front of it, and the {@code @} that would ping someone is neutralised.
 */
public final class MessageFormatter {

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)§[0-9a-fk-or]");

    /** The longest title an embed allows. */
    public static final int MAX_EMBED_TITLE = 256;

    private MessageFormatter() {
    }

    public static String stripColors(String text) {
        return COLOR_CODES.matcher(text).replaceAll("");
    }

    public static String neutralizeMentions(String text) {
        return text.replace("@", "[At]");
    }

    public static String timeCode(LocalTime time) {
        return String.format("%02d:%02d:%02d ", time.getHour(), time.getMinute(), time.getSecond());
    }

    /** A chat message: no colours, no mentions, with the time in front. */
    public static String chat(String text, LocalTime now) {
        return timeCode(now) + stripColors(neutralizeMentions(text));
    }

    /** The title of an embed: no colours, with the time in front, shortened to what Discord accepts. */
    public static String embedTitle(String text, LocalTime now) {
        String title = timeCode(now) + stripColors(text);
        if (title.length() > MAX_EMBED_TITLE) {
            title = title.substring(0, MAX_EMBED_TITLE - 1) + "…";
        }
        return title;
    }

    /**
     * @param color a colour as {@code #RRGGBB}
     * @return the colour as a number, or the fallback when the text is empty or not a colour
     */
    public static int parseColor(String color, int fallback) {
        if (color == null || color.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.decode(color) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

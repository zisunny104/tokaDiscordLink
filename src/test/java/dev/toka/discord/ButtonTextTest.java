package dev.toka.discord;

import dev.toka.i18n.Messages;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Button labels carry no colour codes; only a leading red code is allowed (close, cancel). */
public class ButtonTextTest {
    private static final String[] BUTTON_KEYS = {
            "ui.status.link", "ui.status.unlink", "ui.status.close", "ui.status.yes", "ui.status.no",
            "ui.role.button", "ui.role.back", "ui.role.add", "ui.role.sync", "ui.role.entry", "role.unknownRole_btn"};

    @Test public void buttonLabelsHaveNoColourCodesExceptLeadingRed() {
        Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", new ArrayList<String>()::add);
        for (String key : BUTTON_KEYS) {
            String text = messages.text(key, "a", "b", "c");
            String rest = text.startsWith("§c") ? text.substring(2) : text;
            assertFalse(key + " has a colour code: " + text, rest.contains("§"));
        }
    }
}

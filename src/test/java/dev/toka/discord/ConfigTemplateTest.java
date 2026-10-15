package dev.toka.discord;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The two files that are copied to the server must be valid YAML and contain everything the plugin reads. */
public class ConfigTemplateTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String name) throws Exception {
        try (InputStream stream = ConfigTemplateTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name + " is missing", stream);
            return (Map<String, Object>) new Yaml().load(stream);
        }
    }

    @Test
    public void configHasEveryKeyOfThePluginOne() throws Exception {
        Map<String, Object> config = load("config.yml");
        // the keys the plugin "one" had, so that its settings can be copied
        for (String key : List.of("token", "msg-channel-id", "cmd-channel-id", "status", "discord-command-prefix",
                "bot-start-msg", "bot-start-color", "bot-stop-msg", "bot-stop-color")) {
            assertTrue("missing " + key, config.containsKey(key));
        }
        assertTrue(config.containsKey("unknown-command-msg"));
        assertEquals(1, config.get("config-version"));
    }

    @Test
    public void theLinkingSettingsAreThereWithSafeDefaults() throws Exception {
        Map<String, Object> config = load("config.yml");
        Map<?, ?> link = (Map<?, ?>) config.get("link");
        assertEquals(600, link.get("code-ttl-seconds"));
        assertEquals(5, link.get("max-attempts"));
        assertEquals("sqlite", ((Map<?, ?>) config.get("storage")).get("backend"));
        // the nickname is taken over after linking, and nothing happens to Discord roles until they are listed
        assertEquals("set", ((Map<?, ?>) config.get("nickname")).get("mode"));
        assertTrue(((Map<?, ?>) config.get("roles")).isEmpty());
    }

    @Test
    public void theTemplateDoesNotContainASecret() throws Exception {
        Map<String, Object> config = load("config.yml");
        assertEquals("", config.get("token"));
        assertEquals("", config.get("msg-channel-id"));
        assertEquals("", config.get("cmd-channel-id"));
    }

    @Test
    public void pluginDescriptorIsComplete() throws Exception {
        Map<String, Object> plugin = load("plugin.yml");
        assertEquals("tokaDiscordLink", plugin.get("name"));
        assertEquals("dev.toka.discord.Main", plugin.get("main"));
        assertEquals("POSTWORLD", plugin.get("load"));
        assertTrue(((Map<?, ?>) plugin.get("commands")).containsKey("discord"));
        Map<?, ?> permissions = (Map<?, ?>) plugin.get("permissions");
        assertTrue(permissions.containsKey("tokaDiscordLink.admin"));
        assertEquals("op", ((Map<?, ?>) permissions.get("tokaDiscordLink.admin")).get("default"));
        // every player may link their own account
        assertEquals(true, ((Map<?, ?>) permissions.get("tokaDiscordLink.use")).get("default"));
        // the plugin does not need zero or any other plugin
        assertFalse(plugin.containsKey("depend"));
    }
}

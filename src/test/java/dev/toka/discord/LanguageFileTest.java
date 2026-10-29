package dev.toka.discord;

import dev.toka.i18n.Messages;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Every text of the plugin is in lang/zh-TW.yml, and every key that the code asks for is there. */
public class LanguageFileTest {

    private static void flatten(String prefix, Map<?, ?> map, Map<String, Object> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key, child, out);
            } else {
                out.put(key, entry.getValue());
            }
        }
    }

    private static Map<String, Object> keys() throws IOException {
        try (InputStream stream = LanguageFileTest.class.getClassLoader().getResourceAsStream("lang/zh-TW.yml")) {
            assertNotNull("lang/zh-TW.yml is missing", stream);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                flatten("", (Map<?, ?>) new Yaml().load(reader), out);
                return out;
            }
        }
    }

    @Test
    public void everyKeyOfTheFileCanBeRead() throws IOException {
        Map<String, Object> keys = keys();
        assertTrue(keys.size() > 50);
        Messages messages = new Messages(getClass().getClassLoader(), Paths.get("target", "no-lang-folder"), "zh-TW", line -> fail(line));
        for (Map.Entry<String, Object> entry : keys.entrySet()) {
            assertTrue(entry.getKey(), messages.has(entry.getKey()));
            assertFalse(entry.getKey(), messages.lines(entry.getKey(), "a", "b", "c", "d", "e", "f", "g", "h").isEmpty());
        }
    }

    @Test
    public void everyKeyUsedInTheSourceIsInTheFile() throws IOException {
        Map<String, Object> keys = keys();
        Pattern call = Pattern.compile("\\b(?:text|lines)\\(\\s*\"([^\"]+)\"");
        TreeSet<String> missing = new TreeSet<>();
        int found = 0;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get("src", "main", "java"))) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
        }
        for (Path file : files) {
            Matcher matcher = call.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                found++;
                if (!keys.containsKey(matcher.group(1))) {
                    missing.add(matcher.group(1) + " (" + file.getFileName() + ")");
                }
            }
        }
        assertTrue("the scan found no key", found > 30);
        assertTrue("keys missing in lang/zh-TW.yml: " + missing, missing.isEmpty());
    }

    @Test
    public void thePluginSourceHasNoChineseText() throws IOException {
        Pattern han = Pattern.compile("\\p{IsHan}");
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get("src", "main", "java"))) {
            for (Path file : (Iterable<Path>) walk.filter(path -> path.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String code = line.strip();
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        continue;
                    }
                    if (han.matcher(line).find()) {
                        found.add(file.getFileName() + ": " + code);
                    }
                }
            }
        }
        assertTrue("Chinese text in the source: " + found, found.isEmpty());
    }

    @Test
    public void helpOfTheDiscordCommandListsEverySubcommand() throws IOException {
        Messages messages = new Messages(getClass().getClassLoader(), Paths.get("target", "no-lang-folder"), "zh-TW", line -> fail(line));
        String all = String.join("\n", messages.lines("help.discord")) + "\n" + String.join("\n", messages.lines("help.discordAdmin"));
        for (String part : List.of("/discord help", "/discord info", "/discord link", "/discord link <代碼>", "/discord unlink",
                "/discord status", "/discord link <玩家> <Discord使用者ID>", "/discord unlink <玩家>", "/discord info <玩家>")) {
            assertTrue(part, all.contains(part));
        }
    }
}

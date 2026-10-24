package dev.toka.discord;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.window.FormWindow;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Remembers the window each player has open and hands the answer to the code that opened it; closing a window drops it. */
final class Ui implements Listener {
    private record Open(FormWindow window, Consumer<FormResponse> answer) {}
    private final Map<UUID, Open> open = new HashMap<>();

    void show(Player player, FormWindow window, Consumer<FormResponse> answer) {
        open.put(player.getUniqueId(), new Open(window, answer));
        player.showFormWindow(window);
    }

    static ElementButton button(String text, String icon) {
        return icon == null ? new ElementButton(text)
                : new ElementButton(text, new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, icon));
    }

    @EventHandler public void responded(PlayerFormRespondedEvent event) {
        Open current = open.get(event.getPlayer().getUniqueId());
        if (current == null || event.getWindow() != current.window()) return;
        open.remove(event.getPlayer().getUniqueId());
        if (event.wasClosed() || event.getResponse() == null) return;
        current.answer().accept(event.getResponse());
    }

    @EventHandler public void quit(PlayerQuitEvent event) { open.remove(event.getPlayer().getUniqueId()); }
}

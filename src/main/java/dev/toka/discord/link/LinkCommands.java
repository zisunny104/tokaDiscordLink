package dev.toka.discord.link;

import cn.nukkit.Server;
import dev.toka.i18n.Messages;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Discord side of the linking: {@code /link} makes a code or types one that the game made, {@code /unlink}
 * removes the link of the one who writes it. The answers are only seen by that user.
 */
public final class LinkCommands extends ListenerAdapter {

    private final Messages messages;
    private final LinkService links;
    private final MemberSync sync;
    private final int ttlMinutes;
    /** Link storage and offline name lookups are slow, so they stay off the Discord event thread. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "toka-discord-link");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();

    public LinkCommands(Messages messages, LinkService links, MemberSync sync, int ttlSeconds) {
        this.messages = messages;
        this.links = links;
        this.sync = sync;
        this.ttlMinutes = Math.max(1, ttlSeconds / 60);
    }

    /** The names of the slash commands are fixed; only the descriptions come from the language file. */
    public static List<CommandData> definitions(Messages messages) {
        return List.of(
                Commands.slash("link", messages.text("discord.cmd.link.desc"))
                        .addOption(OptionType.STRING, "code", messages.text("discord.cmd.link.code"), false),
                Commands.slash("unlink", messages.text("discord.cmd.unlink.desc")));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        this.worker.execute(() -> this.handle(event));
    }

    private void handle(SlashCommandInteractionEvent event) {
        String user = event.getUser().getId();
        switch (event.getName()) {
            case "link": {
                String code = event.getOption("code") == null ? null : event.getOption("code").getAsString();
                reply(event, code == null || code.isBlank() ? this.request(user) : this.confirm(user, code));
                break;
            }
            case "unlink":
                reply(event, this.links.unlinkDiscord(user)
                        ? this.messages.text("discord.unlinked") : this.messages.text("discord.notLinked"));
                break;
            default:
                break;
        }
    }

    private static void reply(SlashCommandInteractionEvent event, String text) {
        event.reply(text).setEphemeral(true).queue(null, error -> { });
    }

    private String request(String user) {
        if (this.links.playerOf(user).isPresent()) {
            UUID player = this.links.playerOf(user).get();
            return this.messages.text("discord.alreadyLinked", this.nameOf(player));
        }
        String code = this.links.codes().issue(PendingCodes.Side.DISCORD, user);
        return this.messages.text("discord.codeIssued", code, this.ttlMinutes);
    }

    private String confirm(String user, String code) {
        LinkService.Confirmation result = this.links.confirm(PendingCodes.Side.DISCORD, user, code);
        switch (result.outcome()) {
            case LOCKED:
                return this.messages.text("discord.locked", this.links.codes().lockoutSeconds());
            case WRONG_OR_EXPIRED:
                return this.messages.text("discord.wrongCode");
            default:
                return this.describe(result);
        }
    }

    /** The answer after a right code. */
    private String describe(LinkService.Confirmation result) {
        UUID player = result.match().player();
        switch (result.result()) {
            case LINKED:
                // This runs on the worker thread, so saving the name here is fine
                this.links.updateName(player, this.nameOf(player));
                if (this.sync != null) {
                    this.sync.sync(player, result.match().discordUser());
                }
                return this.messages.text("discord.linked", this.nameOf(player));
            case ALREADY_LINKED:
                return this.messages.text("discord.already");
            case PLAYER_HAS_OTHER:
                return this.messages.text("discord.playerHasOther");
            case DISCORD_HAS_OTHER:
                return this.messages.text("discord.discordHasOther");
            default:
                return this.messages.text("discord.saveFailed");
        }
    }

    private String nameOf(UUID player) {
        return this.names.computeIfAbsent(player, id -> {
            String name = Server.getInstance().getOfflinePlayer(id).getName();
            return name == null ? id.toString() : name;
        });
    }
}

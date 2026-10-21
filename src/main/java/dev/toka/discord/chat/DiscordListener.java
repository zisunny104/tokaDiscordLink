package dev.toka.discord.chat;

import cn.nukkit.utils.Config;
import dev.toka.discord.Main;
import dev.toka.discord.api.DiscordLink;
import dev.toka.discord.event.BotCommandEvent;
import dev.toka.discord.event.BotMessageEvent;
import dev.toka.discord.link.MemberSync;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * What is written in the message channel: a message that starts with the command prefix becomes a
 * {@link BotCommandEvent}, every other message a {@link BotMessageEvent}. Both are called on the server thread.
 */
final class DiscordListener extends ListenerAdapter {

    private final Main plugin;
    private final DiscordService service;
    private final MemberSync sync;
    private final String prefix;
    private final String unknownCommand;

    DiscordListener(Main plugin, DiscordService service, Config config, MemberSync sync) {
        this.plugin = plugin;
        this.service = service;
        this.sync = sync;
        this.prefix = config.getString("discord-command-prefix", "/");
        this.unknownCommand = config.getString("unknown-command-msg", plugin.messages().text("discord.unknownCommand"));
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!this.service.isReady() || !event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        if (!this.service.isMessageChannel(event.getChannel().getId())) {
            return;
        }
        Member member = event.getMember();
        if (member == null) {
            return;
        }

        // with nickname.mode: enforce a nickname that was changed is put back
        this.sync.onMemberSeen(member);

        String id = member.getId();
        // a Discord user must not be able to type colour codes into the game chat
        String name = member.getEffectiveName().replace("§", "");
        String message = event.getMessage().getContentStripped().replace("§", "");
        String messageId = event.getMessageId();
        if (message.isEmpty()) {
            return;
        }

        this.plugin.getServer().getScheduler().scheduleTask(this.plugin, () -> this.handle(id, name, message, messageId));
    }

    private void handle(String id, String name, String message, String messageId) {
        if (!this.prefix.isEmpty() && message.startsWith(this.prefix)) {
            String command = message.substring(this.prefix.length()).trim();
            if (command.isEmpty()) {
                return;
            }
            BotCommandEvent event = new BotCommandEvent(name, id, command.split("\\s+"));
            this.plugin.getServer().getPluginManager().callEvent(event);
            if (!event.isHandled()) {
                DiscordLink.sendMessage(this.unknownCommand.replace("%name", name).replace("%msg", command));
            }
            DiscordLink.deleteMessage(messageId);
        } else {
            BotMessageEvent botMessage = new BotMessageEvent(message, messageId, id, name);
            this.plugin.getServer().getPluginManager().callEvent(botMessage);
            if (!botMessage.isCancelled()) {
                String linkedPlayer = botMessage.getLinkedPlayer().map(Object::toString).orElse("");
                DiscordLink.publishInboundMessage(linkedPlayer, message, messageId);
            }
        }
    }
}

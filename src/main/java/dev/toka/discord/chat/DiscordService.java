package dev.toka.discord.chat;

import cn.nukkit.utils.Config;
import dev.toka.discord.Main;
import dev.toka.discord.api.DiscordLink;
import dev.toka.discord.link.LinkCommands;
import dev.toka.discord.link.LinkService;
import dev.toka.discord.link.MemberSync;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageRequest;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * The bot: it connects to Discord in the background, sends and deletes messages, and passes what is written in
 * the message channel on to the server (see {@link DiscordListener}).
 */
public final class DiscordService implements DiscordLink.Transport {

    private static final int DEFAULT_COLOR = 0xE7E7E7;
    private static final int MAX_TEXT = 2000;

    private final Main plugin;
    private volatile long lastDeleteWarning;
    private final LinkService links;
    private final MemberSync sync;
    private volatile JDA jda;
    private volatile boolean ready;
    private volatile boolean stopping;
    private volatile TextChannel msgChannel;
    private volatile TextChannel cmdChannel;
    private volatile String failure;

    public DiscordService(Main plugin, LinkService links, MemberSync sync) {
        this.plugin = plugin;
        this.links = links;
        this.sync = sync;
    }

    @Override
    public boolean isReady() {
        return this.ready && !this.stopping;
    }

    /** Why the bot is not ready, or null. */
    public String getFailure() {
        return this.failure;
    }

    public String describeChannel(String channel) {
        TextChannel textChannel = channel.equals(DiscordLink.CMD_CHANNEL) ? this.cmdChannel : this.msgChannel;
        return textChannel == null ? this.plugin.messages().text("status.channelMissing") : "#" + textChannel.getName();
    }

    /** Connects in the background, so that the server does not wait for Discord. */
    public void start(Config config) {
        this.stopping = false;
        this.failure = null;
        String token = config.getString("token", "").trim();
        Thread worker = new Thread(() -> this.connect(config, token), "tokaDiscordLink-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void connect(Config config, String token) {
        try {
            // JDA looks for its SLF4J provider through the context class loader, which on the server is not the plugin's
            Thread.currentThread().setContextClassLoader(DiscordService.class.getClassLoader());
            // Nobody on the server is pinged by what the game sends
            MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));

            JDABuilder builder = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT);
            String status = config.getString("status", "");
            if (!status.isEmpty()) {
                builder.setActivity(Activity.playing(status));
            }
            JDA client = builder.build();
            this.jda = client;
            if (this.stopping) {
                client.shutdownNow();
                return;
            }

            client.awaitReady();
            if (this.stopping) {
                client.shutdownNow();
                return;
            }

            this.msgChannel = find(client, config.getString("msg-channel-id", ""), "msg-channel-id");
            this.cmdChannel = find(client, config.getString("cmd-channel-id", ""), "cmd-channel-id");
            client.addEventListener(new DiscordListener(this.plugin, this, config, this.sync));
            this.registerLinkCommands(client, config);
            this.ready = true;
            this.sendEmbed(config.getString("bot-start-msg", ""), config.getString("bot-start-color", ""), DiscordLink.MSG_CHANNEL);
            this.plugin.getLogger().info(this.plugin.messages().text("log.connected", client.getSelfUser().getName()));
        } catch (Exception | LinkageError e) {
            this.ready = false;
            this.failure = e.getMessage() == null ? e.getClass().getSimpleName()
                    : this.plugin.messages().text("status.failure", e.getClass().getSimpleName(), e.getMessage());
            JDA client = this.jda;
            if (client != null) {
                client.shutdownNow();
            }
            String hint = this.failure.contains("ntent")
                    ? this.plugin.messages().text("status.intentHint") : "";
            this.plugin.getLogger().warning(this.plugin.messages().text("log.notReady", this.failure, hint));
        }
    }

    /** The slash commands of the linking, in the Discord server of the chat channel, where they appear at once. */
    private void registerLinkCommands(JDA client, Config config) {
        TextChannel channel = this.msgChannel;
        if (channel == null) {
            this.plugin.getLogger().warning(this.plugin.messages().text("log.noChannelForCommands"));
            return;
        }
        this.sync.attach(client, channel.getGuild().getId());
        client.addEventListener(new LinkCommands(this.plugin.messages(), this.links, this.sync, config.getInt("link.code-ttl-seconds", 600)));
        channel.getGuild().updateCommands().addCommands(LinkCommands.definitions(this.plugin.messages()))
                .queue(null, error -> this.plugin.getLogger().warning(this.plugin.messages().text("log.registerFailed", error.getMessage())));
    }

    private TextChannel find(JDA client, String id, String key) {
        if (id == null || id.isBlank()) {
            return null;
        }
        TextChannel channel = client.getTextChannelById(id.trim());
        if (channel == null) {
            this.plugin.getLogger().warning(this.plugin.messages().text("log.channelNotFound", key));
        }
        return channel;
    }

    public void stop(Config config) {
        try {
            if (this.isReady() && config != null) {
                this.sendEmbed(config.getString("bot-stop-msg", ""), config.getString("bot-stop-color", ""), DiscordLink.MSG_CHANNEL);
            }
        } finally {
            this.stopping = true;
            this.ready = false;
            JDA client = this.jda;
            if (client != null) {
                client.shutdown();
                try {
                    if (!client.awaitShutdown(5, TimeUnit.SECONDS)) {
                        client.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    client.shutdownNow();
                }
            }
            this.msgChannel = null;
            this.cmdChannel = null;
        }
    }

    public boolean isMessageChannel(String channelId) {
        TextChannel channel = this.msgChannel;
        return channel != null && channel.getId().equals(channelId);
    }

    private TextChannel channelOf(String channel) {
        return DiscordLink.CMD_CHANNEL.equals(channel) ? this.cmdChannel : this.msgChannel;
    }

    @Override
    public void sendMessage(String text, String channel) {
        TextChannel target = this.channelOf(channel);
        if (!this.isReady() || target == null) {
            return;
        }
        String formatted = MessageFormatter.chat(text, LocalTime.now());
        if (formatted.length() > MAX_TEXT) {
            formatted = formatted.substring(0, MAX_TEXT - 1) + "…";
        }
        target.sendMessage(formatted).queue(null, error -> { });
    }

    @Override
    public void sendEmbed(String text, String color, String channel) {
        TextChannel target = this.channelOf(channel);
        if (!this.isReady() || target == null || text == null || text.isEmpty()) {
            return;
        }
        target.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(MessageFormatter.embedTitle(text, LocalTime.now()))
                .setColor(MessageFormatter.parseColor(color, DEFAULT_COLOR))
                .build()).queue(null, error -> { });
    }

    @Override
    public void deleteMessage(String messageId, String channel) {
        TextChannel target = this.channelOf(channel);
        if (this.isReady() && target != null) {
            target.deleteMessageById(messageId).queue(null, error -> {
                // the reason is shown once per minute at most: a missing "Manage Messages" permission would repeat on every line
                long now = System.currentTimeMillis();
                if (now - lastDeleteWarning > 60_000L) {
                    lastDeleteWarning = now;
                    this.plugin.getLogger().warning(this.plugin.messages().text("log.deleteFailed", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                }
            });
        } else {
            this.plugin.getLogger().warning(this.plugin.messages().text("log.deleteNotReady"));
        }
    }
}

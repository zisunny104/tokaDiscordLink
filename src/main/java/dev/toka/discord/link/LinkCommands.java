package dev.toka.discord.link;

import cn.nukkit.Server;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;
import java.util.UUID;

/**
 * The Discord side of the linking: {@code /link} makes a code or types one that the game made, {@code /unlink}
 * removes the link of the one who writes it. The answers are only seen by that user.
 */
public final class LinkCommands extends ListenerAdapter {

    private final LinkService links;
    private final MemberSync sync;
    private final int ttlMinutes;

    public LinkCommands(LinkService links, MemberSync sync, int ttlSeconds) {
        this.links = links;
        this.sync = sync;
        this.ttlMinutes = Math.max(1, ttlSeconds / 60);
    }

    public static List<CommandData> definitions() {
        return List.of(
                Commands.slash("link", "把你的 Discord 帳號綁定到遊戲帳號")
                        .addOption(OptionType.STRING, "code", "遊戲裡產生的代碼；不填就產生一個給遊戲輸入的代碼", false),
                Commands.slash("unlink", "解除你的 Discord 帳號與遊戲帳號的綁定"));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String user = event.getUser().getId();
        switch (event.getName()) {
            case "link": {
                String code = event.getOption("code") == null ? null : event.getOption("code").getAsString();
                reply(event, code == null || code.isBlank() ? this.request(user) : this.confirm(user, code));
                break;
            }
            case "unlink":
                reply(event, this.links.unlinkDiscord(user) ? "已解除綁定。要再綁定需要重新驗證。" : "你目前沒有綁定任何遊戲帳號。");
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
            return "你已經綁定遊戲帳號：" + nameOf(player) + "。要換的話請先 /unlink。";
        }
        String code = this.links.codes().issue(PendingCodes.Side.DISCORD, user);
        return "你的綁定代碼：**" + code + "**（" + this.ttlMinutes + " 分鐘內有效，只能用一次）。\n"
                + "請在遊戲裡輸入：/discord link " + code + "\n不要把代碼給任何人。";
    }

    private String confirm(String user, String code) {
        LinkService.Confirmation result = this.links.confirm(PendingCodes.Side.DISCORD, user, code);
        switch (result.outcome()) {
            case LOCKED:
                return "輸入錯誤太多次，請稍後再試。";
            case WRONG_OR_EXPIRED:
                return "代碼不正確或已經過期。請在遊戲裡重新輸入 /discord link 取得新的代碼。";
            default:
                return describe(result, user, this.sync);
        }
    }

    /** The answer after a right code, also used by the game side. */
    static String describe(LinkService.Confirmation result, String discordUser, MemberSync sync) {
        UUID player = result.match().player();
        switch (result.result()) {
            case LINKED:
                if (sync != null) {
                    sync.sync(player, result.match().discordUser());
                }
                return "綁定完成：" + nameOf(player);
            case ALREADY_LINKED:
                return "這兩個帳號早就綁定在一起了。";
            case PLAYER_HAS_OTHER:
                return "這個遊戲帳號已經綁定了另一個 Discord 帳號。要換的話，先在遊戲輸入 /discord unlink。";
            case DISCORD_HAS_OTHER:
                return "這個 Discord 帳號已經綁定了另一個遊戲帳號。要換的話，先 /unlink。";
            default:
                return "綁定沒有儲存成功，請稍後再試，並通知管理員。";
        }
    }

    private static String nameOf(UUID player) {
        String name = Server.getInstance().getOfflinePlayer(player).getName();
        return name == null ? player.toString() : name;
    }
}

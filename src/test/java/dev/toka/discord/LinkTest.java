package dev.toka.discord;

import dev.toka.discord.link.LinkService;
import dev.toka.discord.link.MemberSync;
import dev.toka.discord.link.PendingCodes;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class LinkTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private PendingCodes codes() {
        return new PendingCodes(this.now::get, 600, 3, 600);
    }

    private static Path directory() throws Exception {
        Path root = Path.of(".build-tools/tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "links-").resolve("data");
    }

    private LinkService service(Backend backend, Path directory) throws Exception {
        return new LinkService(DocumentStore.open(new StoreConfig(backend, directory, 1)), this.now::get, this.codes());
    }

    // ---------------------------------------------------------------- the codes

    @Test
    public void aCodeIsMadeByOneSideAndTypedByTheOther() {
        PendingCodes codes = this.codes();
        UUID player = UUID.randomUUID();
        String code = codes.issue(PendingCodes.Side.GAME, player.toString());
        assertEquals(PendingCodes.LENGTH, code.length());

        PendingCodes.Match[] match = new PendingCodes.Match[1];
        assertEquals(PendingCodes.Outcome.OK, codes.confirm(PendingCodes.Side.DISCORD, "111", code, match));
        assertEquals(player, match[0].player());
        assertEquals("111", match[0].discordUser());
        // one time only
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, codes.confirm(PendingCodes.Side.DISCORD, "111", code, new PendingCodes.Match[1]));
    }

    @Test
    public void aCodeMadeByTheGameCannotBeTypedInTheGame() {
        PendingCodes codes = this.codes();
        UUID player = UUID.randomUUID();
        String code = codes.issue(PendingCodes.Side.GAME, player.toString());
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED,
                codes.confirm(PendingCodes.Side.GAME, UUID.randomUUID().toString(), code, new PendingCodes.Match[1]));
    }

    @Test
    public void theOtherDirectionWorksToo() {
        PendingCodes codes = this.codes();
        UUID player = UUID.randomUUID();
        String code = codes.issue(PendingCodes.Side.DISCORD, "222");
        PendingCodes.Match[] match = new PendingCodes.Match[1];
        assertEquals(PendingCodes.Outcome.OK, codes.confirm(PendingCodes.Side.GAME, player.toString(), code.toLowerCase(), match));
        assertEquals(player, match[0].player());
        assertEquals("222", match[0].discordUser());
    }

    @Test
    public void aCodeRunsOutAndANewOneReplacesTheOldOne() {
        PendingCodes codes = this.codes();
        String old = codes.issue(PendingCodes.Side.GAME, UUID.randomUUID().toString());
        this.now.addAndGet(601_000);
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, codes.confirm(PendingCodes.Side.DISCORD, "1", old, new PendingCodes.Match[1]));

        String owner = UUID.randomUUID().toString();
        String first = codes.issue(PendingCodes.Side.GAME, owner);
        String second = codes.issue(PendingCodes.Side.GAME, owner);
        if (!first.equals(second)) {
            assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, codes.confirm(PendingCodes.Side.DISCORD, "1", first, new PendingCodes.Match[1]));
        }
        assertEquals(PendingCodes.Outcome.OK, codes.confirm(PendingCodes.Side.DISCORD, "1", second, new PendingCodes.Match[1]));
    }

    @Test
    public void tooManyWrongCodesLockTheOneWhoTypesThem() {
        PendingCodes codes = this.codes();
        String right = codes.issue(PendingCodes.Side.GAME, UUID.randomUUID().toString());
        PendingCodes.Match[] match = new PendingCodes.Match[1];
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, codes.confirm(PendingCodes.Side.DISCORD, "9", "AAAAAA", match));
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, codes.confirm(PendingCodes.Side.DISCORD, "9", "BBBBBB", match));
        assertEquals(PendingCodes.Outcome.LOCKED, codes.confirm(PendingCodes.Side.DISCORD, "9", "CCCCCC", match));
        // even the right code does not help while locked
        assertEquals(PendingCodes.Outcome.LOCKED, codes.confirm(PendingCodes.Side.DISCORD, "9", right, match));
        // someone else is not locked
        assertEquals(PendingCodes.Outcome.OK, codes.confirm(PendingCodes.Side.DISCORD, "10", right, match));
        // the lock ends
        this.now.addAndGet(601_000);
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, codes.confirm(PendingCodes.Side.DISCORD, "9", "DDDDDD", match));
    }

    // ---------------------------------------------------------------- the links

    @Test
    public void oneToOneAndNothingIsReplacedSilently() throws Exception {
        LinkService links = this.service(Backend.SQLITE, directory());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(LinkService.Result.LINKED, links.link(a, "111"));
        assertEquals(LinkService.Result.ALREADY_LINKED, links.link(a, "111"));
        assertEquals(LinkService.Result.PLAYER_HAS_OTHER, links.link(a, "222"));
        assertEquals(LinkService.Result.DISCORD_HAS_OTHER, links.link(b, "111"));
        assertEquals("111", links.discordUserOf(a).orElseThrow());
        assertEquals(a, links.playerOf("111").orElseThrow());
        assertTrue(links.playerOf("222").isEmpty());

        // to change a link it is removed first, which means a new code
        assertTrue(links.unlinkPlayer(a));
        assertFalse(links.unlinkPlayer(a));
        assertEquals(LinkService.Result.LINKED, links.link(b, "111"));
        assertTrue(links.unlinkDiscord("111"));
        assertEquals(0, links.count());
    }

    @Test
    public void linkingByCodeEndsInALink() throws Exception {
        LinkService links = this.service(Backend.SQLITE, directory());
        UUID player = UUID.randomUUID();
        String code = links.codes().issue(PendingCodes.Side.DISCORD, "333");
        LinkService.Confirmation done = links.confirm(PendingCodes.Side.GAME, player.toString(), code);
        assertEquals(PendingCodes.Outcome.OK, done.outcome());
        assertEquals(LinkService.Result.LINKED, done.result());
        assertEquals(player, links.playerOf("333").orElseThrow());

        // a wrong code changes nothing
        LinkService.Confirmation wrong = links.confirm(PendingCodes.Side.GAME, UUID.randomUUID().toString(), "ZZZZZZ");
        assertEquals(PendingCodes.Outcome.WRONG_OR_EXPIRED, wrong.outcome());
        assertNull(wrong.result());
        assertEquals(1, links.count());
    }

    @Test
    public void linksSurviveARestartInEveryBackend() throws Exception {
        for (Backend backend : Backend.values()) {
            Path directory = directory();
            UUID player = UUID.randomUUID();
            LinkService first = this.service(backend, directory);
            assertEquals(LinkService.Result.LINKED, first.link(player, "444"));
            first.close();

            LinkService second = this.service(backend, directory);
            assertEquals(backend + " lost the link", "444", second.discordUserOf(player).orElseThrow());
            assertEquals(player, second.playerOf("444").orElseThrow());
            assertTrue(second.unlinkPlayer(player));
            second.close();

            LinkService third = this.service(backend, directory);
            assertEquals(0, third.count());
            third.close();
        }
    }

    // ---------------------------------------------------------------- the nickname

    @Test
    public void theNicknameIsTheFormatWithTheNameAndAtMost32Characters() {
        assertEquals("Sunny", MemberSync.nickname("{name}", "Sunny"));
        assertEquals("[村長] Sunny", MemberSync.nickname("[村長] {name}", "Sunny"));
        assertEquals(32, MemberSync.nickname("{name}", "x".repeat(50)).length());
    }

    @Test
    public void theNicknameModeIsReadLeniently() {
        assertEquals(MemberSync.NicknameMode.SET, MemberSync.parseMode(null));
        assertEquals(MemberSync.NicknameMode.SET, MemberSync.parseMode("set"));
        assertEquals(MemberSync.NicknameMode.SET, MemberSync.parseMode("nonsense"));
        assertEquals(MemberSync.NicknameMode.OFF, MemberSync.parseMode(" OFF "));
        assertEquals(MemberSync.NicknameMode.ENFORCE, MemberSync.parseMode("Enforce"));
    }

    // ---------------------------------------------------------------- the last known name

    @Test
    public void theLastKnownNameIsKeptAndFollowsARename() throws Exception {
        Path directory = directory();
        UUID player = UUID.randomUUID();
        try (LinkService links = this.service(Backend.JSON, directory)) {
            assertEquals(LinkService.Result.LINKED, links.link(player, "111", "Old"));
            assertEquals("Old", links.lastNameOf(player).orElse(null));
            assertFalse(links.updateName(player, "Old"));
            assertTrue(links.updateName(player, "New"));
        }
        try (LinkService links = this.service(Backend.JSON, directory)) {
            assertEquals("New", links.lastNameOf(player).orElse(null));
            // the link is by UUID, so it still holds
            assertEquals(player, links.playerOf("111").orElse(null));
            assertEquals("111", links.discordUserOf(player).orElse(null));
            assertEquals(1, links.count());
        }
    }

    @Test
    public void aLinkWithoutANameStillLoadsAndTakesOneLater() throws Exception {
        Path directory = directory();
        UUID player = UUID.randomUUID();
        try (LinkService links = this.service(Backend.JSON, directory)) {
            assertEquals(LinkService.Result.LINKED, links.link(player, "222"));
            assertTrue(links.lastNameOf(player).isEmpty());
            assertFalse(links.updateName(UUID.randomUUID(), "Nobody"));
            assertTrue(links.updateName(player, "Steve"));
        }
        try (LinkService links = this.service(Backend.JSON, directory)) {
            assertEquals("Steve", links.lastNameOf(player).orElse(null));
        }
    }
}

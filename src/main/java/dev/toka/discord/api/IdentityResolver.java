package dev.toka.discord.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Tells which player a Discord account belongs to, and the other way round.
 * <p>
 * The account linking itself is not part of this plugin yet. Another plugin, for example tokaProfiles, can provide
 * the answers through {@link DiscordLink#setIdentityResolver(IdentityResolver)}; until then every question is
 * answered with "not linked". A Discord name is never used to find a player, only the Discord user id.
 */
public interface IdentityResolver {

    /**
     * @param discordUserId the id of the Discord user, not the name
     * @return the player the account is linked to, or empty when it is not linked
     */
    Optional<UUID> playerOf(String discordUserId);

    /**
     * @return the id of the Discord user the player is linked to, or empty when there is none
     */
    Optional<String> discordUserOf(UUID playerId);
}

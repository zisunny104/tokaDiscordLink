package dev.toka.discord.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Tells which player a Discord account belongs to, and the other way round.
 * <p>
 * This plugin registers its own resolver, backed by the stored links; another plugin may replace it through
 * {@link DiscordLink#setIdentityResolver(IdentityResolver)}. Without a resolver every question is answered
 * with "not linked". A Discord name is never used to find a player, only the Discord user id.
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

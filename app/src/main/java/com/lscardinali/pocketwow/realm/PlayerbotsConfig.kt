package com.lscardinali.pocketwow.realm

/**
 * The mod-playerbots settings the app controls, rendered as overrides on top of the
 * module's playerbots.conf.dist. Everything else keeps the module defaults.
 *
 * [MODEST] is the starting point for weak phones and the "reset" target of the settings
 * screen; fields the app did not tune keep the dist default there, so they change nothing
 * until the user does. The world server rewrites modules/playerbots.conf on every start.
 *
 * This core never unloads map grids, so anything that makes bots roam (more bots, more
 * maps, following players around) raises memory use for the rest of the session.
 */
data class PlayerbotsConfig(
    /** Random bots log in at all; off leaves only bots players add themselves. */
    val randomBotsEnabled: Boolean = true,
    /** Random bots kept logged in. Equal min and max keep the population from drifting. */
    val minRandomBots: Int,
    val maxRandomBots: Int,
    /** Random bot accounts to create, 10 characters each; 0 lets the module compute it. */
    val accountCount: Int = 0,
    /** Bots logged in or updated per manager cycle; small batches avoid load spikes. */
    val botsPerInterval: Int,
    /** Percent of bots simulated when no real player is near (others idle). */
    val activeAlonePercent: Int,
    val minLevel: Int = 1,
    val maxLevel: Int = 80,
    /** Caps random bot levels at the highest online player's level. */
    val syncLevelWithPlayers: Boolean = false,
    /** Bots teleport into zones holding a real player; loads every zone a player visits. */
    val concentrateInPlayerZone: Boolean = false,
    /** Bots log in only while a real player is online. */
    val disabledWithoutRealPlayer: Boolean = false,
    /** Map ids random bots may teleport to; see [MAPS]. */
    val maps: Set<Int> = MAPS.keys.toSet(),
    /** Bots join battlegrounds on their own. */
    val joinBattlegrounds: Boolean,
    /** Bots queue for the Dungeon Finder. */
    val joinLfg: Boolean = true,
    /** Random bot guilds created on first start. */
    val guilds: Int,
    /** Accounts reserved for the addclass command, 10 characters each, created on start. */
    val addClassAccounts: Int,
    /** Bots one player may control at once (party and raid bots). */
    val maxAddedBots: Int = 40,
    /** Logs in all of a player's other characters as bots when they log in. */
    val altBotAutologin: Boolean = false,
    /** Random bots invite players to groups, raids and guilds. */
    val invitePlayers: Boolean = false,
    /** Bots are summoned to their master when they accept a group invite. */
    val summonWhenGroup: Boolean = true,
) {
    fun overrides(): Map<String, String> = mapOf(
        RANDOM_BOT_AUTOLOGIN to flag(randomBotsEnabled),
        MIN_RANDOM_BOTS to minRandomBots.toString(),
        MAX_RANDOM_BOTS to maxRandomBots.toString(),
        ACCOUNT_COUNT to accountCount.toString(),
        BOTS_PER_INTERVAL to botsPerInterval.toString(),
        ACTIVE_ALONE to activeAlonePercent.toString(),
        MIN_LEVEL to minLevel.toString(),
        MAX_LEVEL to maxLevel.toString(),
        SYNC_LEVEL to flag(syncLevelWithPlayers),
        CONCENTRATE_IN_PLAYER_ZONE to flag(concentrateInPlayerZone),
        DISABLED_WITHOUT_REAL_PLAYER to flag(disabledWithoutRealPlayer),
        // applyOverrides quotes the list; the core strips quotes when parsing.
        MAPS_KEY to maps.sorted().joinToString(","),
        JOIN_BG to flag(joinBattlegrounds),
        JOIN_LFG to flag(joinLfg),
        GUILD_COUNT to guilds.toString(),
        ADD_CLASS_ACCOUNTS to addClassAccounts.toString(),
        MAX_ADDED_BOTS to maxAddedBots.toString(),
        ALT_BOT_AUTOLOGIN to flag(altBotAutologin),
        INVITE_PLAYERS to flag(invitePlayers),
        SUMMON_WHEN_GROUP to flag(summonWhenGroup),
    )

    /** Out-of-range or contradictory values, named by conf key; empty when valid. */
    fun errors(): List<String> = buildList {
        fun check(key: String, value: Int, range: IntRange) {
            if (value !in range) add("$key must be ${range.first}-${range.last}")
        }
        check(MIN_RANDOM_BOTS, minRandomBots, BOT_COUNT_RANGE)
        check(MAX_RANDOM_BOTS, maxRandomBots, BOT_COUNT_RANGE)
        if (minRandomBots > maxRandomBots) add("$MIN_RANDOM_BOTS must not exceed $MAX_RANDOM_BOTS")
        check(ACCOUNT_COUNT, accountCount, ACCOUNT_COUNT_RANGE)
        check(BOTS_PER_INTERVAL, botsPerInterval, BOTS_PER_INTERVAL_RANGE)
        check(ACTIVE_ALONE, activeAlonePercent, PERCENT_RANGE)
        check(MIN_LEVEL, minLevel, WorldConfig.LEVEL_RANGE)
        check(MAX_LEVEL, maxLevel, WorldConfig.LEVEL_RANGE)
        if (minLevel > maxLevel) add("$MIN_LEVEL must not exceed $MAX_LEVEL")
        if (maps.isEmpty()) add("$MAPS_KEY needs at least one map")
        if (!MAPS.keys.containsAll(maps)) add("$MAPS_KEY has unknown map ids")
        check(GUILD_COUNT, guilds, GUILD_COUNT_RANGE)
        check(ADD_CLASS_ACCOUNTS, addClassAccounts, ADD_CLASS_ACCOUNTS_RANGE)
        check(MAX_ADDED_BOTS, maxAddedBots, MAX_ADDED_BOTS_RANGE)
    }

    companion object {
        const val RANDOM_BOT_AUTOLOGIN = "Playerbots.RandomBotAutologin"
        const val MIN_RANDOM_BOTS = "Playerbots.MinRandomBots"
        const val MAX_RANDOM_BOTS = "Playerbots.MaxRandomBots"
        const val ACCOUNT_COUNT = "Playerbots.RandomBotAccountCount"
        const val BOTS_PER_INTERVAL = "Playerbots.RandomBotsPerInterval"
        const val ACTIVE_ALONE = "Playerbots.BotActiveAlone"
        const val MIN_LEVEL = "Playerbots.RandomBotMinLevel"
        const val MAX_LEVEL = "Playerbots.RandomBotMaxLevel"
        const val SYNC_LEVEL = "Playerbots.SyncLevelWithPlayers"
        const val CONCENTRATE_IN_PLAYER_ZONE = "Playerbots.RandomBotConcentrateInPlayerZone"
        const val DISABLED_WITHOUT_REAL_PLAYER = "Playerbots.DisabledWithoutRealPlayer"
        const val MAPS_KEY = "Playerbots.RandomBotMaps"
        const val JOIN_BG = "Playerbots.RandomBotJoinBG"
        const val JOIN_LFG = "Playerbots.RandomBotJoinLfg"
        const val GUILD_COUNT = "Playerbots.RandomBotGuildCount"
        const val ADD_CLASS_ACCOUNTS = "Playerbots.AddClassAccountPoolSize"
        const val MAX_ADDED_BOTS = "Playerbots.MaxAddedBots"
        const val ALT_BOT_AUTOLOGIN = "Playerbots.BotAutologin"
        const val INVITE_PLAYERS = "Playerbots.RandomBotInvitePlayer"
        const val SUMMON_WHEN_GROUP = "Playerbots.SummonWhenGroup"

        // The dist documents no upper bounds for these; the caps keep typos from asking a
        // phone for thousands of bots. 500 is the module's own default population.
        val BOT_COUNT_RANGE = 0..500
        val ACCOUNT_COUNT_RANGE = 0..100
        val BOTS_PER_INTERVAL_RANGE = 1..100
        val PERCENT_RANGE = 0..100
        val GUILD_COUNT_RANGE = 0..50
        val ADD_CLASS_ACCOUNTS_RANGE = 0..50
        val MAX_ADDED_BOTS_RANGE = 1..40

        /** The dist's default RandomBotMaps: the four continents, by map id. */
        val MAPS = mapOf(0 to "Eastern Kingdoms", 1 to "Kalimdor", 530 to "Outland", 571 to "Northrend")

        val MODEST = PlayerbotsConfig(
            minRandomBots = 10,
            maxRandomBots = 10,
            botsPerInterval = 5,
            activeAlonePercent = 5,
            joinBattlegrounds = false,
            guilds = 0,
            addClassAccounts = 1,
            // Bots in the player's zone at their level, and only while someone plays: bots
            // roaming the world load grids everywhere, and loaded grids are never freed.
            syncLevelWithPlayers = true,
            concentrateInPlayerZone = true,
            disabledWithoutRealPlayer = true,
        )

        private fun flag(on: Boolean) = if (on) "1" else "0"
    }
}

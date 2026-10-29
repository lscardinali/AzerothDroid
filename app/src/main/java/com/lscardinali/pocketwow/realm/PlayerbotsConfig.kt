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
    /** Weights bot levels toward the brackets real players are in, keeping some at every level. */
    val dynamicLevelDistribution: Boolean = false,
    /** How strongly [dynamicLevelDistribution] favours players' brackets; 1 is mild, 10-15 solo. */
    val levelDistributionWeight: Float = 1.0f,
    /**
     * Share of that boost the brackets just above and below also get (patch 0002), so bots are
     * around the player's level, some higher and some lower; 0 boosts only the player's bracket.
     */
    val levelNeighborShare: Float = 0.0f,
    /** Players of both factions shape both factions' bot levels (needs identical brackets). */
    val levelSyncFactions: Boolean = false,
    /** Rotates a larger bot pool online and offline: more faces, same number online at once. */
    val periodicOnlineOffline: Boolean = false,
    /** Bot pool size as a multiple of the online bot count, for [periodicOnlineOffline]. */
    val onlineOfflineRatio: Float = 2.0f,
    /**
     * Server update time (ms) at which idle bots start to be paused, and at which all are.
     * Bots near a real player stay active regardless.
     */
    val smartScaleFloorMs: Int = 50,
    val smartScaleCeilingMs: Int = 200,
    /** Seconds between a bot's random teleports; shorter keeps bots following players around. */
    val teleportMinSeconds: Int = 3600,
    val teleportMaxSeconds: Int = 18000,
    /** Chance (0-1) that a teleporting bot of level 10+ goes to a capital's banker instead. */
    val cityChance: Float = 0.25f,
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
        DYNAMIC_LEVELS to flag(dynamicLevelDistribution),
        DYNAMIC_LEVELS_WEIGHT to levelDistributionWeight.toString(),
        PERIODIC_ONLINE to flag(periodicOnlineOffline),
        PERIODIC_RATIO to onlineOfflineRatio.toString(),
        SMART_SCALE_FLOOR to smartScaleFloorMs.toString(),
        SMART_SCALE_CEILING to smartScaleCeilingMs.toString(),
        DYNAMIC_LEVELS_NEIGHBORS to levelNeighborShare.toString(),
        DYNAMIC_LEVELS_SYNC_FACTIONS to flag(levelSyncFactions),
        TELEPORT_MIN to teleportMinSeconds.toString(),
        TELEPORT_MAX to teleportMaxSeconds.toString(),
        CITY_CHANCE to cityChance.toString(),
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
        if (levelDistributionWeight !in WEIGHT_RANGE) add("$DYNAMIC_LEVELS_WEIGHT must be 0-20")
        if (onlineOfflineRatio !in RATIO_RANGE) add("$PERIODIC_RATIO must be 1-10")
        check(SMART_SCALE_FLOOR, smartScaleFloorMs, UPDATE_MS_RANGE)
        check(SMART_SCALE_CEILING, smartScaleCeilingMs, UPDATE_MS_RANGE)
        if (smartScaleFloorMs >= smartScaleCeilingMs) add("$SMART_SCALE_FLOOR must be below $SMART_SCALE_CEILING")
        if (levelNeighborShare !in SHARE_RANGE) add("$DYNAMIC_LEVELS_NEIGHBORS must be 0-1")
        check(TELEPORT_MIN, teleportMinSeconds, TELEPORT_RANGE)
        check(TELEPORT_MAX, teleportMaxSeconds, TELEPORT_RANGE)
        if (teleportMinSeconds > teleportMaxSeconds) add("$TELEPORT_MIN must not exceed $TELEPORT_MAX")
        if (cityChance !in SHARE_RANGE) add("$CITY_CHANCE must be 0-1")
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
        const val DYNAMIC_LEVELS = "Playerbots.LevelBrackets.Dynamic.UseDynamicDistribution"
        const val DYNAMIC_LEVELS_WEIGHT = "Playerbots.LevelBrackets.Dynamic.RealPlayerWeight"
        const val PERIODIC_ONLINE = "Playerbots.EnablePeriodicOnlineOffline"
        const val PERIODIC_RATIO = "Playerbots.PeriodicOnlineOfflineRatio"
        const val SMART_SCALE_FLOOR = "Playerbots.botActiveAloneSmartScaleDiffLimitfloor"
        const val SMART_SCALE_CEILING = "Playerbots.botActiveAloneSmartScaleDiffLimitCeiling"
        const val DYNAMIC_LEVELS_NEIGHBORS = "Playerbots.LevelBrackets.Dynamic.NeighborShare"
        const val DYNAMIC_LEVELS_SYNC_FACTIONS = "Playerbots.LevelBrackets.Dynamic.SyncFactions"
        const val TELEPORT_MIN = "Playerbots.MinRandomBotTeleportInterval"
        const val TELEPORT_MAX = "Playerbots.MaxRandomBotTeleportInterval"
        const val CITY_CHANCE = "Playerbots.ProbTeleToBankers"

        // The dist documents no upper bounds for these; the caps keep typos from asking a
        // phone for thousands of bots. 500 is the module's own default population.
        val BOT_COUNT_RANGE = 0..500
        val ACCOUNT_COUNT_RANGE = 0..100
        val BOTS_PER_INTERVAL_RANGE = 1..100
        val PERCENT_RANGE = 0..100
        val GUILD_COUNT_RANGE = 0..50
        val ADD_CLASS_ACCOUNTS_RANGE = 0..50
        val MAX_ADDED_BOTS_RANGE = 1..40
        val WEIGHT_RANGE = 0f..20f
        val RATIO_RANGE = 1f..10f
        val UPDATE_MS_RANGE = 10..1000
        val SHARE_RANGE = 0f..1f
        /** One minute to one day. */
        val TELEPORT_RANGE = 60..86_400

        /** The dist's default RandomBotMaps: the four continents, by map id. */
        val MAPS = mapOf(0 to "Eastern Kingdoms", 1 to "Kalimdor", 530 to "Outland", 571 to "Northrend")

        /**
         * A living world sized for a 3 GB phone (measured: about 12 MB per online bot, and this
         * core never frees the grids bots load). Bots of the player's faction and level gather in
         * their zone; others keep every level and both factions present, for higher-level
         * encounters and PvP. A doubled pool rotates online for variety at the same memory cost.
 * Bots that do not fit the player's zone prefer the player's continent (patch 0003).
         */
        val MODEST = PlayerbotsConfig(
            minRandomBots = 60,
            maxRandomBots = 60,
            botsPerInterval = 10,
            activeAlonePercent = 10,
            joinBattlegrounds = true,
            guilds = 5,
            addClassAccounts = 1,
            concentrateInPlayerZone = true,
            disabledWithoutRealPlayer = true,
            // Around the player's level: about a third of bots in their bracket, a fifth in each
            // neighbouring one, the rest spread over 1-80 for higher-level encounters.
            dynamicLevelDistribution = true,
            levelDistributionWeight = 12.0f,
            levelNeighborShare = 0.5f,
            levelSyncFactions = true,
            // Re-teleport every 20-60 minutes so fitting bots follow the player between zones.
            teleportMinSeconds = 1200,
            teleportMaxSeconds = 3600,
            cityChance = 0.15f,
            periodicOnlineOffline = true,
            onlineOfflineRatio = 2.0f,
            // A phone's update loop is slower than a server's: start shedding idle bots earlier.
            smartScaleFloorMs = 30,
            smartScaleCeilingMs = 120,
        )

        private fun flag(on: Boolean) = if (on) "1" else "0"
    }
}

package com.lscardinali.azerothdroid.realm

/**
 * mod-autobalance: scales dungeons and raids to the players inside. Compiled in, off by default;
 * rendered over AutoBalance.conf.dist.
 */
data class AutoBalanceConfig(
    val enabled: Boolean = false,
    /** Instances scale as if at least this many players were inside. */
    val minPlayers: Int = 1,
    val minPlayersRaid: Int = 1,
    /** Added to the player count, so instances play harder than the group's size. */
    val difficultyOffset: Int = 0,
    /** Multiplies creature stats everywhere the module scales them. */
    val statModifier: Float = 1f,
    /** Brings creatures to the level of the highest player inside. */
    val levelScaling: Boolean = true,
    val announcePlayerChanges: Boolean = true,
) {
    fun overrides(): Map<String, String> = buildMap {
        put(ENABLE, flag(enabled))
        put(MIN_PLAYERS, minPlayers.toString())
        put(MIN_PLAYERS_HEROIC, minPlayers.toString())
        put(MIN_PLAYERS_RAID, minPlayersRaid.toString())
        put(MIN_PLAYERS_RAID_HEROIC, minPlayersRaid.toString())
        put(DIFFICULTY_OFFSET, difficultyOffset.toString())
        STAT_MODIFIER_KEYS.forEach { put(it, statModifier.toString()) }
        put(LEVEL_SCALING, flag(levelScaling))
        put(PLAYER_CHANGE_NOTIFY, flag(announcePlayerChanges))
    }

    fun errors(): List<String> = buildList {
        if (minPlayers !in PLAYERS_RANGE) add("$MIN_PLAYERS must be 1-${PLAYERS_RANGE.last}")
        if (minPlayersRaid !in PLAYERS_RANGE) add("$MIN_PLAYERS_RAID must be 1-${PLAYERS_RANGE.last}")
        if (difficultyOffset !in OFFSET_RANGE) add("$DIFFICULTY_OFFSET must be 0-${OFFSET_RANGE.last}")
        if (statModifier.isNaN() || statModifier !in STAT_RANGE) {
            add("${STAT_MODIFIER_KEYS.first()} must be ${STAT_RANGE.start}-${STAT_RANGE.endInclusive}")
        }
    }

    companion object {
        const val ENABLE = "AutoBalance.Enable.Global"
        const val MIN_PLAYERS = "AutoBalance.MinPlayers"
        const val MIN_PLAYERS_HEROIC = "AutoBalance.MinPlayers.Heroic"
        const val MIN_PLAYERS_RAID = "AutoBalance.MinPlayers.Raid"
        const val MIN_PLAYERS_RAID_HEROIC = "AutoBalance.MinPlayers.RaidHeroic"
        const val DIFFICULTY_OFFSET = "AutoBalance.playerCountDifficultyOffset"
        val STAT_MODIFIER_KEYS = listOf(
            "AutoBalance.StatModifier.Global",
            "AutoBalance.StatModifierHeroic.Global",
            "AutoBalance.StatModifierRaid.Global",
            "AutoBalance.StatModifierRaidHeroic.Global",
        )
        const val LEVEL_SCALING = "AutoBalance.LevelScaling"
        const val PLAYER_CHANGE_NOTIFY = "AutoBalance.PlayerChangeNotify"

        val PLAYERS_RANGE = 1..40
        val OFFSET_RANGE = 0..39
        val STAT_RANGE = 0.1f..10f

        val DEFAULT = AutoBalanceConfig()
    }
}

/**
 * mod-individual-progression: each character moves through Vanilla, TBC and WotLK content in
 * order. Compiled in, off by default; rendered over individualProgression.conf.dist.
 *
 * The module's SQL rewrites the world database, so [DataPack.setOptionalSql] holds it back until
 * the module is first enabled. Turning the module off later stops its rules but not those changes.
 */
data class IndividualProgressionConfig(
    val enabled: Boolean = false,
    /** Highest stage players can reach, 0 for none. */
    val progressionLimit: Int = 0,
    /** Stage new characters begin at. */
    val startingProgression: Int = 0,
    /** Player damage in Vanilla and TBC content, so they feel as they did: 0.5-0.6 is the module's suggestion. */
    val vanillaPower: Float = 1f,
    val tbcPower: Float = 1f,
    /** The power adjustments apply to bots only, which makes dungeons harder without changing players. */
    val botOnlyAdjustments: Boolean = false,
    /** Groups may only hold players at the same stage. */
    val enforceGroupRules: Boolean = false,
    val disableRandomDungeonFinder: Boolean = false,
    val hideQuestMarkers: Boolean = true,
) {
    fun overrides(): Map<String, String> = buildMap {
        put(ENABLE, flag(enabled))
        put(PROGRESSION_LIMIT, progressionLimit.toString())
        put(STARTING_PROGRESSION, startingProgression.toString())
        put(VANILLA_POWER, vanillaPower.toString())
        put(TBC_POWER, tbcPower.toString())
        put(BOT_ONLY_ADJUSTMENTS, flag(botOnlyAdjustments))
        put(ENFORCE_GROUP_RULES, flag(enforceGroupRules))
        // The module applies these four at config load, whether it is enabled or not.
        put(DISABLE_RDF, flag(enabled && disableRandomDungeonFinder))
        put(DISABLE_QUEST_MARKERS, flag(enabled && hideQuestMarkers))
        put(SIMPLE_CONFIG_OVERRIDE, flag(enabled))
        put(MAX_MONSTER_SIGHT, flag(enabled))
    }

    fun errors(): List<String> = buildList {
        if (progressionLimit !in LIMIT_RANGE) add("$PROGRESSION_LIMIT must be 0-${LIMIT_RANGE.last}")
        if (startingProgression !in STAGE_RANGE) add("$STARTING_PROGRESSION must be 0-${STAGE_RANGE.last}")
        if (progressionLimit != 0 && startingProgression > progressionLimit) {
            add("$STARTING_PROGRESSION must not exceed $PROGRESSION_LIMIT")
        }
        listOf(VANILLA_POWER to vanillaPower, TBC_POWER to tbcPower).forEach { (key, value) ->
            if (value.isNaN() || value !in POWER_RANGE) add("$key must be ${POWER_RANGE.start}-${POWER_RANGE.endInclusive}")
        }
    }

    companion object {
        const val MODULE = "mod-individual-progression"
        const val ENABLE = "IndividualProgression.Enable"
        const val PROGRESSION_LIMIT = "IndividualProgression.ProgressionLimit"
        const val STARTING_PROGRESSION = "IndividualProgression.StartingProgression"
        const val VANILLA_POWER = "IndividualProgression.VanillaPowerAdjustment"
        const val TBC_POWER = "IndividualProgression.TBCPowerAdjustment"
        const val BOT_ONLY_ADJUSTMENTS = "IndividualProgression.BotOnlyAdjustments"
        const val ENFORCE_GROUP_RULES = "IndividualProgression.EnforceGroupRules"
        const val DISABLE_RDF = "IndividualProgression.DisableRDF"
        const val DISABLE_QUEST_MARKERS = "IndividualProgression.DisableQuestMarkers"
        const val SIMPLE_CONFIG_OVERRIDE = "IndividualProgression.SimpleConfigOverride"
        const val MAX_MONSTER_SIGHT = "IndividualProgression.MaxMonsterSight"

        /** The module's ProgressionState: 18 is the last stage, and a limit of 18 would be no limit. */
        val STAGE_RANGE = 0..18
        val LIMIT_RANGE = 0..17
        val POWER_RANGE = 0.1f..1f

        val DEFAULT = IndividualProgressionConfig()
    }
}

private fun flag(on: Boolean) = if (on) "1" else "0"

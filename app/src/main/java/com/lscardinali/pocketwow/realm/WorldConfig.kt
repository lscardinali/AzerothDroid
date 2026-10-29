package com.lscardinali.pocketwow.realm

/**
 * The gameplay settings of worldserver.conf that players may change, rendered as overrides
 * on top of worldserver.conf.dist. Infrastructure keys (paths, ports, database, SOAP,
 * console) stay in [WorldServer] and are never user settings.
 *
 * [DEFAULT] equals the dist values, i.e. what the realm ran with before settings existed.
 * Several fields fan out to a family of keys (all item qualities, all skill types) because
 * nobody tunes those one by one on a phone.
 */
data class WorldConfig(
    val xpKill: Float = 1f,
    val xpQuest: Float = 1f,
    val xpExplore: Float = 1f,
    /** Rest XP growth, in game and offline (inn/city and wilderness alike). */
    val restXp: Float = 1f,
    /** Item drop chance for every quality, poor to artifact, and reference loot. */
    val itemDrop: Float = 1f,
    val moneyDrop: Float = 1f,
    val honor: Float = 1f,
    val reputation: Float = 1f,
    /** Points per crafting, gathering, weapon and defense skill-up (whole numbers in the core). */
    val skillGain: Int = 1,
    val maxPlayerLevel: Int = 80,
    val startPlayerLevel: Int = 1,
    /** Death knights; their starting zone assumes 55. */
    val startHeroicPlayerLevel: Int = 55,
    /** Whole gold; the conf takes copper. */
    val startGold: Int = 0,
    /** New characters know every flight path of both factions. */
    val allFlightPaths: Boolean = false,
    /** 0 off, 1 on, 2 on with a per-flight-master toggle (dist values). */
    val instantFlightPaths: Int = 0,
    val instanceIgnoreLevel: Boolean = false,
    /** Lets a small party (or a solo player) enter raids. */
    val instanceIgnoreRaid: Boolean = false,
    /** Hour of the daily instance reset, 0-23. */
    val instanceResetHour: Int = 4,
    /** 1 on, 0 battle off but zone still processed, 2 all processing off (dist values). */
    val wintergrasp: Int = 1,
    /** Yards; farther sight keeps more creatures and grids active around players. */
    val visibilityContinents: Int = 100,
    val visibilityInstances: Int = 170,
) {
    fun overrides(): Map<String, String> = buildMap {
        put(XP_KILL, rate(xpKill))
        put(XP_QUEST, rate(xpQuest))
        put(XP_EXPLORE, rate(xpExplore))
        REST_XP_KEYS.forEach { put(it, rate(restXp)) }
        ITEM_DROP_KEYS.forEach { put(it, rate(itemDrop)) }
        put(MONEY_DROP, rate(moneyDrop))
        put(HONOR, rate(honor))
        put(REPUTATION, rate(reputation))
        SKILL_GAIN_KEYS.forEach { put(it, skillGain.toString()) }
        put(MAX_PLAYER_LEVEL, maxPlayerLevel.toString())
        put(START_PLAYER_LEVEL, startPlayerLevel.toString())
        put(START_HEROIC_PLAYER_LEVEL, startHeroicPlayerLevel.toString())
        put(START_PLAYER_MONEY, (startGold.toLong() * COPPER_PER_GOLD).toString())
        put(ALL_FLIGHT_PATHS, flag(allFlightPaths))
        put(INSTANT_FLIGHT_PATHS, instantFlightPaths.toString())
        put(INSTANCE_IGNORE_LEVEL, flag(instanceIgnoreLevel))
        put(INSTANCE_IGNORE_RAID, flag(instanceIgnoreRaid))
        put(INSTANCE_RESET_HOUR, instanceResetHour.toString())
        put(WINTERGRASP, wintergrasp.toString())
        put(VISIBILITY_CONTINENTS, visibilityContinents.toString())
        put(VISIBILITY_INSTANCES, visibilityInstances.toString())
    }

    /** Out-of-range or contradictory values, named by conf key; empty when valid. */
    fun errors(): List<String> = buildList {
        fun check(key: String, value: Int, range: IntRange) {
            if (value !in range) add("$key must be ${range.first}-${range.last}")
        }
        fun check(key: String, value: Float) {
            if (value.isNaN() || value !in RATE_RANGE) add("$key must be ${RATE_RANGE.start}-${RATE_RANGE.endInclusive}")
        }
        check(XP_KILL, xpKill)
        check(XP_QUEST, xpQuest)
        check(XP_EXPLORE, xpExplore)
        check(REST_XP_KEYS.first(), restXp)
        check(ITEM_DROP_KEYS.first(), itemDrop)
        check(MONEY_DROP, moneyDrop)
        check(HONOR, honor)
        check(REPUTATION, reputation)
        check(SKILL_GAIN_KEYS.first(), skillGain, SKILL_GAIN_RANGE)
        check(MAX_PLAYER_LEVEL, maxPlayerLevel, LEVEL_RANGE)
        check(START_PLAYER_LEVEL, startPlayerLevel, 1..maxPlayerLevel)
        check(START_HEROIC_PLAYER_LEVEL, startHeroicPlayerLevel, 1..maxPlayerLevel)
        check(START_PLAYER_MONEY, startGold, START_GOLD_RANGE)
        check(INSTANT_FLIGHT_PATHS, instantFlightPaths, 0..2)
        check(INSTANCE_RESET_HOUR, instanceResetHour, 0..23)
        check(WINTERGRASP, wintergrasp, 0..2)
        check(VISIBILITY_CONTINENTS, visibilityContinents, VISIBILITY_RANGE)
        check(VISIBILITY_INSTANCES, visibilityInstances, VISIBILITY_RANGE)
    }

    companion object {
        const val XP_KILL = "Rate.XP.Kill"
        const val XP_QUEST = "Rate.XP.Quest"
        const val XP_EXPLORE = "Rate.XP.Explore"
        val REST_XP_KEYS = listOf(
            "Rate.Rest.InGame", "Rate.Rest.Offline.InTavernOrCity", "Rate.Rest.Offline.InWilderness",
        )
        val ITEM_DROP_KEYS = listOf(
            "Rate.Drop.Item.Poor", "Rate.Drop.Item.Normal", "Rate.Drop.Item.Uncommon", "Rate.Drop.Item.Rare",
            "Rate.Drop.Item.Epic", "Rate.Drop.Item.Legendary", "Rate.Drop.Item.Artifact", "Rate.Drop.Item.Referenced",
        )
        const val MONEY_DROP = "Rate.Drop.Money"
        const val HONOR = "Rate.Honor"
        const val REPUTATION = "Rate.Reputation.Gain"
        val SKILL_GAIN_KEYS = listOf(
            "SkillGain.Crafting", "SkillGain.Gathering", "SkillGain.Weapon", "SkillGain.Defense",
        )
        const val MAX_PLAYER_LEVEL = "MaxPlayerLevel"
        const val START_PLAYER_LEVEL = "StartPlayerLevel"
        const val START_HEROIC_PLAYER_LEVEL = "StartHeroicPlayerLevel"
        const val START_PLAYER_MONEY = "StartPlayerMoney"
        const val ALL_FLIGHT_PATHS = "AllFlightPaths"
        const val INSTANT_FLIGHT_PATHS = "InstantFlightPaths"
        const val INSTANCE_IGNORE_LEVEL = "Instance.IgnoreLevel"
        const val INSTANCE_IGNORE_RAID = "Instance.IgnoreRaid"
        const val INSTANCE_RESET_HOUR = "Instance.ResetTimeHour"
        const val WINTERGRASP = "Wintergrasp.Enable"
        const val VISIBILITY_CONTINENTS = "Visibility.Distance.Continents"
        const val VISIBILITY_INSTANCES = "Visibility.Distance.Instances"

        /** The dist gives no bounds for rates; 0 (none) to 100x covers every sane server. */
        val RATE_RANGE = 0f..100f
        /** The dist says 1-255, but the core rejects MaxPlayerLevel above MAX_LEVEL (100). Client data ends at 80. */
        val LEVEL_RANGE = 1..100
        /** Undocumented; 0 would stop skill-ups altogether. */
        val SKILL_GAIN_RANGE = 1..10
        /** The core caps money at MAX_MONEY_AMOUNT, 2^31-2 copper (about 214k gold). */
        val START_GOLD_RANGE = 0..200_000
        /** Dist: max 250, min the max aggro radius (45) at the default aggro rate. */
        val VISIBILITY_RANGE = 45..250
        private const val COPPER_PER_GOLD = 10_000

        val DEFAULT = WorldConfig()

        /** "2" rather than "2.0", so default values read like the dist. */
        private fun rate(value: Float) =
            if (value % 1f == 0f) value.toLong().toString() else value.toString()

        private fun flag(on: Boolean) = if (on) "1" else "0"
    }
}

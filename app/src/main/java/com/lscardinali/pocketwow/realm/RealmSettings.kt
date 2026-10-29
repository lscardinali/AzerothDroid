package com.lscardinali.pocketwow.realm

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Everything the settings screen edits; the world server renders it into its .conf files. */
data class RealmSettings(
    val world: WorldConfig = WorldConfig.DEFAULT,
    val bots: PlayerbotsConfig = PlayerbotsConfig.MODEST,
) {
    fun errors(): List<String> = world.errors() + bots.errors() + buildList {
        if (bots.maxLevel > world.maxPlayerLevel) {
            add("${PlayerbotsConfig.MAX_LEVEL} must not exceed ${WorldConfig.MAX_PLAYER_LEVEL}")
        }
    }

    companion object {
        val DEFAULT = RealmSettings()
    }
}

private val Context.realmSettings by preferencesDataStore("realm_settings")

/**
 * Persists [RealmSettings] in a Preferences DataStore. Preference names are the conf keys
 * (the first key of a fanned-out family), so a stored value is easy to trace to its line.
 * Only values that differ from the defaults are stored: missing ones read as defaults, so
 * reset is an empty store, and settings added or retuned in later versions take effect for
 * everyone who has not changed them.
 */
class RealmSettingsStore(context: Context) {

    private val store = context.applicationContext.realmSettings

    val settings: Flow<RealmSettings> = store.data.map(::decode)

    suspend fun save(settings: RealmSettings) {
        store.edit { encode(settings, it) }
    }

    /**
     * Blocking read for the realm start, which already runs off the main thread. Falls back
     * to the defaults if the stored values are invalid, so the realm still comes up.
     */
    fun load(): RealmSettings {
        val settings = runBlocking { settings.first() }
        val errors = settings.errors()
        if (errors.isEmpty()) return settings
        Log.w(TAG, "Stored settings invalid, using defaults: $errors")
        return RealmSettings.DEFAULT
    }

    companion object {
        private const val TAG = "RealmSettings"

        fun encode(settings: RealmSettings, prefs: MutablePreferences) {
            prefs.clear()
            fun <T> put(key: Preferences.Key<T>, value: T, default: T) {
                if (value != default) prefs[key] = value
            }
            val w = settings.world
            val dw = WorldConfig.DEFAULT
            with(WorldConfig) {
                put(float(XP_KILL), w.xpKill, dw.xpKill)
                put(float(XP_QUEST), w.xpQuest, dw.xpQuest)
                put(float(XP_EXPLORE), w.xpExplore, dw.xpExplore)
                put(float(REST_XP_KEYS.first()), w.restXp, dw.restXp)
                put(float(ITEM_DROP_KEYS.first()), w.itemDrop, dw.itemDrop)
                put(float(MONEY_DROP), w.moneyDrop, dw.moneyDrop)
                put(float(HONOR), w.honor, dw.honor)
                put(float(REPUTATION), w.reputation, dw.reputation)
                put(int(SKILL_GAIN_KEYS.first()), w.skillGain, dw.skillGain)
                put(int(MAX_PLAYER_LEVEL), w.maxPlayerLevel, dw.maxPlayerLevel)
                put(int(START_PLAYER_LEVEL), w.startPlayerLevel, dw.startPlayerLevel)
                put(int(START_HEROIC_PLAYER_LEVEL), w.startHeroicPlayerLevel, dw.startHeroicPlayerLevel)
                put(int(START_PLAYER_MONEY), w.startGold, dw.startGold)
                put(bool(ALL_FLIGHT_PATHS), w.allFlightPaths, dw.allFlightPaths)
                put(int(INSTANT_FLIGHT_PATHS), w.instantFlightPaths, dw.instantFlightPaths)
                put(bool(INSTANCE_IGNORE_LEVEL), w.instanceIgnoreLevel, dw.instanceIgnoreLevel)
                put(bool(INSTANCE_IGNORE_RAID), w.instanceIgnoreRaid, dw.instanceIgnoreRaid)
                put(int(INSTANCE_RESET_HOUR), w.instanceResetHour, dw.instanceResetHour)
                put(int(WINTERGRASP), w.wintergrasp, dw.wintergrasp)
                put(int(VISIBILITY_CONTINENTS), w.visibilityContinents, dw.visibilityContinents)
                put(int(VISIBILITY_INSTANCES), w.visibilityInstances, dw.visibilityInstances)
            }
            val b = settings.bots
            val db = PlayerbotsConfig.MODEST
            with(PlayerbotsConfig) {
                put(bool(RANDOM_BOT_AUTOLOGIN), b.randomBotsEnabled, db.randomBotsEnabled)
                put(int(MIN_RANDOM_BOTS), b.minRandomBots, db.minRandomBots)
                put(int(MAX_RANDOM_BOTS), b.maxRandomBots, db.maxRandomBots)
                put(int(ACCOUNT_COUNT), b.accountCount, db.accountCount)
                put(int(BOTS_PER_INTERVAL), b.botsPerInterval, db.botsPerInterval)
                put(int(ACTIVE_ALONE), b.activeAlonePercent, db.activeAlonePercent)
                put(int(MIN_LEVEL), b.minLevel, db.minLevel)
                put(int(MAX_LEVEL), b.maxLevel, db.maxLevel)
                put(bool(SYNC_LEVEL), b.syncLevelWithPlayers, db.syncLevelWithPlayers)
                put(bool(CONCENTRATE_IN_PLAYER_ZONE), b.concentrateInPlayerZone, db.concentrateInPlayerZone)
                put(bool(DISABLED_WITHOUT_REAL_PLAYER), b.disabledWithoutRealPlayer, db.disabledWithoutRealPlayer)
                put(stringPreferencesKey(MAPS_KEY), b.maps.sorted().joinToString(","), db.maps.sorted().joinToString(","))
                put(bool(JOIN_BG), b.joinBattlegrounds, db.joinBattlegrounds)
                put(bool(JOIN_LFG), b.joinLfg, db.joinLfg)
                put(int(GUILD_COUNT), b.guilds, db.guilds)
                put(int(ADD_CLASS_ACCOUNTS), b.addClassAccounts, db.addClassAccounts)
                put(int(MAX_ADDED_BOTS), b.maxAddedBots, db.maxAddedBots)
                put(bool(ALT_BOT_AUTOLOGIN), b.altBotAutologin, db.altBotAutologin)
                put(bool(INVITE_PLAYERS), b.invitePlayers, db.invitePlayers)
                put(bool(SUMMON_WHEN_GROUP), b.summonWhenGroup, db.summonWhenGroup)
            }
        }

        fun decode(prefs: Preferences): RealmSettings {
            val dw = WorldConfig.DEFAULT
            val world = with(WorldConfig) {
                WorldConfig(
                    xpKill = prefs[float(XP_KILL)] ?: dw.xpKill,
                    xpQuest = prefs[float(XP_QUEST)] ?: dw.xpQuest,
                    xpExplore = prefs[float(XP_EXPLORE)] ?: dw.xpExplore,
                    restXp = prefs[float(REST_XP_KEYS.first())] ?: dw.restXp,
                    itemDrop = prefs[float(ITEM_DROP_KEYS.first())] ?: dw.itemDrop,
                    moneyDrop = prefs[float(MONEY_DROP)] ?: dw.moneyDrop,
                    honor = prefs[float(HONOR)] ?: dw.honor,
                    reputation = prefs[float(REPUTATION)] ?: dw.reputation,
                    skillGain = prefs[int(SKILL_GAIN_KEYS.first())] ?: dw.skillGain,
                    maxPlayerLevel = prefs[int(MAX_PLAYER_LEVEL)] ?: dw.maxPlayerLevel,
                    startPlayerLevel = prefs[int(START_PLAYER_LEVEL)] ?: dw.startPlayerLevel,
                    startHeroicPlayerLevel = prefs[int(START_HEROIC_PLAYER_LEVEL)] ?: dw.startHeroicPlayerLevel,
                    startGold = prefs[int(START_PLAYER_MONEY)] ?: dw.startGold,
                    allFlightPaths = prefs[bool(ALL_FLIGHT_PATHS)] ?: dw.allFlightPaths,
                    instantFlightPaths = prefs[int(INSTANT_FLIGHT_PATHS)] ?: dw.instantFlightPaths,
                    instanceIgnoreLevel = prefs[bool(INSTANCE_IGNORE_LEVEL)] ?: dw.instanceIgnoreLevel,
                    instanceIgnoreRaid = prefs[bool(INSTANCE_IGNORE_RAID)] ?: dw.instanceIgnoreRaid,
                    instanceResetHour = prefs[int(INSTANCE_RESET_HOUR)] ?: dw.instanceResetHour,
                    wintergrasp = prefs[int(WINTERGRASP)] ?: dw.wintergrasp,
                    visibilityContinents = prefs[int(VISIBILITY_CONTINENTS)] ?: dw.visibilityContinents,
                    visibilityInstances = prefs[int(VISIBILITY_INSTANCES)] ?: dw.visibilityInstances,
                )
            }
            val db = PlayerbotsConfig.MODEST
            val bots = with(PlayerbotsConfig) {
                PlayerbotsConfig(
                    randomBotsEnabled = prefs[bool(RANDOM_BOT_AUTOLOGIN)] ?: db.randomBotsEnabled,
                    minRandomBots = prefs[int(MIN_RANDOM_BOTS)] ?: db.minRandomBots,
                    maxRandomBots = prefs[int(MAX_RANDOM_BOTS)] ?: db.maxRandomBots,
                    accountCount = prefs[int(ACCOUNT_COUNT)] ?: db.accountCount,
                    botsPerInterval = prefs[int(BOTS_PER_INTERVAL)] ?: db.botsPerInterval,
                    activeAlonePercent = prefs[int(ACTIVE_ALONE)] ?: db.activeAlonePercent,
                    minLevel = prefs[int(MIN_LEVEL)] ?: db.minLevel,
                    maxLevel = prefs[int(MAX_LEVEL)] ?: db.maxLevel,
                    syncLevelWithPlayers = prefs[bool(SYNC_LEVEL)] ?: db.syncLevelWithPlayers,
                    concentrateInPlayerZone = prefs[bool(CONCENTRATE_IN_PLAYER_ZONE)] ?: db.concentrateInPlayerZone,
                    disabledWithoutRealPlayer = prefs[bool(DISABLED_WITHOUT_REAL_PLAYER)] ?: db.disabledWithoutRealPlayer,
                    maps = prefs[stringPreferencesKey(MAPS_KEY)]
                        ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                        ?: db.maps,
                    joinBattlegrounds = prefs[bool(JOIN_BG)] ?: db.joinBattlegrounds,
                    joinLfg = prefs[bool(JOIN_LFG)] ?: db.joinLfg,
                    guilds = prefs[int(GUILD_COUNT)] ?: db.guilds,
                    addClassAccounts = prefs[int(ADD_CLASS_ACCOUNTS)] ?: db.addClassAccounts,
                    maxAddedBots = prefs[int(MAX_ADDED_BOTS)] ?: db.maxAddedBots,
                    altBotAutologin = prefs[bool(ALT_BOT_AUTOLOGIN)] ?: db.altBotAutologin,
                    invitePlayers = prefs[bool(INVITE_PLAYERS)] ?: db.invitePlayers,
                    summonWhenGroup = prefs[bool(SUMMON_WHEN_GROUP)] ?: db.summonWhenGroup,
                )
            }
            return RealmSettings(world, bots)
        }

        private fun int(key: String) = intPreferencesKey(key)
        private fun float(key: String) = floatPreferencesKey(key)
        private fun bool(key: String) = booleanPreferencesKey(key)
    }
}

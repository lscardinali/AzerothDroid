package com.lscardinali.azerothdroid.realm

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RealmSettingsTest {

    /** Staged by tools/; set AZEROTHDROID_DIST_DIR when testing from a checkout without native/out. */
    private val distDir = File(System.getenv("AZEROTHDROID_DIST_DIR") ?: "../native/out/assets/worldserver")

    private fun dist(name: String): String {
        val file = File(distDir, name)
        assumeTrue("$file not staged", file.exists())
        return file.readText()
    }

    private fun distValue(conf: String, key: String): String =
        Regex("""^${Regex.escape(key)}\s*=\s*(.*)$""", RegexOption.MULTILINE).find(conf)!!.groupValues[1].trim()

    private val changed = RealmSettings(
        world = WorldConfig(
            xpKill = 3f, xpQuest = 2.5f, restXp = 4f, itemDrop = 2f, skillGain = 2, maxPlayerLevel = 70,
            startGold = 5, instantFlightPaths = 2, instanceIgnoreRaid = true, wintergrasp = 2, visibilityContinents = 60,
        ),
        bots = PlayerbotsConfig.MODEST.copy(
            randomBotsEnabled = false, minRandomBots = 20, maxRandomBots = 40, maxLevel = 70,
            maps = setOf(0, 1), joinLfg = false, summonWhenGroup = false,
        ),
        autoBalance = AutoBalanceConfig(enabled = true, minPlayers = 3, difficultyOffset = 2, statModifier = 1.5f, levelScaling = false),
        progression = IndividualProgressionConfig(
            enabled = true, progressionLimit = 13, startingProgression = 8, vanillaPower = 0.6f,
            botOnlyAdjustments = true, hideQuestMarkers = false,
        ),
    )

    @Test
    fun everyKeyExistsInTheDist() {
        // applyOverrides fails on unknown keys, which is what catches typos at realm start.
        AuthServer.applyOverrides(dist("worldserver.conf.dist"), changed.world.overrides())
        // Except level brackets past the dist's nine, which WorldServer appends instead.
        val beyondDist = Regex("""\.Range(\d+)\.""")
        val bots = changed.bots.overrides().filterKeys { key ->
            beyondDist.find(key)?.groupValues?.get(1)?.toInt()?.let { it <= 9 } ?: true
        }
        AuthServer.applyOverrides(dist("playerbots.conf.dist"), bots)
        AuthServer.applyOverrides(dist("AutoBalance.conf.dist"), changed.autoBalance.overrides())
        AuthServer.applyOverrides(dist("individualProgression.conf.dist"), changed.progression.overrides())
    }

    @Test
    fun modulesStayOutOfTheWayUntilEnabled() {
        val autoBalance = AutoBalanceConfig.DEFAULT.overrides()
        assertEquals("0", autoBalance[AutoBalanceConfig.ENABLE])
        val progression = IndividualProgressionConfig.DEFAULT.overrides()
        // These apply at config load even with the module off; each would change the normal game.
        listOf(
            IndividualProgressionConfig.ENABLE, IndividualProgressionConfig.DISABLE_RDF,
            IndividualProgressionConfig.DISABLE_QUEST_MARKERS, IndividualProgressionConfig.SIMPLE_CONFIG_OVERRIDE,
            IndividualProgressionConfig.MAX_MONSTER_SIGHT,
        ).forEach { assertEquals(it, "0", progression[it]) }
        assertEquals("1", changed.progression.copy(hideQuestMarkers = true).overrides()[IndividualProgressionConfig.DISABLE_QUEST_MARKERS])
    }

    @Test
    fun moduleDefaultsOtherwiseMatchTheDist() {
        val tuned = setOf(
            AutoBalanceConfig.ENABLE, IndividualProgressionConfig.ENABLE, IndividualProgressionConfig.DISABLE_QUEST_MARKERS,
            IndividualProgressionConfig.SIMPLE_CONFIG_OVERRIDE, IndividualProgressionConfig.MAX_MONSTER_SIGHT,
        )
        val autoBalance = dist("AutoBalance.conf.dist")
        AutoBalanceConfig.DEFAULT.overrides().filterKeys { it !in tuned }.forEach { (key, value) ->
            assertEquals(key, distValue(autoBalance, key).toFloat(), value.toFloat())
        }
        val progression = dist("individualProgression.conf.dist")
        IndividualProgressionConfig.DEFAULT.overrides().filterKeys { it !in tuned }.forEach { (key, value) ->
            assertEquals(key, distValue(progression, key).toFloat(), value.toFloat())
        }
    }

    @Test
    fun worldDefaultsAreTheDistValues() {
        // Wintergrasp is off by default; bots cannot navigate it and it keeps grids loaded.
        val tuned = mapOf("Wintergrasp.Enable" to "2")
        val conf = dist("worldserver.conf.dist")
        WorldConfig.DEFAULT.overrides().forEach { (key, value) ->
            assertEquals(key, tuned[key] ?: distValue(conf, key), value)
        }
    }

    @Test
    fun botDefaultsKeepModestAndOtherwiseTheDist() {
        val tuned = mapOf(
            "Playerbots.MinRandomBots" to "60",
            "Playerbots.MaxRandomBots" to "60",
            "Playerbots.RandomBotsPerInterval" to "10",
            "Playerbots.BotActiveAlone" to "10",
            "Playerbots.RandomBotGuildCount" to "5",
            "Playerbots.AddClassAccountPoolSize" to "1",
            "Playerbots.RandomBotConcentrateInPlayerZone" to "1",
            "Playerbots.DisabledWithoutRealPlayer" to "1",
            "Playerbots.LevelBrackets.Enabled" to "1",
            "Playerbots.LevelBrackets.Dynamic.UseDynamicDistribution" to "1",
            "Playerbots.LevelBrackets.Dynamic.RealPlayerWeight" to "20.0",
            "Playerbots.RandomBotConcentrateAnyStartingArea" to "1",
            "Playerbots.MinRandomBotInWorldTime" to "1200",
            "Playerbots.MaxRandomBotInWorldTime" to "3600",
            "Playerbots.LevelBrackets.Dynamic.NeighborShare" to "0.5",
            "Playerbots.LevelBrackets.Dynamic.SyncFactions" to "1",
            "Playerbots.MinRandomBotTeleportInterval" to "1200",
            "Playerbots.MaxRandomBotTeleportInterval" to "3600",
            "Playerbots.ProbTeleToBankers" to "0.15",
            "Playerbots.EnablePeriodicOnlineOffline" to "1",
            "Playerbots.botActiveAloneSmartScaleDiffLimitfloor" to "30",
            "Playerbots.botActiveAloneSmartScaleDiffLimitCeiling" to "120",
        )
        val conf = dist("playerbots.conf.dist")
        // Level brackets have their own test: the 5-level set has ranges the dist lacks.
        PlayerbotsConfig.MODEST.overrides().filterKeys { !it.startsWith("Playerbots.LevelBrackets.Alliance.") &&
            !it.startsWith("Playerbots.LevelBrackets.Horde.") && it != PlayerbotsConfig.BRACKET_COUNT
        }.forEach { (key, value) ->
            assertEquals(key, tuned[key] ?: distValue(conf, key), value)
        }
    }

    @Test
    fun narrowBracketsCoverEveryLevelOnce() {
        val brackets = PlayerbotsConfig.NARROW_BRACKETS
        assertEquals(100, brackets.sumOf { it.second })
        assertEquals(1, brackets.first().first.first)
        assertEquals(80, brackets.last().first.last)
        brackets.zipWithNext().forEach { (a, b) -> assertEquals(a.first.last + 1, b.first.first) }
        val overrides = PlayerbotsConfig.MODEST.overrides()
        assertEquals(brackets.size.toString(), overrides[PlayerbotsConfig.BRACKET_COUNT])
        assertEquals("75", overrides["Playerbots.LevelBrackets.Horde.Range16.Lower"])
        assertEquals("80", overrides["Playerbots.LevelBrackets.Alliance.Range17.Upper"])
    }

    @Test
    fun ratesAndMoneyRenderForTheConf() {
        val world = changed.world.overrides()
        assertEquals("3", world["Rate.XP.Kill"])
        assertEquals("2.5", world["Rate.XP.Quest"])
        assertEquals("4", world["Rate.Rest.Offline.InWilderness"])
        assertEquals("2", world["Rate.Drop.Item.Artifact"])
        assertEquals("2", world["SkillGain.Defense"])
        assertEquals("50000", world["StartPlayerMoney"])
        assertEquals("0,1", changed.bots.overrides()["Playerbots.RandomBotMaps"])
    }

    @Test
    fun defaultsStoreNothing() {
        val prefs = mutablePreferencesOf()
        RealmSettingsStore.encode(RealmSettings.DEFAULT, prefs)
        assertTrue(prefs.asMap().isEmpty())
        assertEquals(RealmSettings.DEFAULT, RealmSettingsStore.decode(prefs))
    }

    @Test
    fun storeRoundTrips() {
        val prefs = mutablePreferencesOf()
        RealmSettingsStore.encode(changed, prefs)
        assertEquals(changed, RealmSettingsStore.decode(prefs))
    }

    @Test
    fun validation() {
        assertEquals(emptyList<String>(), RealmSettings.DEFAULT.errors())
        assertEquals(emptyList<String>(), changed.errors())
        fun errors(settings: RealmSettings) = settings.errors().joinToString()
        val bots = PlayerbotsConfig.MODEST
        assertTrue(errors(RealmSettings(bots = bots.copy(minRandomBots = bots.maxRandomBots + 1))).contains("must not exceed Playerbots.MaxRandomBots"))
        assertTrue(errors(RealmSettings(bots = bots.copy(maps = emptySet()))).contains("at least one map"))
        assertTrue(errors(RealmSettings(bots = bots.copy(maxRandomBots = 501))).contains("Playerbots.MaxRandomBots must be 0-500"))
        assertTrue(errors(RealmSettings(WorldConfig(maxPlayerLevel = 60))).contains("must not exceed MaxPlayerLevel"))
        assertTrue(errors(RealmSettings(WorldConfig(maxPlayerLevel = 60, startHeroicPlayerLevel = 61))).contains("StartHeroicPlayerLevel"))
        assertTrue(errors(RealmSettings(WorldConfig(maxPlayerLevel = 101))).contains("MaxPlayerLevel must be 1-100"))
        assertTrue(errors(RealmSettings(WorldConfig(xpKill = Float.NaN))).contains("Rate.XP.Kill"))
        assertTrue(errors(RealmSettings(WorldConfig(visibilityContinents = 30))).contains("Visibility.Distance.Continents"))
        assertTrue(errors(RealmSettings(autoBalance = AutoBalanceConfig(minPlayers = 0))).contains("AutoBalance.MinPlayers"))
        assertTrue(errors(RealmSettings(progression = IndividualProgressionConfig(progressionLimit = 5, startingProgression = 8))).contains("must not exceed"))
    }
}

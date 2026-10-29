package com.lscardinali.pocketwow.realm

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RealmSettingsTest {

    /** Staged by tools/; set POCKETWOW_DIST_DIR when testing from a checkout without native/out. */
    private val distDir = File(System.getenv("POCKETWOW_DIST_DIR") ?: "../native/out/assets/worldserver")

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
    )

    @Test
    fun everyKeyExistsInTheDist() {
        // applyOverrides fails on unknown keys, which is what catches typos at realm start.
        AuthServer.applyOverrides(dist("worldserver.conf.dist"), changed.world.overrides())
        AuthServer.applyOverrides(dist("playerbots.conf.dist"), changed.bots.overrides())
    }

    @Test
    fun worldDefaultsAreTheDistValues() {
        val conf = dist("worldserver.conf.dist")
        WorldConfig.DEFAULT.overrides().forEach { (key, value) -> assertEquals(key, distValue(conf, key), value) }
    }

    @Test
    fun botDefaultsKeepModestAndOtherwiseTheDist() {
        val tuned = mapOf(
            "Playerbots.MinRandomBots" to "10",
            "Playerbots.MaxRandomBots" to "10",
            "Playerbots.RandomBotsPerInterval" to "5",
            "Playerbots.BotActiveAlone" to "5",
            "Playerbots.RandomBotJoinBG" to "0",
            "Playerbots.RandomBotGuildCount" to "0",
            "Playerbots.AddClassAccountPoolSize" to "1",
        )
        val conf = dist("playerbots.conf.dist")
        PlayerbotsConfig.MODEST.overrides().forEach { (key, value) ->
            assertEquals(key, tuned[key] ?: distValue(conf, key), value)
        }
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
        assertTrue(errors(RealmSettings(bots = bots.copy(minRandomBots = 20))).contains("must not exceed Playerbots.MaxRandomBots"))
        assertTrue(errors(RealmSettings(bots = bots.copy(maps = emptySet()))).contains("at least one map"))
        assertTrue(errors(RealmSettings(bots = bots.copy(maxRandomBots = 501))).contains("Playerbots.MaxRandomBots must be 0-500"))
        assertTrue(errors(RealmSettings(WorldConfig(maxPlayerLevel = 60))).contains("must not exceed MaxPlayerLevel"))
        assertTrue(errors(RealmSettings(WorldConfig(maxPlayerLevel = 60, startHeroicPlayerLevel = 61))).contains("StartHeroicPlayerLevel"))
        assertTrue(errors(RealmSettings(WorldConfig(maxPlayerLevel = 101))).contains("MaxPlayerLevel must be 1-100"))
        assertTrue(errors(RealmSettings(WorldConfig(xpKill = Float.NaN))).contains("Rate.XP.Kill"))
        assertTrue(errors(RealmSettings(WorldConfig(visibilityContinents = 30))).contains("Visibility.Distance.Continents"))
    }
}

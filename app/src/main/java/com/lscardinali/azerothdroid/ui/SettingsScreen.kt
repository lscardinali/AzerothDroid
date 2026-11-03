package com.lscardinali.azerothdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lscardinali.azerothdroid.realm.PlayerbotsConfig
import com.lscardinali.azerothdroid.realm.RealmSettings
import com.lscardinali.azerothdroid.realm.RealmSettingsStore
import com.lscardinali.azerothdroid.realm.WorldConfig
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Edits [RealmSettings] as a draft and saves it to [RealmSettingsStore]. The world server
 * reads the store when it starts, so nothing here touches a running realm.
 * [primaryFocus] lands on the first setting.
 */
@Composable
fun SettingsScreen(modifier: Modifier, primaryFocus: FocusRequester, realmRunning: Boolean) {
    val context = LocalContext.current
    val store = remember { RealmSettingsStore(context) }
    val saved by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<RealmSettings?>(null) }
    LaunchedEffect(saved) { if (draft == null) draft = saved }
    // The draft arrives after the tab's first frame, too late for the tab bar's focus request.
    val loaded = draft != null
    LaunchedEffect(loaded) {
        if (loaded) {
            withFrameNanos {}
            primaryFocus.requestFocus()
        }
    }
    // Fields whose text does not parse; their last good value is still in the draft.
    val unparsed = remember { mutableStateMapOf<String, Boolean>() }
    // Bumped on reset so every text field drops what was typed and shows the new value.
    var generation by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        val current = draft
        if (current == null) {
            Text("Loading", Modifier.padding(16.dp))
            return@Column
        }
        val errors = current.errors()
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel("Before you change things", readable = true) {
                Text(
                    if (realmRunning) {
                        "The realm is running. Saved changes apply the next time it starts: stop and start it."
                    } else {
                        "Saved changes apply the next time the realm starts."
                    },
                )
                Text(
                    "Memory-heavy options make bots or players spread over more of the world. This server " +
                        "never unloads map terrain, so whatever they load stays in memory until the realm stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Not a focus target itself: requesting focus here enters its first setting.
            Column(Modifier.focusRequester(primaryFocus), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                key(generation) {
                    BotsCard(current.bots, unparsed) { draft = current.copy(bots = it) }
                    BotWorldCard(current.bots, unparsed) { draft = current.copy(bots = it) }
                    RatesCard(current.world, unparsed) { draft = current.copy(world = it) }
                    WorldCard(current.world, unparsed) { draft = current.copy(world = it) }
                }
            }
            AboutCard()
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (current != saved) Text("Unsaved changes", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { store.save(current) } },
                    enabled = current != saved && errors.isEmpty() && unparsed.none { it.value },
                    modifier = Modifier
                        .weight(1f)
                        .focusRing(ButtonDefaults.shape),
                ) { Text("Save") }
                OutlinedButton(modifier = Modifier.weight(1f).focusRing(ButtonDefaults.shape), onClick = {
                    draft = RealmSettings.DEFAULT
                    unparsed.clear()
                    generation++
                }) { Text("Reset to defaults") }
            }
        }
    }
}

/** The notices the app's licences ask an interactive program to show. */
@Composable
private fun AboutCard() {
    Panel("About", readable = true) {
        Text(
            "AzerothDroid is free software with no warranty, under the GNU GPL, version 2 or later. It includes " +
                "AzerothCore, mod-playerbots, MariaDB and other software under their own licences, some of it " +
                "AGPL-3.0. Licence texts are in the app's assets/licenses folder.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Source code: https://github.com/lscardinali/AzerothDroid", style = MaterialTheme.typography.bodySmall)
        Text(
            "Unofficial. Not affiliated with or endorsed by Blizzard Entertainment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BotsCard(bots: PlayerbotsConfig, unparsed: SnapshotStateMap<String, Boolean>, onChange: (PlayerbotsConfig) -> Unit) {
    SettingsCard("Bots") {
        Toggle("Random bots", "Bots that live in the world on their own", bots.randomBotsEnabled) {
            onChange(bots.copy(randomBotsEnabled = it))
        }
        IntSetting(
            "Minimum random bots", bots.minRandomBots, PlayerbotsConfig.BOT_COUNT_RANGE, "bots", unparsed,
            memoryHeavy = true,
        ) { onChange(bots.copy(minRandomBots = it)) }
        IntSetting(
            "Maximum random bots", bots.maxRandomBots, PlayerbotsConfig.BOT_COUNT_RANGE, "bots", unparsed,
            note = "Equal to the minimum keeps the population steady", memoryHeavy = true,
        ) { onChange(bots.copy(maxRandomBots = it)) }
        IntSetting(
            "Random bot accounts", bots.accountCount, PlayerbotsConfig.ACCOUNT_COUNT_RANGE, "accounts", unparsed,
            note = "10 characters each; 0 = as many as the bot count needs",
        ) { onChange(bots.copy(accountCount = it)) }
        IntSetting(
            "Bots processed per cycle", bots.botsPerInterval, PlayerbotsConfig.BOTS_PER_INTERVAL_RANGE, "bots", unparsed,
            note = "Logins and updates per cycle; small batches avoid lag spikes",
        ) { onChange(bots.copy(botsPerInterval = it)) }
        IntSetting(
            "Activity when alone", bots.activeAlonePercent, PlayerbotsConfig.PERCENT_RANGE, "%", unparsed,
            note = "Share of bots simulated with no real player nearby; CPU-heavy",
        ) { onChange(bots.copy(activeAlonePercent = it)) }
        IntSetting("Lowest bot level", bots.minLevel, WorldConfig.LEVEL_RANGE, "level", unparsed) {
            onChange(bots.copy(minLevel = it))
        }
        IntSetting("Highest bot level", bots.maxLevel, WorldConfig.LEVEL_RANGE, "level", unparsed) {
            onChange(bots.copy(maxLevel = it))
        }
        Toggle("Match bot levels to players", "Caps bot levels at the highest online player", bots.syncLevelWithPlayers, memoryHeavy = true) {
            onChange(bots.copy(syncLevelWithPlayers = it))
        }
        Toggle("Gather bots in player zones", "Bots teleport to zones that hold a player", bots.concentrateInPlayerZone, memoryHeavy = true) {
            onChange(bots.copy(concentrateInPlayerZone = it))
        }
        Toggle("Only with a player online", "Bots log in after a real player does and leave after they log out", bots.disabledWithoutRealPlayer, memoryHeavy = true) {
            onChange(bots.copy(disabledWithoutRealPlayer = it))
        }
        PanelInset {
            Column { Label("Maps bots travel to", "At least one", memoryHeavy = true) }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerbotsConfig.MAPS.forEach { (id, name) ->
                    FilterChip(
                        selected = id in bots.maps,
                        onClick = { onChange(bots.copy(maps = if (id in bots.maps) bots.maps - id else bots.maps + id)) },
                        label = { Text(name) },
                        modifier = Modifier.focusRing(FilterChipDefaults.shape),
                    )
                }
            }
        }
        Toggle("Join battlegrounds", null, bots.joinBattlegrounds) { onChange(bots.copy(joinBattlegrounds = it)) }
        Toggle("Join Dungeon Finder", null, bots.joinLfg) { onChange(bots.copy(joinLfg = it)) }
        IntSetting(
            "Bot guilds", bots.guilds, PlayerbotsConfig.GUILD_COUNT_RANGE, "guilds", unparsed,
            note = "Created on first start; each takes 10 random bots",
        ) { onChange(bots.copy(guilds = it)) }
        IntSetting(
            "Addclass accounts", bots.addClassAccounts, PlayerbotsConfig.ADD_CLASS_ACCOUNTS_RANGE, "accounts", unparsed,
            note = "Bots for the .playerbots bot addclass command, 10 per account; 0 disables it",
        ) { onChange(bots.copy(addClassAccounts = it)) }
        IntSetting("Bots per player", bots.maxAddedBots, PlayerbotsConfig.MAX_ADDED_BOTS_RANGE, "bots", unparsed, note = "Party and raid bots one player controls") {
            onChange(bots.copy(maxAddedBots = it))
        }
        Toggle("Log in my other characters as bots", null, bots.altBotAutologin) { onChange(bots.copy(altBotAutologin = it)) }
        Toggle("Random bots invite players", "To groups, raids and guilds", bots.invitePlayers) { onChange(bots.copy(invitePlayers = it)) }
        Toggle("Summon bots on joining a group", null, bots.summonWhenGroup) { onChange(bots.copy(summonWhenGroup = it)) }
    }
}

/** Where bots are and how levels spread around the player: what makes the world feel lived in. */
@Composable
private fun BotWorldCard(bots: PlayerbotsConfig, unparsed: SnapshotStateMap<String, Boolean>, onChange: (PlayerbotsConfig) -> Unit) {
    SettingsCard("Bots around you") {
        Toggle(
            "Follow players' levels", "Bot levels lean toward the brackets real players are in",
            bots.dynamicLevelDistribution,
        ) { onChange(bots.copy(dynamicLevelDistribution = it)) }
        FloatSetting(
            "Pull toward your level", bots.levelDistributionWeight, PlayerbotsConfig.WEIGHT_RANGE, unparsed,
            note = "1 is mild; 10-15 puts about a third of bots at your level",
        ) { onChange(bots.copy(levelDistributionWeight = it)) }
        FloatSetting(
            "Spread to nearby levels", bots.levelNeighborShare, PlayerbotsConfig.SHARE_RANGE, unparsed,
            note = "Share of that pull the levels just above and below also get; 0.5 = half",
        ) { onChange(bots.copy(levelNeighborShare = it)) }
        Toggle(
            "Both factions follow all players", "Your level shapes enemy bots' levels too, for PvP",
            bots.levelSyncFactions,
        ) { onChange(bots.copy(levelSyncFactions = it)) }
        Toggle(
            "Close to your exact level", "5-level brackets instead of 10, so bots are within a few levels of you",
            bots.narrowLevelBrackets,
        ) { onChange(bots.copy(narrowLevelBrackets = it)) }
        Toggle(
            "Low-level bots of every race", "Starter bots of all your faction's races come to your zone, not only its own",
            bots.anyStartingArea,
        ) { onChange(bots.copy(anyStartingArea = it)) }
        IntSetting(
            "Shortest time between teleports", bots.teleportMinSeconds / 60, minutes(PlayerbotsConfig.TELEPORT_RANGE),
            "minutes", unparsed, note = "Shorter lets bots follow you between zones", memoryHeavy = true,
        ) { onChange(bots.copy(teleportMinSeconds = it * 60)) }
        IntSetting(
            "Longest time between teleports", bots.teleportMaxSeconds / 60, minutes(PlayerbotsConfig.TELEPORT_RANGE),
            "minutes", unparsed, memoryHeavy = true,
        ) { onChange(bots.copy(teleportMaxSeconds = it * 60)) }
        IntSetting(
            "Teleports to a capital city", (bots.cityChance * 100).roundToInt(), PlayerbotsConfig.PERCENT_RANGE, "%",
            unparsed, note = "Bots of level 10+ that go to a banker instead of a questing zone",
        ) { onChange(bots.copy(cityChance = it / 100f)) }
        IntSetting(
            "Shortest time a bot stays online", bots.inWorldMinSeconds / 60, minutes(PlayerbotsConfig.TELEPORT_RANGE),
            "minutes", unparsed, note = "Then it logs out and another bot logs in near you",
        ) { onChange(bots.copy(inWorldMinSeconds = it * 60)) }
        IntSetting(
            "Longest time a bot stays online", bots.inWorldMaxSeconds / 60, minutes(PlayerbotsConfig.TELEPORT_RANGE),
            "minutes", unparsed,
        ) { onChange(bots.copy(inWorldMaxSeconds = it * 60)) }
        Toggle(
            "Rotate bots online and offline", "A pool larger than the online count takes turns: new faces, same memory",
            bots.periodicOnlineOffline,
        ) { onChange(bots.copy(periodicOnlineOffline = it)) }
        FloatSetting(
            "Pool size", bots.onlineOfflineRatio, PlayerbotsConfig.RATIO_RANGE, unparsed,
            note = "Times the online count; more characters to create on first start",
        ) { onChange(bots.copy(onlineOfflineRatio = it)) }
        IntSetting(
            "Start pausing far bots at", bots.smartScaleFloorMs, PlayerbotsConfig.UPDATE_MS_RANGE, "ms update time",
            unparsed, note = "Adaptive load: idle bots far from you pause as the server slows down",
        ) { onChange(bots.copy(smartScaleFloorMs = it)) }
        IntSetting(
            "Pause all far bots at", bots.smartScaleCeilingMs, PlayerbotsConfig.UPDATE_MS_RANGE, "ms update time",
            unparsed, note = "Bots near you always stay active",
        ) { onChange(bots.copy(smartScaleCeilingMs = it)) }
    }
}

private fun minutes(seconds: IntRange) = (seconds.first / 60)..(seconds.last / 60)

@Composable
private fun RatesCard(world: WorldConfig, unparsed: SnapshotStateMap<String, Boolean>, onChange: (WorldConfig) -> Unit) {
    SettingsCard("Rates") {
        RateSetting("Kill XP", world.xpKill, unparsed) { onChange(world.copy(xpKill = it)) }
        RateSetting("Quest XP", world.xpQuest, unparsed) { onChange(world.copy(xpQuest = it)) }
        RateSetting("Exploration XP", world.xpExplore, unparsed) { onChange(world.copy(xpExplore = it)) }
        RateSetting("Rested XP gain", world.restXp, unparsed) { onChange(world.copy(restXp = it)) }
        RateSetting("Item drops", world.itemDrop, unparsed, note = "All qualities") { onChange(world.copy(itemDrop = it)) }
        RateSetting("Money drops", world.moneyDrop, unparsed) { onChange(world.copy(moneyDrop = it)) }
        RateSetting("Honor", world.honor, unparsed) { onChange(world.copy(honor = it)) }
        RateSetting("Reputation", world.reputation, unparsed) { onChange(world.copy(reputation = it)) }
        IntSetting(
            "Skill points per skill-up", world.skillGain, WorldConfig.SKILL_GAIN_RANGE, "points", unparsed,
            note = "Professions, weapons and defense",
        ) { onChange(world.copy(skillGain = it)) }
    }
}

@Composable
private fun WorldCard(world: WorldConfig, unparsed: SnapshotStateMap<String, Boolean>, onChange: (WorldConfig) -> Unit) {
    SettingsCard("World") {
        IntSetting(
            "Max player level", world.maxPlayerLevel, WorldConfig.LEVEL_RANGE, "level", unparsed,
            note = "80 is the WotLK cap; higher needs custom client data",
        ) { onChange(world.copy(maxPlayerLevel = it)) }
        IntSetting("Starting level", world.startPlayerLevel, 1..world.maxPlayerLevel, "level", unparsed) {
            onChange(world.copy(startPlayerLevel = it))
        }
        IntSetting("Death knight starting level", world.startHeroicPlayerLevel, 1..world.maxPlayerLevel, "level", unparsed) {
            onChange(world.copy(startHeroicPlayerLevel = it))
        }
        IntSetting("Starting money", world.startGold, WorldConfig.START_GOLD_RANGE, "gold", unparsed) {
            onChange(world.copy(startGold = it))
        }
        Toggle("All flight paths", "New characters know every flight path of both factions", world.allFlightPaths) {
            onChange(world.copy(allFlightPaths = it))
        }
        Choice(
            "Instant flights", world.instantFlightPaths,
            listOf(0 to "Off", 1 to "On", 2 to "Player's choice"),
        ) { onChange(world.copy(instantFlightPaths = it)) }
        Toggle("Ignore dungeon level requirements", null, world.instanceIgnoreLevel) { onChange(world.copy(instanceIgnoreLevel = it)) }
        Toggle("Enter raids without a raid group", null, world.instanceIgnoreRaid) { onChange(world.copy(instanceIgnoreRaid = it)) }
        IntSetting("Daily instance reset", world.instanceResetHour, 0..23, "hour of day", unparsed) {
            onChange(world.copy(instanceResetHour = it))
        }
        Choice(
            "Wintergrasp", world.wintergrasp,
            listOf(1 to "On", 0 to "Battle off", 2 to "Fully off"),
            note = "Battle off still runs the zone; fully off saves the most",
            memoryHeavy = true,
        ) { onChange(world.copy(wintergrasp = it)) }
        IntSetting(
            "View distance outdoors", world.visibilityContinents, WorldConfig.VISIBILITY_RANGE, "yards", unparsed,
            memoryHeavy = true,
        ) { onChange(world.copy(visibilityContinents = it)) }
        IntSetting(
            "View distance in dungeons", world.visibilityInstances, WorldConfig.VISIBILITY_RANGE, "yards", unparsed,
            memoryHeavy = true,
        ) { onChange(world.copy(visibilityInstances = it)) }
    }
}

@Composable
private fun IntSetting(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    unparsed: SnapshotStateMap<String, Boolean>,
    note: String? = null,
    memoryHeavy: Boolean = false,
    onChange: (Int) -> Unit,
) = NumberSetting(
    label, value.toString(), "${range.first}-${range.last} $unit", KeyboardType.Number, unparsed, note, memoryHeavy,
    parse = { text -> text.trim().toIntOrNull()?.takeIf { it in range } },
    onChange = onChange,
)

/** Multipliers; 1 is blizzlike. */
@Composable
private fun RateSetting(
    label: String,
    value: Float,
    unparsed: SnapshotStateMap<String, Boolean>,
    note: String? = null,
    onChange: (Float) -> Unit,
) {
    val range = WorldConfig.RATE_RANGE
    NumberSetting(
        label, rate(value), "x${rate(range.start)}-x${rate(range.endInclusive)}, 1 = normal",
        KeyboardType.Decimal, unparsed, note, memoryHeavy = false,
        parse = { text -> text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in range } },
        onChange = onChange,
    )
}

/** A decimal value within [range]. */
@Composable
private fun FloatSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unparsed: SnapshotStateMap<String, Boolean>,
    note: String? = null,
    onChange: (Float) -> Unit,
) = NumberSetting(
    label, rate(value), "${rate(range.start)}-${rate(range.endInclusive)}", KeyboardType.Decimal, unparsed, note,
    memoryHeavy = false,
    parse = { text -> text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in range } },
    onChange = onChange,
)

/**
 * A text field that pushes only parsed, in-range values to [onChange]; anything else is
 * flagged in [unparsed] so the screen can hold back saving. Range errors that span fields
 * (min above max) come from the model instead.
 */
@Composable
private fun <T> NumberSetting(
    label: String,
    initial: String,
    rangeText: String,
    keyboard: KeyboardType,
    unparsed: SnapshotStateMap<String, Boolean>,
    note: String?,
    memoryHeavy: Boolean,
    parse: (String) -> T?,
    onChange: (T) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val invalid = parse(text) == null
    ControllerTextField(
        value = text,
        onValueChange = {
            text = it
            val parsed = parse(it)
            unparsed[label] = parsed == null
            if (parsed != null) onChange(parsed)
        },
        label = label,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = {
            Column {
                Text(if (invalid) "Must be $rangeText" else rangeText)
                note?.let { Text(it) }
                if (memoryHeavy) MemoryHeavy()
            }
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun Toggle(label: String, note: String?, checked: Boolean, memoryHeavy: Boolean = false, onChange: (Boolean) -> Unit) {
    SwitchRow(label, note, checked, extra = if (memoryHeavy) ({ MemoryHeavy() }) else null, onChange = onChange)
}

@Composable
private fun Choice(
    label: String,
    value: Int,
    options: List<Pair<Int, String>>,
    note: String? = null,
    memoryHeavy: Boolean = false,
    onChange: (Int) -> Unit,
) {
    PanelInset {
        Column { Label(label, note, memoryHeavy) }
        // Equal heights when a long label wraps.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { index, (option, name) ->
                val shape = SegmentedButtonDefaults.itemShape(index, options.size)
                SegmentedButton(
                    selected = value == option,
                    onClick = { onChange(option) },
                    shape = shape,
                    label = { Text(name, textAlign = TextAlign.Center) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .focusRing(shape, outset = 0.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(label: String, note: String?, memoryHeavy: Boolean) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (memoryHeavy) MemoryHeavy()
}

@Composable
private fun MemoryHeavy() {
    Text("Memory-heavy", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
}

/** Cards of [SwitchRow]s and fields; fields, labels and chips pad themselves to line up. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Panel(title, contentPadding = RowPanelPadding) { content() }
}

private fun rate(value: Float) = if (value % 1f == 0f) value.toLong().toString() else value.toString()

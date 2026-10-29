package com.lscardinali.pocketwow.realm

/**
 * The mod-playerbots settings the app controls, rendered as overrides on top of the
 * module's playerbots.conf.dist. Everything else keeps the module defaults.
 *
 * [MODEST] is the starting point for weak phones; app settings will later build other
 * values of this class and the world server rewrites modules/playerbots.conf on start.
 */
data class PlayerbotsConfig(
    /** Random bots kept logged in (min = max, so the population does not drift). */
    val randomBots: Int,
    /** Bots logged in or updated per manager cycle; small batches avoid load spikes. */
    val botsPerInterval: Int,
    /** Percent of bots simulated when no real player is near (others idle). */
    val activeAlonePercent: Int,
    /** Bots join battlegrounds on their own. */
    val joinBattlegrounds: Boolean,
    /** Random bot guilds created on first start. */
    val guilds: Int,
    /** Accounts reserved for the addclass command, 10 characters each, created on start. */
    val addClassAccounts: Int,
) {
    fun overrides(): Map<String, String> = mapOf(
        "Playerbots.MinRandomBots" to randomBots.toString(),
        "Playerbots.MaxRandomBots" to randomBots.toString(),
        "Playerbots.RandomBotsPerInterval" to botsPerInterval.toString(),
        "Playerbots.BotActiveAlone" to activeAlonePercent.toString(),
        "Playerbots.RandomBotJoinBG" to (if (joinBattlegrounds) "1" else "0"),
        "Playerbots.RandomBotGuildCount" to guilds.toString(),
        "Playerbots.AddClassAccountPoolSize" to addClassAccounts.toString(),
    )

    companion object {
        val MODEST = PlayerbotsConfig(
            randomBots = 10,
            botsPerInterval = 5,
            activeAlonePercent = 5,
            joinBattlegrounds = false,
            guilds = 0,
            addClassAccounts = 1,
        )
    }
}

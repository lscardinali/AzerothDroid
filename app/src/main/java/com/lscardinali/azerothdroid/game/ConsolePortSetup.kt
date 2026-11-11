package com.lscardinali.azerothdroid.game

import android.util.Log
import app.gamenative.azerothdroid.ConsolePortLayout
import java.io.File

/**
 * Calibrates the ConsolePortLK addon for the keys the gamepad sends ([ConsolePortLayout], WoWpadX's
 * defaults), so the addon starts ready instead of running its controller wizard.
 *
 * ConsolePortLK keeps the calibration (its button name to the key it arrives as) in
 * WTF/Account/<ACCOUNT>/SavedVariables/ConsolePort.lua and re-applies it with SetBinding every time it
 * loads; bindings-cache.wtf holds the same bindings for the game. Both are written for every account
 * that has played in this client. An account that has never logged in gets the addon's wizard once,
 * which works with the pad too, since every button sends a steady key.
 *
 * Only the calibration and stick settings change; the rest of the player's ConsolePort setup stays.
 */
object ConsolePortSetup {

    private const val TAG = "ConsolePortSetup"
    private const val BACKUP = ".before-azerothdroid"

    /** Settings the pad depends on: 16-way stick (W/A/S/D + H/V), no paddles (T3-T6). */
    private val SETTINGS = linkedMapOf(
        "type" to "\"XBOX\"",
        "stickRadialType" to "1",
        "stickRadialLocal" to "false",
        "skipGuideBtn" to "false",
        "skipCP_T3" to "true",
        "skipCP_T4" to "true",
        "skipCP_T5" to "true",
        "skipCP_T6" to "true",
    )

    fun apply(client: File) {
        if (!hasConsolePort(client)) return
        val accounts = child(child(client, "WTF") ?: return, "Account") ?: return
        accounts.listFiles()?.filter { it.isDirectory }?.forEach { account ->
            runCatching {
                writeBindings(account)
                writeSettings(account)
            }.onFailure { Log.w(TAG, "Could not set up ConsolePort for ${account.name}", it) }
        }
    }

    private fun hasConsolePort(client: File): Boolean {
        val addons = child(child(client, "Interface") ?: return false, "AddOns") ?: return false
        return child(addons, "ConsolePort") != null
    }

    /** bindings-cache.wtf: our keys bound to ConsolePort's buttons; other bindings on those keys go. */
    private fun writeBindings(account: File) {
        val file = child(account, "bindings-cache.wtf") ?: File(account, "bindings-cache.wtf")
        val ours = ConsolePortLayout.CALIBRATION.map { (button, key) -> "bind $key $button" }
        val keys = ConsolePortLayout.CALIBRATION.values.toSet()
        val buttons = ConsolePortLayout.CALIBRATION.keys
        val kept = if (file.exists()) {
            file.readLines().map { it.trim() }.filter { line ->
                val parts = line.split(Regex("\\s+"))
                line.isNotEmpty() &&
                    !(parts.size >= 3 && parts[0].equals("bind", true) && (parts[1] in keys || parts[2] in buttons))
            }
        } else {
            emptyList()
        }
        write(file, (kept + ours).joinToString("\n", postfix = "\n"))
    }

    /** SavedVariables/ConsolePort.lua: our calibration table and [SETTINGS] inside ConsolePortSettings. */
    private fun writeSettings(account: File) {
        val dir = child(account, "SavedVariables") ?: File(account, "SavedVariables").apply { mkdirs() }
        val file = child(dir, "ConsolePort.lua") ?: File(dir, "ConsolePort.lua")
        val calibration = buildString {
            append("\t[\"calibration\"] = {\n")
            ConsolePortLayout.CALIBRATION.forEach { (button, key) -> append("\t\t[\"$button\"] = \"$key\",\n") }
            append("\t},\n")
        }
        val text = if (file.exists()) file.readText() else ""
        val start = Regex("""(?m)^ConsolePortSettings\s*=\s*\{\s*$""").find(text)
        val updated = if (start == null) {
            // No settings yet: just ours. The addon fills in its defaults around them.
            buildString {
                append("ConsolePortSettings = {\n")
                SETTINGS.forEach { (key, value) -> append("\t[\"$key\"] = $value,\n") }
                append(calibration)
                append("}\n")
                append(text)
            }
        } else {
            // The table ends at the first "}" alone on a line after it (SavedVariables are written that way).
            val end = Regex("""(?m)^\}\s*$""").find(text, start.range.last) ?: return
            var body = text.substring(start.range.last + 1, end.range.first)
            body = body.replace(Regex("""(?s)\t\["calibration"\] = \{.*?\n\t\},\n"""), "")
            SETTINGS.keys.forEach { key -> body = body.replace(Regex("""(?m)^\t\["$key"\] = [^\n]*\n"""), "") }
            val ours = SETTINGS.map { (key, value) -> "\t[\"$key\"] = $value,\n" }.joinToString("") + calibration
            text.substring(0, start.range.last + 1) + "\n" + ours + body.trimStart('\n') + text.substring(end.range.first)
        }
        write(file, withoutEmptyBindingSet(updated))
    }

    /**
     * Removes a ConsolePortBindingSet that holds no bindings, e.g. `{ {} }` left by another launcher.
     * ConsolePortLK only copies its default controller bindings into a missing set, so an empty one
     * leaves every button without an action. A set with any binding in it is the player's and stays.
     */
    private fun withoutEmptyBindingSet(text: String): String {
        val start = Regex("""(?m)^ConsolePortBindingSet\s*=\s*\{\s*$""").find(text) ?: return text
        val end = Regex("""(?m)^\}\s*$""").find(text, start.range.last) ?: return text
        val body = text.substring(start.range.last + 1, end.range.first)
        if (body.contains("[\"")) return text
        Log.i(TAG, "Removing an empty ConsolePortBindingSet so ConsolePort loads its default bindings")
        return text.substring(0, start.range.first) + text.substring(end.range.last + 1).trimStart('\n')
    }

    /** Keeps the player's original once, beside it. */
    private fun write(file: File, text: String) {
        if (file.exists()) {
            if (file.readText() == text) return
            val backup = File(file.parentFile, file.name + BACKUP)
            if (!backup.exists()) file.copyTo(backup)
        }
        file.writeText(text)
    }

    /** Case-insensitive child: the client's folders vary in case (Interface/interface, WTF/wtf). */
    private fun child(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

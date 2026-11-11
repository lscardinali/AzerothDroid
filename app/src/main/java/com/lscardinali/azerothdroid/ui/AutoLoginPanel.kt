package com.lscardinali.azerothdroid.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lscardinali.azerothdroid.game.AutoLoginStore
import com.lscardinali.azerothdroid.realm.RealmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which account the game on this phone logs into by itself (see GameLauncher and AutoLogin).
 * [revision] changes when another panel sets it, e.g. after creating an account.
 */
@Composable
fun AutoLoginPanel(revision: Int) {
    val context = LocalContext.current
    val store = remember { AutoLoginStore(context) }
    val scope = rememberCoroutineScope()
    var current by remember(revision) { mutableStateOf(store.get()?.account) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val buttonModifier = Modifier.fillMaxWidth().focusRing(ButtonDefaults.shape)

    Panel("Log in automatically", contentPadding = RowPanelPadding) {
        PanelInset {
            Text(
                current?.let { "When you tap Play, the game logs in as $it once the realm is up." }
                    ?: "Choose an account, and Play logs the game into it by itself.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "The password is kept in the app's private storage, which only this app can read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current != null) {
                OutlinedButton(
                    onClick = {
                        store.clear()
                        current = null
                        message = null
                    },
                    modifier = buttonModifier,
                ) { Text("Turn off") }
            }
            ControllerTextField(
                name, { name = it.filter(Char::isLetterOrDigit).take(16) }, "Account",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            ControllerTextField(
                password, { password = it.take(16) }, "Password",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { RealmService.accounts()?.checkPassword(name, password) == true }.getOrDefault(false)
                        }
                        if (ok) {
                            store.set(name, password)
                            current = name.uppercase()
                            message = true to "The game will log in as ${name.uppercase()}"
                            name = ""
                            password = ""
                        } else {
                            message = false to "Wrong account name or password"
                        }
                    }
                },
                enabled = name.isNotEmpty() && password.isNotEmpty(),
                modifier = buttonModifier,
            ) { Text("Use this account") }
            message?.let { (ok, text) ->
                Text(text, color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

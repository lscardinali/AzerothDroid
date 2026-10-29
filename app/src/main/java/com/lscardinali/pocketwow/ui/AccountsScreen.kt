package com.lscardinali.pocketwow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lscardinali.pocketwow.realm.RealmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Game accounts in the auth database. Needs the realm's database to be running. */
@Composable
fun AccountsScreen(modifier: Modifier, databaseRunning: Boolean) {
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<List<String>?>(null) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gm by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    suspend fun refresh() {
        existing = withContext(Dispatchers.IO) { runCatching { RealmService.accounts()?.list() }.getOrNull() }
    }
    // The database comes up a few seconds after the service; load (again) once it does.
    LaunchedEffect(databaseRunning) { if (databaseRunning) refresh() else existing = null }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Accounts", "Log in to the realm with these from any 3.3.5a client")
        if (!databaseRunning) {
            Panel { Text("Start the realm to see and create accounts.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        Panel("On this realm") {
            val accounts = existing
            when {
                accounts == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                accounts.isEmpty() -> Text("No accounts yet: create one below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> accounts.forEachIndexed { index, account ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(account, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Panel("New account") {
            OutlinedTextField(
                name, { name = it.filter(Char::isLetterOrDigit).take(16) },
                label = { Text("Name") }, singleLine = true,
                supportingText = { Text("Up to 16 letters or digits") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it.take(16) },
                label = { Text("Password") }, singleLine = true,
                supportingText = { Text("Up to 16 characters; the client ignores case") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Game master", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "GM commands such as .gm on or .playerbots",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(gm, { gm = it })
            }
            Button(
                onClick = {
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching {
                                val accounts = checkNotNull(RealmService.accounts()) { "Database not running" }
                                accounts.create(name, password, gm)
                                true to "Created ${name.uppercase()}"
                            }.getOrElse {
                                val text = it.message ?: it.toString()
                                false to if ("Duplicate entry" in text) "${name.uppercase()} already exists" else text
                            }
                        }
                        if (message?.first == true) {
                            name = ""
                            password = ""
                        }
                        refresh()
                    }
                },
                enabled = name.isNotEmpty() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create account") }
            message?.let { (ok, text) ->
                Text(text, color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

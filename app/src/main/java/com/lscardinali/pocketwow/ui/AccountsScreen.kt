package com.lscardinali.pocketwow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lscardinali.pocketwow.realm.RealmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Game accounts in the auth database. Needs the realm's database to be running.
 * [primaryFocus] goes on the name field; with the database down there is nothing to focus.
 */
@Composable
fun AccountsScreen(modifier: Modifier, primaryFocus: FocusRequester, databaseRunning: Boolean) {
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
        if (!databaseRunning) {
            Panel { Text("Start the realm to see and create accounts.", style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }
        Panel("On this realm", contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp), readable = true) {
            val accounts = existing
            when {
                accounts == null -> Text(
                    "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                accounts.isEmpty() -> Text(
                    "No accounts yet: create one below.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> accounts.forEach { account ->
                    ListItem(
                        headlineContent = { Text(account) },
                        leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                        colors = cardListItemColors(),
                    )
                }
            }
        }
        Panel("New account", contentPadding = RowPanelPadding) {
            PanelInset {
                ControllerTextField(
                    name, { name = it.filter(Char::isLetterOrDigit).take(16) }, "Name",
                    supportingText = { Text("Up to 16 letters or digits") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.focusRequester(primaryFocus),
                )
                ControllerTextField(
                    password, { password = it.take(16) }, "Password",
                    supportingText = { Text("Up to 16 characters; the client ignores case") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            SwitchRow("Game master", "GM commands such as .gm on or .playerbots", gm) { gm = it }
            PanelInset {
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
                                // Create just disabled itself; start the next account.
                                primaryFocus.requestFocus()
                            }
                            refresh()
                        }
                    },
                    enabled = name.isNotEmpty() && password.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(ButtonDefaults.shape),
                ) { Text("Create account") }
                message?.let { (ok, text) ->
                    Text(text, color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

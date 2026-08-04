package com.mailsearch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mailsearch.app.UiState
import com.mailsearch.app.auth.AuthClient
import com.mailsearch.app.graph.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** First run: the one thing the app cannot guess. */
@Composable
fun SetupScreen(
    initialClientId: String,
    initialTenant: String,
    onSave: (String, String) -> Unit,
) {
    var clientId by remember { mutableStateOf(initialClientId) }
    var tenant by remember { mutableStateOf(initialTenant) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set up Mail Search", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Microsoft requires every app that reads a mailbox to be registered. " +
                "Register one free in the Azure portal (it takes a couple of minutes — " +
                "the README has the steps), then paste its Application (client) ID here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Application (client) ID") },
            placeholder = { Text("00000000-0000-0000-0000-000000000000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = tenant,
            onValueChange = { tenant = it },
            label = { Text("Tenant") },
            supportingText = { Text("Leave as \"common\" unless your workplace told you otherwise.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("In the app registration, make sure:", fontWeight = FontWeight.SemiBold)
                Text("• Authentication → Allow public client flows: Yes", style = MaterialTheme.typography.bodySmall)
                Text(
                    "• Authentication → Mobile and desktop applications → add the " +
                        "redirect URI:\n${AuthClient.DEFAULT_REDIRECT}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("• API permissions → Microsoft Graph → Mail.Read, User.Read, offline_access",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = { onSave(clientId.trim(), tenant.trim()) },
            enabled = clientId.trim().length > 10,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

@Composable
fun SignInScreen(account: String, error: String, busy: Boolean, onSignIn: () -> Unit, onSetup: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mail Search", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Search your whole Outlook mailbox from your phone. " +
                "Your mail stays on Microsoft's servers — nothing is downloaded or stored here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Signing in…")
            } else {
                Text(if (account.isEmpty()) "Sign in with Outlook" else "Sign in as $account")
            }
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    error,
                    Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSetup) { Text("Change app registration") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: UiState,
    account: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpen: (Message) -> Unit,
    onLoadMore: () -> Unit,
    onRecent: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mail Search", style = MaterialTheme.typography.titleMedium)
                        if (account.isNotEmpty()) {
                            Text(
                                account,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                placeholder = { Text("Search your mail…") },
                singleLine = true,
                trailingIcon = {
                    if (state.searching) {
                        CircularProgressIndicator(Modifier.size(18.dp).padding(end = 2.dp), strokeWidth = 2.dp)
                    } else if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChanged("") }) { Text("×", fontSize = 20.sp) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (state.error.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        state.error,
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.notes.forEach { note ->
                Text(
                    note,
                    Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                state.query.isBlank() -> IdleHelp(state.recent, onRecent)
                state.hasSearched && state.results.isEmpty() && !state.searching ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No mail matches that.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.results, key = { it.id }) { message ->
                        ResultRow(message, state.terms) { onOpen(message) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    if (state.hasMore) {
                        item {
                            TextButton(
                                onClick = onLoadMore,
                                enabled = !state.loadingMore,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            ) { Text(if (state.loadingMore) "Loading…" else "Show more results") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleHelp(recent: List<String>, onRecent: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recent.isNotEmpty()) {
            Text("Recent searches", style = MaterialTheme.typography.titleSmall)
            recent.forEach { entry ->
                Text(
                    entry,
                    Modifier.fillMaxWidth().clickable { onRecent(entry) }.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text("Type anything you remember", style = MaterialTheme.typography.titleSmall)
        Text(
            "A word, a name, a phrase — Outlook searches your whole mailbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        listOf(
            "from:alice" to "mail from a particular person",
            "\"purchase order\"" to "that exact phrase",
            "invoice -newsletter" to "matches invoice, excludes newsletter",
            "has:attachment" to "only mail with attachments",
            "after:2024-01-01" to "mail since a date",
        ).forEach { (syntax, meaning) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(syntax, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(10.dp))
                Text(meaning, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultRow(message: Message, terms: List<String>, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                highlighted(message.displaySubject, terms),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (message.isRead) FontWeight.Medium else FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                shortDate(message.receivedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.sender,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (message.hasAttachments) {
                Spacer(Modifier.width(6.dp))
                Text("📎", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (message.preview.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                highlighted(message.preview, terms),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    message: Message,
    body: String,
    loading: Boolean,
    terms: List<String>,
    onBack: () -> Unit,
    onOpenInOutlook: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                },
                actions = {
                    if (message.webLink.isNotEmpty()) {
                        TextButton(onClick = { onOpenInOutlook(message.webLink) }) { Text("Outlook") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(message.displaySubject, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LabelLine("From", listOfNotNull(
                message.fromName.ifEmpty { null }, message.fromAddress.ifEmpty { null },
            ).joinToString(" · "))
            if (message.toRecipients.isNotEmpty()) LabelLine("To", message.toRecipients)
            LabelLine("Date", longDate(message.receivedAt))
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            if (loading && body.isEmpty()) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    highlighted(body.ifEmpty { "(this message has no text)" }, terms),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    Text("Loading the rest…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LabelLine(label: String, value: String) {
    if (value.isBlank()) return
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Marks the searched-for words inside a piece of text. */
internal fun highlighted(text: String, terms: List<String>) = buildAnnotatedString {
    append(text)
    val wanted = terms.filter { it.length >= 2 }
    if (wanted.isEmpty()) return@buildAnnotatedString
    val lowered = text.lowercase()
    for (term in wanted) {
        val needle = term.lowercase()
        var index = lowered.indexOf(needle)
        while (index >= 0) {
            addStyle(
                SpanStyle(background = Color(0x66FFD54F), fontWeight = FontWeight.SemiBold),
                index,
                index + needle.length,
            )
            index = lowered.indexOf(needle, index + needle.length)
        }
    }
}

private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val SHORT_WITH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy")
private val LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm")

internal fun shortDate(iso: String): String {
    val moment = parseInstant(iso) ?: return ""
    val local = moment.atZone(ZoneId.systemDefault())
    val now = Instant.now().atZone(ZoneId.systemDefault())
    return if (local.year == now.year) local.format(SHORT) else local.format(SHORT_WITH_YEAR)
}

internal fun longDate(iso: String): String =
    parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(LONG).orEmpty()

private fun parseInstant(iso: String): Instant? =
    if (iso.isEmpty()) null else runCatching { Instant.parse(iso) }.getOrNull()

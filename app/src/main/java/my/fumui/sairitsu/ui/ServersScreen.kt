package my.fumui.sairitsu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import my.fumui.sairitsu.data.CatalogSummary
import my.fumui.sairitsu.data.SavedServer
import my.fumui.sairitsu.data.SyncStatus
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Online Servers: the user's servers as cards (tap to browse; Edit and Remove on the left) and "Add server". */
@Composable
fun ServersScreen(vm: ServersViewModel, onOpen: (SavedServer) -> Unit, onBack: () -> Unit) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val catalogs by vm.catalogSummaries.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedServer?>(null) }
    var removing by remember { mutableStateOf<SavedServer?>(null) }
    var refreshing by remember { mutableStateOf<SavedServer?>(null) }

    val scope = rememberCoroutineScope()
    // Shown from a scope that outlives the effect: clearing the message re-keys the effect, which would cancel it.
    LaunchedEffect(message) {
        message?.let {
            vm.messageShown()
            scope.launch { snackbar.showSnackbar(it) }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Online Servers", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { adding = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add server")
                }
            }
            HorizontalDivider()
            if (servers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No servers yet. Add one to browse levels.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(servers, key = { it.address }) { server ->
                        ServerRow(
                            server,
                            cards[server.address],
                            catalogs[server.address],
                            onOpen = { onOpen(server) },
                            onRefresh = { refreshing = server },
                            onEdit = { editing = server },
                            onRemove = { removing = server },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        ServerDialog(
            heading = "Add server",
            confirm = "Add",
            initialAddress = "",
            initialTitle = null,
            onConfirm = { address, _ ->
                vm.add(address)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { server ->
        ServerDialog(
            heading = "Edit server",
            confirm = "Save",
            initialAddress = server.url.removePrefix("https://"),
            initialTitle = server.title,
            onConfirm = { address, title ->
                vm.edit(server, address, title)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    refreshing?.let { server ->
        val count = catalogs[server.address]?.count ?: 0
        AlertDialog(
            onDismissRequest = { refreshing = null },
            title = { Text("Refresh all levels?") },
            text = {
                Text(
                    "Downloads ${server.title}'s whole level list again to pick up edited and removed levels" +
                        (if (count > 0) " (%,d levels). ".format(count) else ". ") +
                        "This can take a few minutes; the saved list stays usable meanwhile.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.refreshLevels(server)
                    refreshing = null
                }) { Text("Refresh") }
            },
            dismissButton = { TextButton(onClick = { refreshing = null }) { Text("Cancel") } },
        )
    }
    removing?.let { server ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this server?") },
            text = { Text("${server.title}\n${server.address}\n\nDownloaded levels stay in Play.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove(server.address)
                        removing = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerRow(
    server: SavedServer,
    card: ServerCard?,
    catalog: CatalogSummary?,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Banner(card?.bannerUrl, server.title)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                catalogLine(catalog)?.let { line ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (catalog?.status is SyncStatus.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                card?.description?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Trailing actions: standard 48dp icon buttons in the neutral icon color; the destructive
            // step (red) is the confirmation dialog, not the button.
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Sync, contentDescription = "Refresh all levels of ${server.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit ${server.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${server.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The server's banner, or its initial on a colored tile while there is none. */
@Composable
private fun Banner(url: String?, title: String) {
    val hue = (title.hashCode().absoluteValue % 360).toFloat()
    Box(
        Modifier.width(200.dp).height(84.dp).clip(RoundedCornerShape(10.dp)).background(Color.hsl(hue, 0.35f, 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.trim().take(1).uppercase(), color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 36.sp)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Address and (when editing) title; a blank title is fetched from the server. */
@Composable
private fun ServerDialog(
    heading: String,
    confirm: String,
    initialAddress: String,
    initialTitle: String?,
    onConfirm: (address: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by remember { mutableStateOf(initialAddress) }
    var title by remember { mutableStateOf(initialTitle.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initialTitle != null) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text("Use the server's name") },
                    )
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    label = { Text("Server address") },
                    placeholder = { Text("example.com") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(address, title) }, enabled = address.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

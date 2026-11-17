package my.fumui.sairitsu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import my.fumui.sairitsu.library.CollectionCard
import my.fumui.sairitsu.library.collectionNameError
import kotlinx.coroutines.launch
import java.io.File

/** Main Menu → Collections: a card per collection; tapping one opens it ([onOpen] with its id). */
@Composable
fun CollectionsScreen(vm: CollectionsViewModel, cacheFile: (String) -> File?, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionCard?>(null) }
    var deleting by remember { mutableStateOf<CollectionCard?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            vm.messageShown()
            scope.launch { snackbar.showSnackbar(it) }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Collections", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("New collection")
                }
            }
            HorizontalDivider()
            if (state.cards.isEmpty()) {
                EmptyCollections(onCreate = { creating = true })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.cards, key = { it.id }) { card ->
                        CollectionCardView(
                            card,
                            cacheFile,
                            onOpen = { onOpen(card.id) },
                            onRename = { renaming = card },
                            onDelete = { deleting = card },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        CollectionNameDialog(
            title = "New collection",
            confirmLabel = "Create",
            initial = "",
            validate = { collectionNameError(it, state.collections) },
            onConfirm = { name ->
                vm.create(name)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { card ->
        CollectionNameDialog(
            title = "Rename collection",
            confirmLabel = "Rename",
            initial = card.name,
            validate = { collectionNameError(it, state.collections, excludingId = card.id) },
            onConfirm = { name ->
                vm.rename(card.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${card.name}\"?") },
            text = { Text("The levels stay in your library.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(card.id)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CollectionCardView(
    card: CollectionCard,
    cacheFile: (String) -> File?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        Mosaic(card.coverKeys.mapNotNull { key -> cacheFile(key)?.toURI()?.toString() })
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (card.count == 1) "1 level" else "${card.count} levels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() })
                }
            }
        }
    }
}

/** Up to four covers in a square: one fills it, two to four make a 2×2 grid; none shows a placeholder. */
@Composable
private fun Mosaic(urls: List<String>) {
    val square = Modifier.fillMaxWidth().aspectRatio(1f)
    when {
        urls.isEmpty() -> Box(square.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.CollectionsBookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        urls.size == 1 -> AsyncImage(urls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = square)
        else -> Column(square.background(MaterialTheme.colorScheme.surfaceVariant)) {
            for (row in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (col in 0 until 2) {
                        val url = urls.getOrNull(row * 2 + col)
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            if (url != null) {
                                AsyncImage(url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCollections(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No collections yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create one here, or long-press levels in Play to add them",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onCreate) { Text("New collection") }
    }
}

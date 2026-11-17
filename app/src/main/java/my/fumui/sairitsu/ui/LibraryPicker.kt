package my.fumui.sairitsu.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.fumui.sairitsu.audio.PreviewPlayer
import my.fumui.sairitsu.audio.PreviewStatus
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.GameplayActivity
import my.fumui.sairitsu.gameplay.GameplayIntent
import my.fumui.sairitsu.gameplay.GameplayMode
import my.fumui.sairitsu.gameplay.arcadeText
import my.fumui.sairitsu.library.LibraryEntry
import my.fumui.sairitsu.library.collectionNameError
import my.fumui.sairitsu.library.membership
import my.fumui.sairitsu.library.toLevelItem
import my.fumui.sairitsu.play.Launchers
import my.fumui.sairitsu.text.Localizer
import kotlinx.coroutines.launch
import java.io.File

/**
 * The downloaded-levels picker shared by Play and the collection screens: the top row ([title] and the filter bar, or
 * the selection bar), the level list with multi-select, the details pane, and the collection and delete dialogs.
 * [empty] is shown when the picker's scope has no levels at all.
 */
@Composable
fun LibraryPicker(
    vm: LibraryViewModel,
    title: String,
    localizer: Localizer,
    player: PreviewPlayer,
    launchers: Launchers,
    cacheFile: (String) -> File?,
    onBack: () -> Unit,
    empty: @Composable () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by player.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<LibraryEntry?>(null) }
    // The levels the "Add to collection" dialog (or its "New collection" dialog) is for; null = closed.
    var addKeys by remember { mutableStateOf<Set<LevelKey>?>(null) }
    var newFor by remember { mutableStateOf<Set<LevelKey>?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStats() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.stop() }
    LaunchedEffect(state.selected?.key) { player.stop() }
    LaunchedEffect(state.missing) { if (state.missing) onBack() }
    LaunchedEffect(preview) { if (preview is PreviewStatus.Failed) snackbar.showSnackbar("Couldn't play the preview") }
    // Shown from [scope]: clearing the flag re-keys the effect, which would cancel a snackbar shown inside it.
    LaunchedEffect(state.showRecoveredMessage) {
        if (state.showRecoveredMessage) {
            vm.recoveredMessageShown()
            scope.launch { snackbar.showSnackbar("The download list was damaged and has been reset") }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            vm.messageShown()
            scope.launch { snackbar.showSnackbar(it) }
        }
    }
    BackHandler(enabled = state.selection.isNotEmpty()) { vm.clearSelection() }

    val items = remember(state.visible) { state.visible.map { it to it.toLevelItem(cacheFile) } }
    fun keysOf(levels: List<LevelItem>): List<LevelKey> =
        levels.mapNotNull { level -> items.firstOrNull { it.second === level }?.first?.key }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
            if (state.selection.isNotEmpty()) {
                SelectionBar(
                    count = state.selection.size,
                    allSelected = state.visible.all { it.key in state.selection },
                    removeLabel = state.removeTarget?.let { "Remove from collection \"${it.name}\"" },
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onAddToCollection = { addKeys = state.selection },
                    onRemove = vm::removeSelectionFromTarget,
                )
            } else {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.ready && !state.scopeEmpty) {
                        LibraryFilterBar(
                            state.query, state.facets, localizer, vm::setQuery, Modifier.weight(1f),
                            // A collection screen is already one collection: no Collections section there.
                            collections = if (state.collection == null) state.collections.map { it.id to it.name } else emptyList(),
                        )
                    }
                }
            }
            HorizontalDivider()
            if (state.ready && state.scopeEmpty) {
                empty()
                return@Column
            }
            Row(Modifier.fillMaxSize()) {
                LevelList(
                    list = ListState(items = items.map { it.second }, endReached = true),
                    selectedName = state.selected?.name,
                    localizer = localizer,
                    onSelect = { level -> items.firstOrNull { it.second === level }?.first?.let(vm::select) },
                    onLoadMore = {},
                    onRetry = {},
                    modifier = Modifier.weight(0.38f).fillMaxHeight(),
                    // Lazy keys must be Bundle types: a String, not LevelKey.
                    itemKey = { index, _ -> items[index].first.let { "${it.server}/${it.name}" } },
                    selection = LevelSelection(
                        active = state.selection.isNotEmpty(),
                        isSelected = { level -> items.firstOrNull { it.second === level }?.first?.key in state.selection },
                        onStart = { vm.startSelection(keysOf(it)) },
                        onToggle = { vm.toggleSelection(keysOf(it)) },
                    ),
                )
                VerticalDivider()
                val entry = state.selected
                Box(Modifier.weight(0.62f).fillMaxHeight()) {
                    if (entry != null) {
                        val level = items.firstOrNull { it.first.key == entry.key }?.second ?: entry.toLevelItem(cacheFile)
                        DetailsPane(
                            selected = level,
                            details = state.details,
                            localizer = localizer,
                            preview = preview,
                            onLaunch = { target, item ->
                                val server = ServerAddress.parse(entry.serverUrl) ?: return@DetailsPane
                                val launcher = when (target) {
                                    LaunchTarget.Watch -> launchers.watch
                                    LaunchTarget.Play -> launchers.play
                                    LaunchTarget.LevelOptions -> launchers.levelOptions
                                }
                                if (!launcher.play(context, server, item)) {
                                    scope.launch { snackbar.showSnackbar("No app can open this level") }
                                }
                            },
                            onPreview = player::toggle,
                            onSelect = {},
                            onRetry = {},
                            showSections = false,
                            menuActions = listOf(
                                MenuAction("Add to collection…") { addKeys = setOf(entry.key) },
                                MenuAction("Delete") { confirmDelete = entry },
                            ),
                            footer = {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    localizer.text(entry.serverTitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                state.stats[entry.key]?.best?.let { best ->
                                    Text("Best ${best.grade.label} ${best.arcadeText()}", style = MaterialTheme.typography.bodyMedium)
                                }
                            },
                            bottom = {
                                RunList(
                                    state.runs,
                                    state.runSort,
                                    vm::setRunSort,
                                    onWatchReplay = { run ->
                                        val ref = replayRef(run)
                                        val server = ServerAddress.parse(entry.serverUrl)
                                        if (ref != null && server != null) {
                                            val intent = Intent(context, GameplayActivity::class.java)
                                            GameplayIntent.extras(server, level.name, GameplayMode.Watch, level.engine?.name, replay = ref)
                                                .forEach { (key, value) -> intent.putExtra(key, value) }
                                            context.startActivity(intent)
                                        }
                                    },
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    addKeys?.let { keys ->
        AddToCollectionDialog(
            rows = state.collections.map { CollectionRow(it.id, it.name, membership(it, keys)) },
            onToggle = { id, add -> vm.setMembership(id, keys, add) },
            onNew = { newFor = keys },
            onDismiss = { addKeys = null },
        )
    }
    newFor?.let { keys ->
        CollectionNameDialog(
            title = "New collection",
            confirmLabel = "Create",
            initial = "",
            validate = { collectionNameError(it, state.collections) },
            onConfirm = { name ->
                vm.createCollection(name, keys)
                newFor = null
            },
            onDismiss = { newFor = null },
        )
    }
    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this level?") },
            text = { Text(localizer.text(entry.title)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(entry)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

package my.fumui.sairitsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import my.fumui.sairitsu.data.SyncStatus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import my.fumui.sairitsu.audio.PreviewPlayer
import my.fumui.sairitsu.audio.PreviewStatus
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.library.DownloadState
import my.fumui.sairitsu.play.Launchers
import my.fumui.sairitsu.text.Localizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val LIST_WEIGHT = 0.38f
private const val PANE_WEIGHT = 0.62f

/** The browser's downloads: which levels (by name) are downloaded, their download states, and the actions. */
class BrowseDownloads(
    val downloaded: Set<String>,
    val states: Map<String, DownloadState>,
    val onDownload: (LevelItem) -> Unit,
    val onCancel: (String) -> Unit,
    /** The "Add to collection" rows for a level (pending ones for a level that isn't downloaded yet). */
    val collectionRows: (LevelItem) -> List<CollectionRow> = { emptyList() },
    /** Why a new collection name can't be used, or null. */
    val nameError: (String) -> String? = { null },
    /** Adds the level to collection `id` or takes it out; for a level not downloaded, add also starts the download. */
    val onMembership: suspend (level: LevelItem, id: String, add: Boolean) -> Unit = { _, _, _ -> },
    /** Creates a collection with the level in it (pending until the level is downloaded). */
    val onCreateCollection: suspend (level: LevelItem, name: String) -> Unit = { _, _ -> },
)

@Composable
fun PickerScreen(
    vm: PickerViewModel,
    localizer: Localizer,
    player: PreviewPlayer,
    launchers: Launchers,
    onBack: () -> Unit,
    downloads: BrowseDownloads,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by player.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addFor by remember { mutableStateOf<LevelItem?>(null) }
    var newFor by remember { mutableStateOf<LevelItem?>(null) }
    fun saveCollections(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't save collections")
            }
        }
    }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }

    val failures = downloads.states.filterValues { it is DownloadState.Failed }
    LaunchedEffect(failures) {
        for (failed in failures.values) {
            failed as DownloadState.Failed
            snackbar.showSnackbar(failed.resource?.let { "$it: ${failed.message}" } ?: failed.message)
        }
    }
    LaunchedEffect(state.selected?.name) { player.stop() }
    LaunchedEffect(state.search == null) { if (state.search == null) filtersOpen = false }
    LaunchedEffect(preview) {
        if (preview is PreviewStatus.Failed) snackbar.showSnackbar("Couldn't play the preview")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.stop() }
    BackHandler(enabled = state.search != null) { vm.closeSearch() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        val server = state.server
        CompositionLocalProvider(LocalUrlResolver provides { url -> server?.resolve(url) ?: url }) {
            Column(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.displayCutout)) {
                val search = state.search
                if (search == null) {
                    BrowseTopBar(
                        state = state,
                        localizer = localizer,
                        onBack = onBack,
                        onSection = vm::selectSection,
                        onSearch = vm::openSearch,
                        onFilters = {
                            vm.openSearch()
                            filtersOpen = true
                        },
                        onSort = { index, value ->
                            vm.openSearch()
                            vm.setOption(index, value)
                        },
                    )
                } else {
                    SearchTopBar(
                        search = search,
                        localizer = localizer,
                        onClose = vm::closeSearch,
                        onOption = vm::setOption,
                        onQuickKeywords = vm::setQuickKeywords,
                        onFilters = { filtersOpen = true },
                    )
                }
                if (filtersOpen && search != null) {
                    SearchFiltersDialog(
                        search = search,
                        localizer = localizer,
                        onForm = vm::selectForm,
                        onOption = vm::setOption,
                        onClear = vm::clearFilters,
                        onDismiss = { filtersOpen = false },
                    )
                }
                HorizontalDivider()
                SyncBar(state.sync, onRetry = vm::retrySync)
                Row(Modifier.fillMaxSize()) {
                    LevelList(
                        list = state.list,
                        selectedName = state.selected?.name,
                        localizer = localizer,
                        onSelect = vm::select,
                        onLoadMore = vm::loadMore,
                        onRetry = vm::retryList,
                        modifier = Modifier.weight(LIST_WEIGHT).fillMaxHeight(),
                        downloaded = downloads.downloaded,
                    )
                    VerticalDivider()
                    Box(Modifier.weight(PANE_WEIGHT).fillMaxHeight()) {
                        DetailsPane(
                            selected = state.selected,
                            details = state.details,
                            localizer = localizer,
                            preview = preview,
                            onLaunch = { target, level ->
                                val server = state.server ?: return@DetailsPane
                                val launcher = when (target) {
                                    LaunchTarget.Watch -> launchers.watch
                                    LaunchTarget.Play -> launchers.play
                                    LaunchTarget.LevelOptions -> launchers.levelOptions
                                }
                                if (!launcher.play(context, server, level)) {
                                    scope.launch { snackbar.showSnackbar("No app can open this level") }
                                }
                            },
                            onPreview = player::toggle,
                            onSelect = vm::select,
                            onRetry = vm::retryDetails,
                            menuActions = listOf(MenuAction("Add to collection…") { addFor = state.selected }),
                            extraActions = {
                                val level = state.selected ?: return@DetailsPane
                                DownloadButton(
                                    downloadButtonState(level.name in downloads.downloaded, downloads.states[level.name]),
                                    onDownload = { downloads.onDownload(level) },
                                    onCancel = { downloads.onCancel(level.name) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    addFor?.let { level ->
        AddToCollectionDialog(
            rows = downloads.collectionRows(level),
            onToggle = { id, add -> saveCollections { downloads.onMembership(level, id, add) } },
            onNew = { newFor = level },
            onDismiss = { addFor = null },
        )
    }
    newFor?.let { level ->
        CollectionNameDialog(
            title = "New collection",
            confirmLabel = "Create",
            initial = "",
            validate = downloads.nameError,
            onConfirm = { name ->
                saveCollections { downloads.onCreateCollection(level, name) }
                newFor = null
            },
            onDismiss = { newFor = null },
        )
    }
}

/** The saved level list's sync: progress while downloading it, a thin bar while checking, or the error. */
@Composable
private fun SyncBar(sync: SyncStatus, onRetry: () -> Unit) {
    when (sync) {
        SyncStatus.Idle -> Unit
        SyncStatus.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        is SyncStatus.Downloading -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val total = sync.total
            Text(
                "Saving levels for instant browsing… " + "%,d".format(sync.done) + (total?.let { " / %,d".format(it) } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (sync.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is SyncStatus.Failed -> Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Couldn't update the level list: ${sync.message}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

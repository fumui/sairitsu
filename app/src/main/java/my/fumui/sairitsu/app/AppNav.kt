package my.fumui.sairitsu.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import my.fumui.sairitsu.audio.PreviewPlayer
import my.fumui.sairitsu.data.ItemApi
import my.fumui.sairitsu.data.LevelKey
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.gameplay.GameplayMode
import my.fumui.sairitsu.library.Membership
import my.fumui.sairitsu.library.collectionNameError
import my.fumui.sairitsu.library.membership
import my.fumui.sairitsu.library.sortedByName
import my.fumui.sairitsu.play.InAppPlayLauncher
import my.fumui.sairitsu.play.Launchers
import my.fumui.sairitsu.text.Localizer
import my.fumui.sairitsu.ui.BrowseDownloads
import my.fumui.sairitsu.ui.CollectionRow
import my.fumui.sairitsu.ui.CollectionScreen
import my.fumui.sairitsu.ui.CollectionsScreen
import my.fumui.sairitsu.ui.CollectionsViewModel
import my.fumui.sairitsu.ui.HubSection
import my.fumui.sairitsu.ui.LibraryScreen
import my.fumui.sairitsu.ui.LibraryViewModel
import my.fumui.sairitsu.ui.Loadable
import my.fumui.sairitsu.ui.MenuScreen
import my.fumui.sairitsu.ui.PickerScope
import my.fumui.sairitsu.ui.PickerScreen
import my.fumui.sairitsu.ui.PickerViewModel
import my.fumui.sairitsu.ui.PostsScreen
import my.fumui.sairitsu.ui.PostsViewModel
import my.fumui.sairitsu.ui.ServerConfigScreen
import my.fumui.sairitsu.ui.ServerConfigViewModel
import my.fumui.sairitsu.ui.ServerHubScreen
import my.fumui.sairitsu.ui.ServerHubViewModel
import my.fumui.sairitsu.ui.ServerSkinsScreen
import my.fumui.sairitsu.ui.ServerSkinsViewModel
import my.fumui.sairitsu.ui.ServersScreen
import my.fumui.sairitsu.ui.ServersViewModel
import my.fumui.sairitsu.ui.SettingsScreen
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
object MenuRoute

@Serializable
object ServersRoute

@Serializable
data class BrowseRoute(val address: String)

@Serializable
data class ServerHubRoute(val address: String)

@Serializable
data class ServerConfigRoute(val address: String)

@Serializable
data class ServerSkinsRoute(val address: String)

@Serializable
data class ServerPostsRoute(val address: String)

/** savedStateHandle key (on the hub's entry) for a message the hub shows in a snackbar. */
const val HUB_MESSAGE = "hubMessage"

@Serializable
object LibraryRoute

@Serializable
object SettingsRoute

@Serializable
object CollectionsRoute

@Serializable
data class CollectionRoute(val id: String)

/** The launchers the details panes use: in-app Watch, Play and Level options. */
fun appLaunchers() = Launchers(
    watch = InAppPlayLauncher(GameplayMode.Watch),
    play = InAppPlayLauncher(GameplayMode.Play),
    levelOptions = InAppPlayLauncher(GameplayMode.Watch, optionsScreen = true),
)

/** The app's screens, starting at the Main Menu. [onExit] leaves the app (Exit, or back on the menu). */
@Composable
fun AppNav(services: AppServices, onExit: () -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    val launchers = remember { appLaunchers() }

    NavHost(nav, startDestination = MenuRoute) {
        composable<MenuRoute> {
            BackHandler(onBack = onExit)
            val entries by services.library.index.entries.collectAsState()
            val covers = remember(entries) {
                entries.mapNotNull { entry -> entry.coverKey?.let(services.cache::pathOf)?.toURI()?.toString() }
            }
            MenuScreen(
                onPlay = { nav.navigate(LibraryRoute) },
                onCollections = { nav.navigate(CollectionsRoute) },
                onServers = { nav.navigate(ServersRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
                onExit = onExit,
                covers = covers,
                levelCount = entries.size,
            )
        }
        composable<ServersRoute> {
            val vm: ServersViewModel = viewModel(
                factory = viewModelFactory { initializer { ServersViewModel(services.servers, services::levelSource, services.catalog, services.serverConfigs) } },
            )
            ServersScreen(vm, onOpen = { nav.navigate(ServerHubRoute(it.url)) }, onBack = { nav.popBackStack() })
        }
        composable<ServerHubRoute> { entry ->
            val address = entry.toRoute<ServerHubRoute>().address
            val server = remember(address) { ServerAddress.parse(address) }
            if (server == null) {
                Placeholder(PickerViewModel.INVALID_ADDRESS)
                return@composable
            }
            val vm: ServerHubViewModel = viewModel(
                factory = viewModelFactory { initializer { ServerHubViewModel(services.levelSource(server)) } },
            )
            LaunchedEffect(server) { services.catalog.loadSummaries(listOf(server.linkAddress)) }
            val info by vm.info.collectAsState()
            val servers by services.servers.servers.collectAsState(emptyList())
            val summaries by services.catalog.summaries.collectAsState()
            val message by entry.savedStateHandle.getStateFlow<String?>(HUB_MESSAGE, null).collectAsState()
            // A saved configuration invalidated the cached server info, so fetch it again.
            LaunchedEffect(message) { if (message != null) vm.retry() }
            ServerHubScreen(
                server = server,
                savedTitle = servers.firstOrNull { it.address == server.linkAddress }?.title,
                info = info,
                catalog = summaries[server.linkAddress],
                localizer = localizer,
                message = message,
                onMessageShown = { entry.savedStateHandle[HUB_MESSAGE] = null },
                onSection = { section ->
                    nav.navigate(
                        when (section) {
                            HubSection.Levels -> BrowseRoute(server.baseUrl.toString())
                            HubSection.Posts -> ServerPostsRoute(address)
                            HubSection.Skins -> ServerSkinsRoute(address)
                            HubSection.Configuration -> ServerConfigRoute(address)
                        },
                    )
                },
                onRetry = vm::retry,
                onBack = { nav.popBackStack() },
            )
        }
        composable<ServerPostsRoute> { entry ->
            val address = entry.toRoute<ServerPostsRoute>().address
            val server = remember(address) { ServerAddress.parse(address) }
            if (server == null) {
                Placeholder(PickerViewModel.INVALID_ADDRESS)
                return@composable
            }
            val vm: PostsViewModel = viewModel(
                factory = viewModelFactory { initializer { PostsViewModel(ItemApi(server, services.client)) } },
            )
            PostsScreen(vm, server, localizer, onBack = { nav.popBackStack() })
        }
        composable<ServerSkinsRoute> { entry ->
            val address = entry.toRoute<ServerSkinsRoute>().address
            val server = remember(address) { ServerAddress.parse(address) }
            if (server == null) {
                Placeholder(PickerViewModel.INVALID_ADDRESS)
                return@composable
            }
            val vm: ServerSkinsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ServerSkinsViewModel(server, ItemApi(server, services.client), services.skinLibrary) }
                },
            )
            ServerSkinsScreen(vm, server, localizer, onBack = { nav.popBackStack() })
        }
        composable<ServerConfigRoute> { entry ->
            val address = entry.toRoute<ServerConfigRoute>().address
            val server = remember(address) { ServerAddress.parse(address) }
            if (server == null) {
                Placeholder(PickerViewModel.INVALID_ADDRESS)
                return@composable
            }
            val link = server.linkAddress
            val vm: ServerConfigViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ServerConfigViewModel(services.levelSource(server)) { services.serverConfigs.values.value[link].orEmpty() }
                    }
                },
            )
            val form by vm.form.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            val uiScope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            ServerConfigScreen(
                form = form,
                localizer = localizer,
                onChange = vm::set,
                onReset = vm::reset,
                onSave = save@{
                    val ready = (form as? Loadable.Ready)?.value ?: return@save
                    if (saving) return@save
                    saving = true
                    services.scope.launch {
                        try {
                            services.serverConfigs.save(link, ready.params())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            saving = false
                            uiScope.launch { snackbar.showSnackbar("Couldn't save the configuration") }
                            return@launch
                        }
                        services.responses.invalidate(link)
                        val hasList = services.catalog.summaries.value[link] != null
                        if (hasList) services.catalog.refresh(server)
                        withContext(Dispatchers.Main) {
                            // Back during the save already left this screen; don't pop whatever is on top now.
                            if (nav.currentBackStackEntry?.id != entry.id) return@withContext
                            nav.previousBackStackEntry?.savedStateHandle?.set(
                                HUB_MESSAGE,
                                if (hasList) "Configuration saved. Refreshing the level list…" else "Configuration saved.",
                            )
                            nav.popBackStack()
                        }
                    }
                },
                onRetry = vm::retry,
                onBack = { nav.popBackStack() },
                snackbar = snackbar,
            )
        }
        composable<BrowseRoute> { entry ->
            val address = entry.toRoute<BrowseRoute>().address
            val server = remember(address) { ServerAddress.parse(address) }
            if (server == null) {
                Placeholder(PickerViewModel.INVALID_ADDRESS)
                return@composable
            }
            val vm: PickerViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        PickerViewModel(services::levelSource, server, catalog = services.catalogHandle(server), onServerInfo = { info ->
                            services.scope.launch { services.servers.setTitle(server.linkAddress, localizer.text(info.title)) }
                        })
                    }
                },
            )
            val entries by services.library.index.entries.collectAsState()
            val states by services.downloader.states.collectAsState()
            val downloaded = remember(entries) {
                entries.filter { it.server == server.linkAddress }.mapTo(HashSet()) { it.name }
            }
            val mine = remember(states) {
                states.filterKeys { it.server == server.linkAddress }.mapKeys { it.key.level }
            }
            val collections by services.collections.collections.collectAsState()
            val pending by services.collections.pending.collectAsState()
            val sorted = remember(collections) { collections.sortedByName() }
            val download = { level: LevelItem ->
                val title = (vm.state.value.serverInfo as? Loadable.Ready)?.value?.title?.let(localizer::text)
                    ?: server.linkAddress
                services.downloader.download(server, title, level)
                Unit
            }
            fun keyOf(level: LevelItem) = LevelKey(server.linkAddress, level.name)
            PickerScreen(
                vm, localizer, player, launchers,
                onBack = { nav.popBackStack() },
                downloads = BrowseDownloads(
                    downloaded = downloaded,
                    states = mine,
                    onDownload = download,
                    onCancel = { name -> services.downloader.cancel(LevelKey(server.linkAddress, name)) },
                    collectionRows = { level ->
                        val key = keyOf(level)
                        val waiting = pending[key].orEmpty()
                        sorted.map { c ->
                            if (level.name in downloaded) {
                                CollectionRow(c.id, c.name, membership(c, setOf(key)))
                            } else {
                                CollectionRow(c.id, c.name, if (c.id in waiting) Membership.All else Membership.None, pending = c.id in waiting)
                            }
                        }
                    },
                    nameError = { collectionNameError(it, collections) },
                    onMembership = { level, id, add ->
                        val key = keyOf(level)
                        val store = services.collections
                        when {
                            level.name in downloaded -> if (add) store.add(setOf(id), setOf(key)) else store.remove(setOf(id), setOf(key))
                            add -> {
                                // download() first: it marks the level queued, so the watcher keeps the pending entry.
                                download(level)
                                store.addPending(key, id)
                            }
                            else -> store.removePending(key, id)
                        }
                    },
                    onCreateCollection = { level, name ->
                        val key = keyOf(level)
                        if (level.name in downloaded) {
                            services.collections.create(name, setOf(key))
                        } else {
                            val created = services.collections.create(name)
                            download(level)
                            services.collections.addPending(key, created.id)
                        }
                    },
                ),
            )
        }
        composable<LibraryRoute> {
            val vm: LibraryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LibraryViewModel(
                            services.library, services.results, services.libraryQuery, services.runs,
                            collections = services.collections,
                            readDetails = services.library::readDetails,
                        )
                    }
                },
            )
            LibraryScreen(
                vm, localizer, player, launchers,
                cacheFile = services.cache::pathOf,
                onServers = { nav.navigate(ServersRoute) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<CollectionsRoute> {
            val vm: CollectionsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { CollectionsViewModel(services.collections, services.library.index.entries, services.libraryQuery) }
                },
            )
            CollectionsScreen(
                vm,
                cacheFile = services.cache::pathOf,
                onOpen = { nav.navigate(CollectionRoute(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<CollectionRoute> { entry ->
            val id = entry.toRoute<CollectionRoute>().id
            val vm: LibraryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LibraryViewModel(
                            services.library, services.results, queryStore = null, history = services.runs,
                            collections = services.collections, scope = PickerScope.InCollection(id),
                            readDetails = services.library::readDetails,
                        )
                    }
                },
            )
            CollectionScreen(vm, localizer, player, launchers, cacheFile = services.cache::pathOf, onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(services.settings, services.library, services.skins, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun Placeholder(label: String) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label) }
    }
}

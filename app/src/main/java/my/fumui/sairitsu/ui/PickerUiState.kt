package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.OptionState
import my.fumui.sairitsu.data.SyncStatus
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.ItemSection
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.Search
import my.fumui.sairitsu.data.model.ServerInfo

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Error(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/**
 * The level list. Servers page either by [cursor] or by page number; [page] is the last
 * page number requested and [pageCount] the server's total (-1 when it uses cursors).
 */
data class ListState(
    val items: List<LevelItem> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
    val page: Int = 0,
    val pageCount: Int = -1,
)

/** Search mode. [activeForm] is -1 when the server has no forms and quick search is used. */
data class SearchState(
    val forms: List<Search>,
    val activeForm: Int,
    val values: List<List<OptionState>>,
    val quickKeywords: String = "",
)

data class PickerUiState(
    val serverInput: String = "",
    val server: ServerAddress? = null,
    val serverInfo: Loadable<ServerInfo> = Loadable.Idle,
    val sections: List<ItemSection> = emptyList(),
    val searches: List<Search> = emptyList(),
    val activeSection: Int? = null,
    val list: ListState = ListState(),
    val selected: LevelItem? = null,
    val details: Loadable<ItemDetails> = Loadable.Idle,
    val search: SearchState? = null,
    /** The saved level list's sync, size and whether it is complete (browsers without one stay Idle). */
    val sync: SyncStatus = SyncStatus.Idle,
    val catalogCount: Int = 0,
    val catalogComplete: Boolean = false,
    /** Names of the levels picked in selection mode; non-empty means selection mode is on. */
    val selection: Set<String> = emptySet(),
)

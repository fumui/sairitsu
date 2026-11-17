package my.fumui.sairitsu.ui

import my.fumui.sairitsu.data.LevelSource
import my.fumui.sairitsu.data.model.ItemDetails
import my.fumui.sairitsu.data.model.ItemInfo
import my.fumui.sairitsu.data.model.ItemList
import my.fumui.sairitsu.data.model.LevelItem
import my.fumui.sairitsu.data.model.ServerInfo
import my.fumui.sairitsu.data.model.ServerJson

/** Scriptable [LevelSource] backed by the saved Horizon responses. */
class FakeLevelSource : LevelSource {
    val listCalls = mutableListOf<Pair<Map<String, String>, String?>>()
    val detailCalls = mutableListOf<String>()

    var info: suspend () -> ItemInfo = { INFO }
    var list: suspend (Map<String, String>, String?) -> ItemList = { _, cursor -> if (cursor == null) PAGE_1 else PAGE_2 }
    var details: suspend (String) -> ItemDetails = { name -> ItemDetails(item = LevelItem(name = name, title = name)) }

    override suspend fun serverInfo() = ServerInfo(title = "Coconut Horizon")

    override suspend fun levelInfo() = info()

    override suspend fun levelList(params: Map<String, String>, cursor: String?): ItemList {
        listCalls += params to cursor
        return list(params, cursor)
    }

    override suspend fun levelDetails(name: String): ItemDetails {
        detailCalls += name
        return details(name)
    }

    companion object {
        private fun fixture(name: String) = FakeLevelSource::class.java.getResource("/fixtures/$name.json")!!.readText()

        val INFO: ItemInfo = ServerJson.decodeFromString(fixture("info"))
        val PAGE_1: ItemList = ServerJson.decodeFromString(fixture("list"))
        val PAGE_2: ItemList = PAGE_1.copy(
            cursor = null,
            items = PAGE_1.items.map { it.copy(name = it.name + "-p2") },
        )
    }
}


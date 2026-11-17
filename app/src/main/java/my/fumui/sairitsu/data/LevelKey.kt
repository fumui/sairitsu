package my.fumui.sairitsu.data

import kotlinx.serialization.Serializable

/** A level on a server: [server] is the server's [ServerAddress.linkAddress], [level] the level name. */
@Serializable
data class LevelKey(val server: String, val level: String)

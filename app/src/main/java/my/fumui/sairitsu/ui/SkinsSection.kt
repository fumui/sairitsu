package my.fumui.sairitsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import my.fumui.sairitsu.data.ServerAddress
import my.fumui.sairitsu.gameplay.SavedSkin
import my.fumui.sairitsu.gameplay.ScopedSkins
import my.fumui.sairitsu.gameplay.SkinOffer
import my.fumui.sairitsu.library.downloadErrorText
import my.fumui.sairitsu.text.Localizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings section: the skins saved on this device, and adding more from a server. */
@Composable
fun SkinsSection(skins: ScopedSkins) {
    val saved by skins.library.skins.collectAsState()
    val localizer = remember { Localizer(Localizer.serverLanguage(Locale.getDefault())) }
    var removing by remember { mutableStateOf<SavedSkin?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Skins", style = MaterialTheme.typography.titleMedium)
        for (skin in saved) {
            val title = localizer.text(skin.title)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { removing = skin }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        removeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        AddSkin(skins, localizer)
    }

    removing?.let { skin ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${localizer.text(skin.title)}\"?") },
            text = { Text("Levels and engines using this skin go back to their own look.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    removeError = null
                    scope.launch {
                        try {
                            skins.remove(skin.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            removeError = downloadErrorText(e)
                        }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddSkin(skins: ScopedSkins, localizer: Localizer) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("http://127.0.0.1:8000") }
    var offers by remember { mutableStateOf<List<SkinOffer>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /** Runs [block] with the progress bar up; a failure becomes the message under the field. */
    fun request(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = downloadErrorText(e)
            } finally {
                busy = false
            }
        }
    }

    OutlinedTextField(
        value = address,
        onValueChange = {
            address = it
            offers = emptyList()
            error = null
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Server address") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    TextButton(
        enabled = !busy,
        onClick = {
            val server = ServerAddress.parse(address)
            if (server == null) {
                error = "Enter a valid server address"
            } else {
                request {
                    offers = skins.library.offers(server)
                    if (offers.isEmpty()) error = "This server has no skins"
                }
            }
        },
    ) { Text("Find skins") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    for (offer in offers) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localizer.text(offer.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(
                enabled = !busy,
                onClick = {
                    ServerAddress.parse(address)?.let { server ->
                        request {
                            skins.library.add(server, offer)
                            offers = offers - offer
                        }
                    }
                },
            ) { Text("Add") }
        }
    }
}

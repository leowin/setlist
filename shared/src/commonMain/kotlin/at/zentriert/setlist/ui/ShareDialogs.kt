package at.zentriert.setlist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.rememberShareText
import at.zentriert.setlist.ui.band.PRIVATE_LABEL

/** Dialogs that can appear on any screen: creating a share link, reviewing an opened one, link messages. */
@Composable
fun LinkDialogs(vm: MainViewModel) {
    val share = rememberShareText()

    vm.shareRequest?.let { request ->
        var ttl by remember { mutableStateOf(30) }
        var uploading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!uploading) vm.cancelShare() },
            title = { Text("„${request.title}“ als Link teilen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${request.payload.songs.size} ${if (request.payload.songs.size == 1) "Lied" else "Lieder"} mit Anweisungen und Noten. " +
                            "Der Inhalt wird verschlüsselt hochgeladen; der Schlüssel steht nur im Link.",
                    )
                    Text("Link gültig für", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    listOf(7 to "7 Tage", 30 to "30 Tage", 90 to "90 Tage").forEach { (days, label) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !uploading) { ttl = days }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = ttl == days, onClick = { ttl = days }, enabled = !uploading)
                            Text(label)
                        }
                    }
                    if (uploading) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Wird hochgeladen …", Modifier.padding(start = 8.dp))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !uploading, onClick = {
                    uploading = true; error = null
                    vm.uploadShare(ttl) { link, err ->
                        uploading = false
                        if (link != null) share(request.title, "${request.title} – aus der Setlist-App:\n$link") else error = err
                    }
                }) { Text("Link erzeugen") }
            },
            dismissButton = { TextButton(onClick = vm::cancelShare, enabled = !uploading) { Text("Abbrechen") } },
        )
    }

    vm.incomingShare?.let { payload ->
        var target by remember { mutableStateOf(vm.currentBandId) }
        AlertDialog(
            onDismissRequest = vm::dismissIncomingShare,
            title = { Text(payload.setlistName?.let { "Setlist „$it“" } ?: payload.songs.singleOrNull()?.title ?: "${payload.songs.size} Lieder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (payload.from.isNotBlank()) Text("Geteilt von ${payload.from}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    payload.songs.take(8).forEach { s ->
                        val score = payload.scores[s.scoreId]
                        Text("• ${s.title}" + (score?.let { "  ♩ ${it.summary}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (payload.songs.size > 8) Text("… und ${payload.songs.size - 8} weitere")
                    Text("Übernehmen nach", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    (listOf<Pair<String?, String>>(null to PRIVATE_LABEL) + vm.bands.map { it.id to it.name }).forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth().clickable { target = id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = target == id, onClick = { target = id })
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.acceptShare(payload, target) }) { Text("Übernehmen") } },
            dismissButton = { TextButton(onClick = vm::dismissIncomingShare) { Text("Abbrechen") } },
        )
    }

    vm.linkMessage?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.linkMessage = null },
            title = { Text("Link") }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { vm.linkMessage = null }) { Text("OK") } },
        )
    }
}

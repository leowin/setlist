package at.zentriert.setlist.ui.band

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.band.Band
import at.zentriert.setlist.band.Role
import at.zentriert.setlist.rememberShareText
import at.zentriert.setlist.sync.SyncStatus
import at.zentriert.setlist.ui.InstrumentDropdown
import at.zentriert.setlist.ui.metronome.BeatBackground

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun BandScreen(vm: MainViewModel) {
    val settings = vm.settings
    val data by vm.data.collectAsStateWithLifecycle()
    val share = rememberShareText()
    var name by remember { mutableStateOf(settings.myName) }
    var manualHost by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf<Band?>(null) }
    var leaving by remember { mutableStateOf<Band?>(null) }
    var deleting by remember { mutableStateOf<Band?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }
    BackHandler { vm.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Band") },
                navigationIcon = { IconButton(onClick = vm::goHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.syncTest) {
                Card(Modifier.fillMaxWidth().height(160.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        BeatBackground(vm.metronomePosition, 4)
                        Text(
                            "Sync-Test: alle Handys klicken und blinken jede Sekunde gleichzeitig",
                            Modifier.padding(16.dp).align(Alignment.Center),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; vm.updateSettings { s -> s.copy(myName = it) } },
                label = { Text("Mein Name in der Band") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.bandRole == null,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ich spiele", style = MaterialTheme.typography.labelLarge)
                InstrumentDropdown(selected = vm.myInstrument, noneLabel = "– keins –", onSelect = { vm.setMyInstrument(null, it) })
                vm.currentBand?.let { Text("in ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            // --- my bands ---
            Text("Meine Bands", style = MaterialTheme.typography.titleMedium)
            if (data.bands.isEmpty()) {
                Text(
                    "Noch keine. Eine Band teilt Lieder, Noten und Setlists zwischen den Handys ihrer Mitglieder – " +
                        "verschlüsselt, der Server kann nichts davon lesen. Alles bleibt auf euren Handys.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            data.bands.forEach { band ->
                BandCard(
                    band = band,
                    selected = vm.currentBandId == band.id,
                    status = vm.syncStatus[band.id],
                    hasServer = vm.serverFor(band).isNotEmpty(),
                    songs = data.library(band.id).size,
                    setlists = data.setlists(band.id).size,
                    onSelect = { vm.selectBand(band.id) },
                    onSync = { vm.syncBand(band.id) },
                    onInvite = { inviting = band },
                    onLeave = { leaving = band },
                    onDelete = { deleting = band },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }, modifier = Modifier.weight(1f)) { Text("Band gründen") }
                OutlinedButton(onClick = { joining = true }, modifier = Modifier.weight(1f)) { Text("Einladung einfügen") }
            }
            if (settings.serverUrl.isBlank() && data.bands.any { it.serverUrl.isBlank() }) {
                Text(
                    "Kein Server eingetragen: die Band funktioniert so nur im WLAN. Für Abgleich und Einladungslinks " +
                        "unter „Erweitert“ die Server-Adresse eintragen (siehe server/README.md).",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }

            // --- live session ---
            Text("Gemeinsam spielen", style = MaterialTheme.typography.titleMedium)
            when (vm.bandRole) {
                null -> {
                    Button(onClick = vm::hostBand, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Im WLAN hosten (dieses Handy gibt den Takt vor)") }
                    if (vm.bandHosts.isEmpty()) Text("Im WLAN noch keinen Host gefunden – alle Handys müssen im selben WLAN sein.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vm.bandHosts.forEach { (hostName, address) ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(hostName, style = MaterialTheme.typography.titleMedium)
                                    Text("${address.first}:${address.second}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Button(onClick = { vm.joinBand(address.first, address.second) }) { Text("Beitreten") }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(manualHost, { manualHost = it }, label = { Text("IP-Adresse des Hosts") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { if (manualHost.isNotBlank()) vm.joinBand(manualHost.trim()) }) { Text("Verbinden") }
                    }
                    val current = vm.currentBand
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Über Internet" + (current?.let { " · ${it.name}" } ?: ""), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (current == null) "Oben eine Band wählen – ihr Raum auf dem Server verbindet euch an getrennten Orten."
                                else "Alle Mitglieder von „${current.name}“ treffen sich im Raum der Band; der Server ist die gemeinsame Uhr.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.joinCloud(asHost = true) }, modifier = Modifier.weight(1f), enabled = current != null) { Text("Raum hosten") }
                                OutlinedButton(onClick = { vm.joinCloud(asHost = false) }, modifier = Modifier.weight(1f), enabled = current != null) { Text("Beitreten") }
                            }
                            if (vm.bandStatus.isNotEmpty()) Text(vm.bandStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (vm.bandRole == Role.HOST) "Du bist Host" else "Mitglied", style = MaterialTheme.typography.titleMedium)
                            Text(vm.bandStatus)
                            Text("Dabei: " + (listOf(vm.bandName()) + vm.bandPeers).distinct().joinToString(", "))
                            vm.clockInfo?.let { c ->
                                val good = c.errorMs < 2.0
                                Text(
                                    "Uhr: Offset ${c.offsetMs.fmt()} ms · Laufzeit ${c.rttMs.fmt()} ms · Genauigkeit ±${c.errorMs.fmt()} ms",
                                    color = if (good) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text("Startverzögerung (z. B. Bluetooth-Kopfhörer): ${settings.syncOffsetMs} ms")
                    Slider(
                        value = settings.syncOffsetMs.toFloat(),
                        onValueChange = { v -> vm.updateSettings { it.copy(syncOffsetMs = v.toInt()) } },
                        valueRange = -100f..300f,
                    )
                    if (vm.bandRole == Role.HOST) {
                        OutlinedButton(onClick = vm::toggleSyncTest, modifier = Modifier.fillMaxWidth()) {
                            Text(if (vm.syncTest) "Sync-Test beenden" else "Sync-Test: alle klicken gleichzeitig")
                        }
                        Text(
                            "Im Konzert: Setlist, Weiter/Zurück, Metronom und Abspielen folgen dem Host.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::leaveBand) { Text("Verbindung trennen") }
                }
            }

            // --- advanced ---
            TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Erweitert ausblenden" else "Erweitert …") }
            if (showAdvanced) {
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = { v -> vm.updateSettings { it.copy(serverUrl = v.trim()) } },
                    label = { Text("Server, z. B. https://setlist-….run.app") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Abgleich, Einladungs- und Teilen-Links und der Internet-Raum laufen über diesen Server. Er speichert nur Verschlüsseltes.") },
                )
                Text("Dieses Handy: ${settings.deviceId.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (creating) CreateBandDialog(
        offerAdopt = vm.hasPrivateData,
        onCreate = { n, adopt -> vm.createBand(n, adopt); creating = false },
        onDismiss = { creating = false },
    )
    if (joining) JoinBandDialog(
        offerAdopt = vm.hasPrivateData,
        onJoin = { text, adopt -> vm.joinBandFromInvite(text, adopt).also { if (it) joining = false } },
        onDismiss = { joining = false },
    )
    inviting?.let { band ->
        val link = vm.inviteLink(band)
        InviteDialog(band, link, onShare = { share("Einladung zur Band ${band.name}", "Tritt meiner Band „${band.name}“ in der Setlist-App bei:\n$link") }, onDismiss = { inviting = null })
    }
    leaving?.let { band ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("„${band.name}“ verlassen?") },
            text = { Text("Die Lieder und Setlists der Band bleiben als private Kopien auf diesem Handy. Die anderen Mitglieder merken davon nichts.") },
            confirmButton = { TextButton(onClick = { vm.leaveBandLocally(band.id); leaving = null }) { Text("Verlassen") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Abbrechen") } },
        )
    }
    deleting?.let { band ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Raum von „${band.name}“ am Server löschen?") },
            text = { Text("Alle verschlüsselten Daten der Band werden vom Server entfernt. Auf den Handys bleibt alles erhalten; beim nächsten Abgleich wird der Raum neu angelegt.") },
            confirmButton = { TextButton(onClick = { vm.deleteBandOnServer(band.id) { message = it ?: "Raum gelöscht" }; deleting = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } },
        )
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("Server") }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
}

@Composable
private fun BandCard(
    band: Band, selected: Boolean, status: SyncStatus?, hasServer: Boolean, songs: Int, setlists: Int,
    onSelect: () -> Unit, onSync: () -> Unit, onInvite: () -> Unit, onLeave: () -> Unit, onDelete: () -> Unit,
) {
    Card(
        onClick = onSelect, modifier = Modifier.fillMaxWidth(),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) Icon(Icons.Default.Check, "Ausgewählt", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(band.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onInvite) { Icon(Icons.Default.Share, "Einladen") }
                if (hasServer) {
                    if (status?.running == true) CircularProgressIndicator(Modifier.padding(12.dp).width(24.dp).height(24.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = onSync) { Icon(Icons.Default.Sync, "Jetzt abgleichen") }
                }
            }
            Text("$songs Lieder · $setlists Setlists", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val line = when {
                !hasServer -> "Kein Server – kein Abgleich"
                status == null -> "Noch nicht abgeglichen"
                status.error != null -> "Fehler: ${status.error}"
                status.running -> "Gleicht ab …"
                else -> "Abgeglichen" + (if (status.members.isNotEmpty()) " · Mitglieder: ${status.members.joinToString(", ")}" else "")
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (status?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = onLeave) { Text("Verlassen") }
                if (hasServer) TextButton(onClick = onDelete) { Text("Am Server löschen") }
            }
        }
    }
}

private fun Double.fmt() = ((this * 10).toLong() / 10.0).toString()

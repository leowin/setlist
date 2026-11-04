package at.zentriert.setlist.ui.band

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import at.zentriert.setlist.Instrument
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.band.Role
import at.zentriert.setlist.ui.metronome.BeatBackground
import at.zentriert.setlist.ui.InstrumentDropdown

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun BandScreen(vm: MainViewModel) {
    val settings = vm.settings
    var name by remember { mutableStateOf(settings.bandName) }
    var manualHost by remember { mutableStateOf("") }

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
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
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
                onValueChange = { name = it; vm.updateSettings { s -> s.copy(bandName = it) } },
                label = { Text("Mein Name in der Band") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.bandRole == null,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ich spiele", style = MaterialTheme.typography.labelLarge)
                InstrumentDropdown(selected = settings.myInstrument, noneLabel = "– keins –", onSelect = { vm.setMyInstrument(null, it) })
            }

            when (vm.bandRole) {
                null -> {
                    Button(onClick = vm::hostBand, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Band hosten (dieses Handy gibt den Takt vor)") }
                    Text("Gefundene Bands im WLAN", style = MaterialTheme.typography.labelLarge)
                    if (vm.bandHosts.isEmpty()) Text("Noch keine gefunden – alle Handys müssen im selben WLAN sein.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Über Internet (Relay-Server)", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = settings.serverUrl,
                                onValueChange = { v -> vm.updateSettings { it.copy(serverUrl = v) } },
                                label = { Text("Server, z. B. wss://…/ws") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = settings.roomCode,
                                onValueChange = { v -> vm.updateSettings { it.copy(roomCode = v.uppercase()) } },
                                label = { Text("Raum-Code (frei wählbar)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.joinCloud(asHost = true) }, modifier = Modifier.weight(1f)) { Text("Raum hosten") }
                                OutlinedButton(onClick = { vm.joinCloud(asHost = false) }, modifier = Modifier.weight(1f)) { Text("Beitreten") }
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
                    TextButton(onClick = vm::leaveBand) { Text("Band verlassen") }
                }
            }
        }
    }
}

private fun Double.fmt() = ((this * 10).toLong() / 10.0).toString()

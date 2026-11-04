package at.zentriert.setlist.ui.band

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.band.Band
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

const val PRIVATE_LABEL = "Privat (nur dieses Handy)"

/** Picks which band's songs and setlists are shown; "Privat" is what never leaves the phone. */
@Composable
fun BandSelector(bands: List<Band>, currentBandId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = bands.firstOrNull { it.id == currentBandId }?.name ?: "Privat"
    Box(modifier) {
        Row(
            Modifier.clickable { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
            Icon(Icons.Default.ArrowDropDown, "Band wählen")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(PRIVATE_LABEL) }, onClick = { onSelect(null); open = false })
            bands.forEach { b -> DropdownMenuItem(text = { Text(b.name) }, onClick = { onSelect(b.id); open = false }) }
        }
    }
}

/** "Copy into band …": every other band plus "Privat" as targets. */
@Composable
fun CopyToBandDialog(title: String, bands: List<Band>, currentBandId: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var target by remember { mutableStateOf<String?>(bands.firstOrNull { it.id != currentBandId }?.id ?: if (currentBandId != null) null else bands.firstOrNull()?.id) }
    val choices: List<Pair<String?, String>> = (listOf<Pair<String?, String>>(null to PRIVATE_LABEL) + bands.map { it.id to it.name }).filter { it.first != currentBandId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (choices.isEmpty()) Text("Keine andere Band vorhanden – unter „Band“ eine gründen oder einer beitreten.")
                choices.forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().clickable { target = id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = target == id, onClick = { target = id })
                        Text(name)
                    }
                }
                Text(
                    "Die Kopie ist danach unabhängig: Änderungen gelten nur in der jeweiligen Band.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(target) }, enabled = choices.any { it.first == target }) { Text("Kopieren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Invitation: QR code for the phone camera plus the link to send by messenger. */
@Composable
fun InviteDialog(band: Band, link: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einladung zu „${band.name}“") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (link.startsWith("http")) {
                    Box(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Image(rememberQrCodePainter(link), "QR-Code der Einladung", Modifier.size(220.dp))
                    }
                    Text("Mit der Kamera des anderen Handys scannen oder den Link schicken.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Kein Server eingetragen – Code zum Einfügen unter Band → „Einladung einfügen“:", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Text("Wer den Link hat, ist Mitglied und kann alles der Band lesen und ändern.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = onShare) { Text("Link teilen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Name for a new band, plus the one-time offer to move the private data into it. */
@Composable
fun CreateBandDialog(offerAdopt: Boolean, onCreate: (name: String, adopt: Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var adopt by remember { mutableStateOf(offerAdopt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Band gründen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name der Band") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (offerAdopt) AdoptRow(adopt) { adopt = it }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, adopt) }, enabled = name.isNotBlank()) { Text("Gründen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Paste an invitation link or code. */
@Composable
fun JoinBandDialog(offerAdopt: Boolean, onJoin: (text: String, adopt: Boolean) -> Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var adopt by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einladung einfügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Einladungslink oder -code eines Bandmitglieds hier einfügen. Ein QR-Code lässt sich auch einfach mit der Kamera-App scannen.")
                OutlinedTextField(text, { text = it; error = false }, label = { Text("Link oder Code") }, isError = error,
                    supportingText = if (error) ({ Text("Das ist keine Einladung.") }) else null, modifier = Modifier.fillMaxWidth())
                if (offerAdopt) AdoptRow(adopt) { adopt = it }
            }
        },
        confirmButton = { TextButton(onClick = { if (!onJoin(text, adopt)) error = true }, enabled = text.isNotBlank()) { Text("Beitreten") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun AdoptRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text("Meine bisherigen Lieder und Setlists in diese Band übernehmen", style = MaterialTheme.typography.bodyMedium)
    }
}

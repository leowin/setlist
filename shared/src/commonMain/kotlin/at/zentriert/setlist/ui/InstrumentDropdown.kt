package at.zentriert.setlist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import at.zentriert.setlist.Instrument

/**
 * The one way to pick an instrument in the app: a button showing symbol + name that opens a list.
 * [noneLabel] adds a "none" entry (e.g. "Weglassen"); [labelFor] can decorate entries (e.g. "+ Keys").
 */
@Composable
fun InstrumentDropdown(
    selected: Instrument?,
    onSelect: (Instrument?) -> Unit,
    modifier: Modifier = Modifier,
    options: List<Instrument> = Instrument.entries,
    noneLabel: String? = null,
    placeholder: String = "Instrument",
    labelFor: (Instrument) -> String = { "${it.symbol}  ${it.label}" },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text(selected?.let(labelFor) ?: noneLabel ?: placeholder)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (noneLabel != null) DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { inst ->
                DropdownMenuItem(text = { Text(labelFor(inst)) }, onClick = { onSelect(inst); open = false })
            }
        }
    }
}

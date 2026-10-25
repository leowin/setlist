package at.zentriert.setlist.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import at.zentriert.setlist.ImportState
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.SetlistParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: MainViewModel, state: ImportState) {
    val clipboard = LocalClipboardManager.current
    val songCount = state.cuts.count { it in state.lines.indices && state.lines[it].isNotBlank() }

    fun finishEditing() {
        state.editingText = false
        state.autoCut()
    }

    BackHandler { if (state.editingText && state.text.isNotBlank()) finishEditing() else vm.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.editingText) "Text einfügen" else "Zerschneiden") },
                navigationIcon = {
                    IconButton(onClick = { vm.goHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Abbrechen")
                    }
                },
                actions = {
                    if (state.editingText) {
                        IconButton(onClick = ::finishEditing, enabled = state.text.isNotBlank()) {
                            Icon(Icons.Default.Check, "Fertig")
                        }
                    } else {
                        IconButton(onClick = { state.editingText = true }) {
                            Icon(Icons.Default.Edit, "Text bearbeiten")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                if (state.editingText) {
                    Button(
                        onClick = ::finishEditing,
                        enabled = state.text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Weiter zum Zerschneiden") }
                } else {
                    Button(
                        onClick = { vm.finishImport(state) },
                        enabled = songCount > 0,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("$songCount Lieder übernehmen") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { state.name = it },
                label = { Text("Name der Setlist") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (state.editingText) {
                OutlinedButton(onClick = {
                    clipboard.getText()?.text?.let { state.text = it }
                }) {
                    Icon(Icons.Default.ContentPaste, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Aus Zwischenablage einfügen")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.text,
                    onValueChange = { state.text = it },
                    label = { Text("Text der Notiz") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                CutEditor(state)
            }
        }
    }
}

@Composable
private fun CutEditor(state: ImportState) {
    val lines = state.lines
    val cuts = state.cuts
    val songNumbers = remember(cuts, lines) {
        cuts.filter { it in lines.indices && lines[it].isNotBlank() }
            .sorted()
            .withIndex()
            .associate { (n, line) -> line to n + 1 }
    }
    val firstCut = songNumbers.keys.minOrNull() ?: Int.MAX_VALUE

    Text(
        "Tippe auf eine Zeile, um dort ein Lied beginnen zu lassen (✂) – oder um den Schnitt " +
            "wieder zu entfernen. Erste Zeile = Titel, der Rest = Anweisungen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = state::autoCut, label = { Text("Automatisch") })
        AssistChip(onClick = state::cutEveryLine, label = { Text("Jede Zeile ein Lied") })
        AssistChip(onClick = state::clearCuts, label = { Text("Alle Schnitte weg") })
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(lines) { i, line ->
            val number = songNumbers[i]
            when {
                line.isBlank() -> Spacer(Modifier.height(12.dp))
                number != null -> Column {
                    if (number > 1) HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Row(
                        Modifier.fillMaxWidth().clickable { state.toggleCut(i) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.ContentCut, "Schnitt", tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "$number",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            SetlistParser.cleanTitle(line),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                else -> {
                    val ignored = i < firstCut
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (ignored) 0.4f else 1f),
                        textDecoration = if (ignored) TextDecoration.LineThrough else null,
                        modifier = Modifier.fillMaxWidth().clickable { state.toggleCut(i) }
                            .padding(start = 40.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}

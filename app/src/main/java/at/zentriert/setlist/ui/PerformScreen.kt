package at.zentriert.setlist.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.PerformSettings
import at.zentriert.setlist.Setlist
import at.zentriert.setlist.Song

private val NotesColor = Color(0xFFE6E6E6)
private val DimColor = Color(0xFF9E9E9E)
private val ButtonGray = Color(0xFF2A2A2A)

@Composable
fun PerformScreen(vm: MainViewModel, setlist: Setlist) {
    val window = LocalContext.current.findActivity()?.window
    val settings = vm.settings
    val songs = setlist.songs
    val index = vm.performIndex.coerceIn(0, maxOf(0, songs.lastIndex))
    var jumping by remember { mutableStateOf(false) }

    // Keep the display awake while on stage (if enabled).
    DisposableEffect(window, settings.keepScreenOn) {
        if (settings.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Full screen: hide status and navigation bars (swipe from the edge shows them briefly).
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // "Screen off": black picture at minimum backlight. The display stays awake, so a tap or
    // pedal press brings the setlist back instantly – no lock screen in between.
    DisposableEffect(window, vm.blackout) {
        window?.setBrightness(
            if (vm.blackout) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
        )
        onDispose { window?.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    BackHandler { if (vm.blackout) vm.screenOn() else vm.edit(setlist.id) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopStrip(
                position = "${index + 1} / ${songs.size}",
                name = setlist.name,
                settings = settings,
                onClose = { vm.edit(setlist.id) },
                onPosition = { jumping = true },
                onSmaller = { vm.changeFontSize(-4f) },
                onBigger = { vm.changeFontSize(4f) },
                onNextCount = { vm.updateSettings { it.copy(nextCount = if (it.nextCount == 1) 2 else 1) } },
                onKeepOn = { vm.updateSettings { it.copy(keepScreenOn = !it.keepScreenOn) } },
                onScreenOff = vm::screenOff,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val upcoming = (1..settings.nextCount).map { songs.getOrNull(index + it) }
                val current = songs.getOrNull(index)
                if (maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize()) {
                        CurrentSong(current, index, settings.fontSize, Modifier.weight(1.4f).fillMaxHeight())
                        VerticalDivider(color = DimColor.copy(alpha = 0.4f))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            UpcomingSongs(upcoming, index, settings.fontSize)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        CurrentSong(current, index, settings.fontSize, Modifier.weight(1.4f).fillMaxWidth())
                        HorizontalDivider(color = DimColor.copy(alpha = 0.4f))
                        UpcomingSongs(upcoming, index, settings.fontSize)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(104.dp).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = vm::previous,
                    enabled = index > 0,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonGray, contentColor = Color.White),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Zurück", fontSize = 24.sp)
                }
                Button(
                    onClick = { vm.next(songs.size) },
                    enabled = index < songs.lastIndex,
                    modifier = Modifier.weight(1.6f).fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                ) {
                    Text("Weiter", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
            }
        }

        if (vm.blackout) {
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures { vm.screenOn() } },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text("Tippen zum Einschalten", color = Color(0xFF1E1E1E), modifier = Modifier.padding(32.dp))
            }
        }
    }

    if (jumping) {
        JumpDialog(songs, index, onPick = { vm.jumpTo(it); jumping = false }, onDismiss = { jumping = false })
    }
}

private fun android.view.Window.setBrightness(value: Float) {
    attributes = attributes.apply { screenBrightness = value }
}

@Composable
private fun TopStrip(
    position: String,
    name: String,
    settings: PerformSettings,
    onClose: () -> Unit,
    onPosition: () -> Unit,
    onSmaller: () -> Unit,
    onBigger: () -> Unit,
    onNextCount: () -> Unit,
    onKeepOn: () -> Unit,
    onScreenOff: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Beenden", tint = DimColor) }
        Row(
            Modifier.weight(1f).fillMaxHeight().clickable(onClick = onPosition),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(position, color = Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "  $name",
                color = DimColor,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSmaller, enabled = settings.fontSize > PerformSettings.MIN_FONT) {
            Icon(Icons.Default.TextDecrease, "Schrift kleiner", tint = DimColor)
        }
        IconButton(onClick = onBigger, enabled = settings.fontSize < PerformSettings.MAX_FONT) {
            Icon(Icons.Default.TextIncrease, "Schrift größer", tint = DimColor)
        }
        TextButton(onClick = onNextCount) {
            Text("+${settings.nextCount}", color = DimColor, fontSize = 18.sp)
        }
        IconButton(onClick = onKeepOn) {
            Icon(
                if (settings.keepScreenOn) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                if (settings.keepScreenOn) "Bildschirm bleibt an" else "Bildschirm darf ausgehen",
                tint = if (settings.keepScreenOn) Amber else DimColor,
            )
        }
        IconButton(onClick = onScreenOff) {
            Icon(Icons.Default.PowerSettingsNew, "Bildschirm aus", tint = DimColor)
        }
    }
}

@Composable
private fun CurrentSong(song: Song?, index: Int, fontSize: Float, modifier: Modifier) {
    // key(): start every song scrolled to the top.
    key(index) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (song == null) {
                Text("Keine Lieder", color = DimColor, fontSize = fontSize.sp)
                return@Column
            }
            Text(
                song.title,
                color = Color.White,
                fontSize = (fontSize * 1.5f).sp,
                lineHeight = (fontSize * 1.7f).sp,
                fontWeight = FontWeight.Bold,
            )
            if (song.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(song.notes, color = NotesColor, fontSize = fontSize.sp, lineHeight = (fontSize * 1.3f).sp)
            }
        }
    }
}

@Composable
private fun ColumnScope.UpcomingSongs(upcoming: List<Song?>, index: Int, fontSize: Float) {
    upcoming.forEachIndexed { k, song ->
        if (k > 0) HorizontalDivider(color = DimColor.copy(alpha = 0.25f))
        key(index + k + 1) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    if (k == 0) "ALS NÄCHSTES" else "DANACH",
                    color = DimColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (song == null) {
                    if (k == 0 || upcoming[k - 1] != null) {
                        Text("— Ende —", color = DimColor, fontSize = (fontSize * 0.9f).sp)
                    }
                } else {
                    Text(
                        song.title,
                        color = Amber,
                        fontSize = (fontSize * 1.05f).sp,
                        lineHeight = (fontSize * 1.25f).sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (song.notes.isNotBlank()) {
                        Text(
                            song.notes,
                            color = DimColor,
                            fontSize = (fontSize * 0.75f).sp,
                            lineHeight = (fontSize * 0.95f).sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpDialog(songs: List<Song>, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, current - 2))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Springe zu …") },
        text = {
            LazyColumn(state = listState) {
                itemsIndexed(songs) { i, song ->
                    Text(
                        "${i + 1}. ${song.title}",
                        color = if (i == current) Amber else Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

package com.matthew.serialmonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matthew.serialmonitor.core.BoardType
import com.matthew.serialmonitor.core.CrcType
import com.matthew.serialmonitor.core.Hex
import com.matthew.serialmonitor.core.Level
import com.matthew.serialmonitor.core.LineEnding
import com.matthew.serialmonitor.data.Macro
import com.matthew.serialmonitor.session.ConnState
import com.matthew.serialmonitor.session.Kind
import com.matthew.serialmonitor.session.SerialSession
import com.matthew.serialmonitor.session.TermLine
import com.matthew.serialmonitor.session.Target
import com.matthew.serialmonitor.transport.BleCharInfo
import com.matthew.serialmonitor.transport.BleUuids
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied ${label.lowercase()}", Toast.LENGTH_SHORT).show()
}

fun shareText(ctx: Context, session: SerialSession, text: String, baseName: String, mime: String = "text/plain") {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val f = File(dir, "$baseName-$stamp.txt")
    f.writeText(text)
    shareFile(ctx, f, mime).also { session.info("Shared ${f.name}") }
}

fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Top-app-bar actions for the Terminal tab: the most used toggles as icons, everything else in the overflow. */
@Composable
fun TerminalActions(vm: MainViewModel) {
    val session = vm.session
    val ctx = LocalContext.current
    val prefs by session.settings.state.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val crash by session.crash.collectAsStateWithLifecycle()
    val bleChars by session.bleChars.collectAsStateWithLifecycle()
    val filterOn by vm.filterOn.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(session.exportText(prefs.timestamps).toByteArray()) }
            session.message("Log saved")
        }.onFailure { session.message("Save failed: ${it.message}") }
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant

    IconToggleButton(prefs.autoscroll, { v -> session.settings.update { it.copy(autoscroll = v) } }) {
        Icon(Icons.Filled.VerticalAlignBottom, if (prefs.autoscroll) "Autoscroll on" else "Autoscroll off", tint = if (prefs.autoscroll) on else off)
    }
    IconToggleButton(filterOn, { vm.filterOn.value = it }) {
        Icon(Icons.Filled.FilterList, "Filter", tint = if (filterOn) on else off)
    }
    IconButton(onClick = { session.clearTerminal() }) { Icon(Icons.Filled.DeleteSweep, "Clear") }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(menu, { menu = false }) {
            CheckItem("Timestamps", prefs.timestamps) { session.settings.update { it.copy(timestamps = !it.timestamps) } }
            CheckItem("Hex view", prefs.hexView) { session.settings.update { it.copy(hexView = !it.hexView) } }
            CheckItem("Show macros", prefs.macrosExpanded) { session.settings.update { it.copy(macrosExpanded = !it.macrosExpanded) } }
            val isUsb = status.target is Target.Usb && status.state == ConnState.CONNECTED
            if (isUsb) {
                HorizontalDivider()
                CheckItem("DTR", status.dtr) { session.setDtr(!status.dtr) }
                CheckItem("RTS", status.rts) { session.setRts(!status.rts) }
                DropdownMenuItem({ Text("Reset board (${prefs.boardType.label})") }, {
                    menu = false; session.reset(if (prefs.boardType == BoardType.ESP32) BoardType.ESP32 else BoardType.ARDUINO)
                }, leadingIcon = { Icon(Icons.Filled.RestartAlt, null) })
                DropdownMenuItem({ Text("Reset: Arduino DTR pulse") }, { menu = false; session.reset(BoardType.ARDUINO) })
                DropdownMenuItem({ Text("Reset: ESP32 EN (RTS) pulse") }, { menu = false; session.reset(BoardType.ESP32) })
            }
            if (status.target is Target.Ble && bleChars != null) {
                DropdownMenuItem({ Text("BLE characteristics…") }, { menu = false; vm.showBlePicker.value = true })
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Save log…") }, {
                menu = false
                saveLauncher.launch("serial-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
            }, leadingIcon = { Icon(Icons.Filled.Save, null) })
            DropdownMenuItem({ Text("Share log") }, { menu = false; shareText(ctx, session, session.exportText(prefs.timestamps), "serial-log") },
                leadingIcon = { Icon(Icons.Filled.Share, null) })
            DropdownMenuItem({ Text("Copy all") }, { menu = false; copyToClipboard(ctx, "Log", session.exportText(prefs.timestamps)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
            crash?.let { c -> DropdownMenuItem({ Text("Copy last crash dump") }, { menu = false; copyToClipboard(ctx, "Backtrace", c.text) },
                leadingIcon = { Icon(Icons.Filled.BugReport, null) }) }
            if (status.state != ConnState.DISCONNECTED) {
                HorizontalDivider()
                DropdownMenuItem({ Text("Disconnect") }, { menu = false; session.disconnect() }, leadingIcon = { Icon(Icons.Filled.LinkOff, null) })
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem({ Text(label) }, onToggle, leadingIcon = { Checkbox(checked, null) })
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(vm: MainViewModel, modifier: Modifier) {
    val session = vm.session
    val ctx = LocalContext.current
    val prefs by session.settings.state.collectAsStateWithLifecycle()
    val snap by session.terminal.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val crash by session.crash.collectAsStateWithLifecycle()
    val bleChars by session.bleChars.collectAsStateWithLifecycle()
    val filterOn by vm.filterOn.collectAsStateWithLifecycle()
    val filter by vm.filterText.collectAsStateWithLifecycle()
    val showBlePicker by vm.showBlePicker.collectAsStateWithLifecycle()
    val dark = isDark(prefs.theme)
    var input by rememberSaveable { mutableStateOf("") }
    var editMacro by remember { mutableStateOf<Int?>(null) } // index, -1 = new
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val imeOpen = WindowInsets.isImeVisible
    // Landscape + keyboard leaves very little height: hide the macro row while typing there.
    val compact = imeOpen && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lines: List<TermLine> = remember(snap, filter, filterOn) {
        if (filterOn && filter.isNotBlank()) snap.lines.filter { it.text.contains(filter, ignoreCase = true) } else snap.lines
    }
    val showPending = snap.pending.isNotEmpty() && !(filterOn && filter.isNotBlank())
    val total = lines.size + if (showPending) 1 else 0

    LaunchedEffect(snap.version, prefs.autoscroll, total) {
        if (prefs.autoscroll && total > 0) listState.scrollToItem(total - 1)
    }
    // Dragging the list pauses autoscroll, like scrolling up in the Arduino IDE.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start && session.settings.value.autoscroll) session.settings.update { p -> p.copy(autoscroll = false) }
        }
    }

    fun doSend() {
        if (input.isEmpty() && prefs.lineEnding == LineEnding.NONE) return
        if (session.send(input)) input = ""
    }

    Column(modifier.imePadding()) {
        if (filterOn) {
            OutlinedTextField(filter, { vm.filterText.value = it },
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), singleLine = true,
                placeholder = { Text("Show only lines containing…") },
                leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                trailingIcon = { IconButton(onClick = { vm.filterText.value = ""; vm.filterOn.value = false }) { Icon(Icons.Filled.Close, "Close filter") } })
        }
        crash?.let { c ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BugReport, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Crash dump${if (c.count > 1) " #${c.count}" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { copyToClipboard(ctx, "Backtrace", c.text) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Text("Copy backtrace", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { session.dismissCrash() }) { Icon(Icons.Filled.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        // ---- terminal output (takes all remaining height)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(lines, key = { it.id }) { line -> TermRow(line, prefs.timestamps, prefs.hexView, dark, session, ctx) }
                    if (showPending) item(key = "pending") {
                        Text(if (prefs.hexView) Hex.dumpLine(snap.pendingRaw) else snap.pending, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    }
                }
            }
            if (lines.isEmpty() && !showPending) {
                Text(if (status.state == ConnState.CONNECTED) "Waiting for data…" else "Not connected — open the Connect tab",
                    Modifier.align(Alignment.Center).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            // Small state badges so non-default view modes are visible at a glance.
            val badges = listOfNotNull(if (prefs.hexView) "HEX" else null, if (prefs.timestamps) "TIME" else null,
                if (filterOn && filter.isNotBlank()) "FILTER" else null)
            if (badges.isNotEmpty()) Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.forEach { MiniTag(it) }
            }
            if (!prefs.autoscroll && total > 0) {
                SmallFloatingActionButton(onClick = {
                    session.settings.update { it.copy(autoscroll = true) }
                    scope.launch { listState.scrollToItem(total - 1) }
                }, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.ArrowDownward, "Scroll to bottom") }
            }
        }

        // ---- macros: one thin, collapsible row
        if (prefs.macrosExpanded && !compact) {
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(prefs.macros.size) { i ->
                    val m = prefs.macros[i]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { session.sendMacro(m) }, onLongClick = { editMacro = i }),
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(m.name, maxLines = 1, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).clickable { editMacro = -1 }) {
                        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                            if (prefs.macros.isEmpty()) { Spacer(Modifier.width(4.dp)); Text("Add a macro button", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
                item {
                    IconButton(onClick = { session.settings.update { it.copy(macrosExpanded = false) } }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ExpandMore, "Hide macros", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- send bar: one row
        SendBar(vm, input, { input = it }, ::doSend, connected = status.state == ConnState.CONNECTED)
    }

    editMacro?.let { idx ->
        MacroDialog(if (idx >= 0) prefs.macros.getOrNull(idx) else null,
            onDismiss = { editMacro = null },
            onSave = { m ->
                session.settings.update { p ->
                    p.copy(macros = if (idx >= 0) p.macros.toMutableList().also { it[idx] = m } else p.macros + m, macroTipShown = true)
                }
                if (!prefs.macroTipShown) session.message("Tip: long-press a macro to edit or delete it")
                editMacro = null
            },
            onDelete = if (idx >= 0) ({ session.settings.update { p -> p.copy(macros = p.macros.filterIndexed { i, _ -> i != idx }) }; editMacro = null }) else null)
    }
    if (showBlePicker) bleChars?.let { bc ->
        BlePickerDialog(bc.all, bc.notify, bc.write, onDismiss = { vm.showBlePicker.value = false }) { n, w ->
            session.selectBleChars(n, w); vm.showBlePicker.value = false
        }
    }
}

@Composable
private fun MiniTag(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Text(text, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SendBar(vm: MainViewModel, input: String, onInput: (String) -> Unit, onSend: () -> Unit, connected: Boolean) {
    val session = vm.session
    val prefs by session.settings.state.collectAsStateWithLifecycle()
    var tune by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val nonDefault = prefs.sendHex || prefs.crc != CrcType.NONE || !prefs.macrosExpanded

    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { tune = true }) {
                BadgedBox(badge = { if (nonDefault) Badge() }) { Icon(Icons.Filled.Tune, "Send options and history") }
            }
            DropdownMenu(tune, { tune = false }) {
                CheckItem("Send as hex", prefs.sendHex) { session.settings.update { it.copy(sendHex = !it.sendHex) } }
                CheckItem("Show macros", prefs.macrosExpanded) { session.settings.update { it.copy(macrosExpanded = !it.macrosExpanded) } }
                HorizontalDivider()
                Text("Append CRC", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                CrcType.entries.forEach { c ->
                    DropdownMenuItem({ Text(c.label) }, { session.settings.update { it.copy(crc = c) }; tune = false },
                        leadingIcon = { RadioButton(prefs.crc == c, null) })
                }
                if (prefs.history.isNotEmpty()) {
                    HorizontalDivider()
                    Text("History", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    prefs.history.take(15).forEach { h ->
                        DropdownMenuItem({ Text(h, maxLines = 1, fontFamily = FontFamily.Monospace) }, { tune = false; onInput(h) },
                            leadingIcon = { Icon(Icons.Filled.History, null) })
                    }
                }
            }
        }
        OutlinedTextField(
            input, onInput, Modifier.weight(1f), singleLine = true,
            placeholder = { Text(if (prefs.sendHex) "Hex, e.g. 01 03 FF" else "Message", maxLines = 1) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(24.dp),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefs.sendHex) MiniTag("HEX")
                    if (prefs.crc != CrcType.NONE) { Spacer(Modifier.width(2.dp)); MiniTag("CRC") }
                    Box {
                        TextButton(onClick = { ending = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(prefs.lineEnding.short, style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(ending, { ending = false }) {
                            LineEnding.entries.forEach { e ->
                                DropdownMenuItem({ Text(e.label) }, { session.settings.update { it.copy(lineEnding = e) }; ending = false },
                                    leadingIcon = { RadioButton(prefs.lineEnding == e, null) })
                            }
                        }
                    }
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = connected, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TermRow(line: TermLine, timestamps: Boolean, hex: Boolean, dark: Boolean, session: SerialSession, ctx: Context) {
    val color = when (line.kind) {
        Kind.TX -> TermColors.tx(dark)
        Kind.INFO -> TermColors.forLevel(if (line.level == Level.NONE) Level.INFO else line.level, dark)
        Kind.RX -> TermColors.forLevel(line.level, dark)
    } ?: MaterialTheme.colorScheme.onSurface
    val body = when {
        line.kind == Kind.INFO -> "— ${line.text} —"
        hex -> Hex.dumpLine(line.raw)
        line.kind == Kind.TX -> "→ ${line.text}"
        else -> line.text
    }
    val text = if (timestamps) session.fmtTime(line.time) + "  " + body else body
    Text(
        text,
        modifier = Modifier.fillMaxWidth()
            .then(if (line.level == Level.CRASH) Modifier.background(TermColors.crashBg) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = { copyToClipboard(ctx, "Line", line.text) }),
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 16.sp, color = color,
        fontWeight = if (line.level == Level.CRASH || line.level == Level.ERROR) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun MacroDialog(initial: Macro?, onDismiss: () -> Unit, onSave: (Macro) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var cmd by remember { mutableStateOf(initial?.command ?: "") }
    var hex by remember { mutableStateOf(initial?.hex ?: false) }
    val hexError = hex && runCatching { Hex.parse(cmd) }.isFailure
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New macro" else "Edit macro") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Button label") }, singleLine = true)
                OutlinedTextField(cmd, { cmd = it }, label = { Text("Command") }, singleLine = true, isError = hexError,
                    supportingText = { Text(if (hexError) "Invalid hex" else "Sent with the current line ending") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hex, { hex = it }); Text("Hex bytes") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Macro(name.ifBlank { cmd.take(12) }, cmd, hex)) }, enabled = cmd.isNotEmpty() && !hexError) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun BlePickerDialog(all: List<BleCharInfo>, notify: BleCharInfo?, write: BleCharInfo?, onDismiss: () -> Unit, onApply: (BleCharInfo?, BleCharInfo?) -> Unit) {
    var n by remember { mutableStateOf(notify) }
    var w by remember { mutableStateOf(write) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("BLE characteristics") },
        text = {
            LazyColumn {
                item { Text("Receive (notify/indicate)", style = MaterialTheme.typography.titleSmall) }
                items(all.filter { it.canNotify }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { n = c }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(n == c, { n = c })
                        Text("${BleUuids.shortName(c.uuid)}\nsvc ${BleUuids.shortName(c.service)} · ${c.propsLabel}", fontSize = 12.sp)
                    }
                }
                item { Text("Send (write)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(all.filter { it.canWrite }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { w = c }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(w == c, { w = c })
                        Text("${BleUuids.shortName(c.uuid)}\nsvc ${BleUuids.shortName(c.service)} · ${c.propsLabel}", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(n, w) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


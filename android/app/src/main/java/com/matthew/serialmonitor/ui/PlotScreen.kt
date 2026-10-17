package com.matthew.serialmonitor.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.max

private fun fmtNum(v: Float): String = when {
    v.isNaN() -> "–"
    abs(v) >= 10000 || (abs(v) < 0.01f && v != 0f) -> "%.3g".format(v)
    abs(v) >= 100 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

@Composable
fun PlotScreen(vm: MainViewModel, modifier: Modifier) {
    val session = vm.session
    val snap by session.plot.collectAsStateWithLifecycle()
    val paused by session.plotPaused.collectAsStateWithLifecycle()
    val prefs by session.settings.state.collectAsStateWithLifecycle()
    var hidden by rememberSaveable { mutableStateOf(setOf<String>()) }
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textPaint = remember { Paint().apply { isAntiAlias = true } }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.plotPaused.value = !paused }) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Resume" else "Pause")
            }
            IconButton(onClick = { session.clearPlot() }) { Icon(Icons.Filled.DeleteSweep, "Clear plot") }
            Text("Window", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            Dropdown("${prefs.plotWindow}", listOf(100, 250, 500, 1000, 2000, 5000), { "$it samples" }, { w -> session.settings.update { it.copy(plotWindow = w) } })
            Spacer(Modifier.weight(1f))
            Text(if (paused) "Paused" else "${snap.total} samples", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        }
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(snap.names.size) { i ->
                val name = snap.names[i]
                val color = TermColors.SERIES[i % TermColors.SERIES.size]
                val last = snap.series[i].lastOrNull { !it.isNaN() } ?: Float.NaN
                val off = name in hidden
                Row(Modifier.clickable { hidden = if (off) hidden - name else hidden + name }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(if (off) grid else color, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("$name ${fmtNum(last)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = if (off) onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            if (snap.size == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Print numbers to plot them, e.g.\nSerial.println(value);\nSerial.print(\"temp:\"); Serial.println(t);\n\"a:1,b:2\" or \"1 2 3\" per line",
                        color = onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            } else {
                Canvas(Modifier.fillMaxSize().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    val visible = snap.names.indices.filter { snap.names[it] !in hidden }
                    var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
                    for (i in visible) for (v in snap.series[i]) if (!v.isNaN()) { if (v < lo) lo = v; if (v > hi) hi = v }
                    if (lo == Float.POSITIVE_INFINITY) { lo = 0f; hi = 1f }
                    if (hi - lo < 1e-6f) { lo -= 1f; hi += 1f }
                    val pad = (hi - lo) * 0.05f
                    lo -= pad; hi += pad
                    val labelW = 56.dp.toPx()
                    val w = size.width - labelW
                    val h = size.height
                    textPaint.color = onSurface.toArgb()
                    textPaint.textSize = 11.sp.toPx()
                    for (g in 0..4) {
                        val y = h * g / 4f
                        drawLine(grid, Offset(labelW, y), Offset(size.width, y), 1f)
                        drawContext.canvas.nativeCanvas.drawText(fmtNum(hi - (hi - lo) * g / 4f), 0f, (y + textPaint.textSize / 2).coerceIn(textPaint.textSize, h), textPaint)
                    }
                    val n = snap.size
                    val cap = max(n, prefs.plotWindow)
                    val dx = w / max(1, cap - 1)
                    val offsetX = labelW + (cap - n) * dx // right-align: newest sample at the right edge
                    for (i in visible) {
                        val path = Path()
                        var pen = false
                        val arr = snap.series[i]
                        for (k in 0 until n) {
                            val v = arr[k]
                            if (v.isNaN()) { pen = false; continue }
                            val x = offsetX + k * dx
                            val y = h - (v - lo) / (hi - lo) * h
                            if (pen) path.lineTo(x, y) else { path.moveTo(x, y); pen = true }
                        }
                        drawPath(path, TermColors.SERIES[i % TermColors.SERIES.size], style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
    }
}

package com.matthew.serialmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.matthew.serialmonitor.core.BoardType
import com.matthew.serialmonitor.core.CrcType
import com.matthew.serialmonitor.core.Highlighter
import com.matthew.serialmonitor.core.LineEnding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Macro(val name: String, val command: String, val hex: Boolean = false)

data class Prefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val baud: Int = 9600,
    val dataBits: Int = 8,
    val parity: Int = 0,       // UsbSerialPort.PARITY_*
    val stopBits: Int = 1,     // 1, 2, or 3 (= 1.5, UsbSerialPort.STOPBITS_1_5)
    val boardType: BoardType = BoardType.ARDUINO,
    val resetOnConnect: Boolean = true,
    val dtr: Boolean = true,
    val rts: Boolean = true,
    val lineEnding: LineEnding = LineEnding.NL,
    val sendHex: Boolean = false,
    val crc: CrcType = CrcType.NONE,
    val autoscroll: Boolean = true,
    val timestamps: Boolean = false,
    val hexView: Boolean = false,
    val showSent: Boolean = true,
    val maxLines: Int = 5000,
    val keepScreenOn: Boolean = true,
    val autoReconnect: Boolean = true,
    val autoConnectOnAttach: Boolean = true,
    val errorKeywords: String = Highlighter.DEFAULT_ERROR.joinToString(", "),
    val warnKeywords: String = Highlighter.DEFAULT_WARN.joinToString(", "),
    val plotWindow: Int = 500,
    val csvLogging: Boolean = false,
    val fileLogging: Boolean = false,
    val macros: List<Macro> = DEFAULT_MACROS,
    val macrosExpanded: Boolean = true,
    val macroTipShown: Boolean = false,
    val history: List<String> = emptyList(),
) {
    companion object {
        val DEFAULT_MACROS = listOf(Macro("help", "help"), Macro("status", "status"))
    }
}

/** SharedPreferences-backed settings exposed as a StateFlow. */
class Settings(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Prefs> = _state.asStateFlow()
    val value: Prefs get() = _state.value

    @Synchronized fun update(f: (Prefs) -> Prefs) {
        val n = f(_state.value)
        if (n == _state.value) return
        _state.value = n
        save(n)
    }

    fun addHistory(cmd: String) {
        if (cmd.isBlank()) return
        update { p -> p.copy(history = (listOf(cmd) + p.history.filter { it != cmd }).take(50)) }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, def: E): E =
        runCatching { enumValueOf<E>(name ?: def.name) }.getOrDefault(def)

    private fun load(): Prefs {
        val d = Prefs()
        return Prefs(
            theme = enumOf(sp.getString("theme", null), d.theme),
            baud = sp.getInt("baud", d.baud),
            dataBits = sp.getInt("dataBits", d.dataBits),
            parity = sp.getInt("parity", d.parity),
            stopBits = sp.getInt("stopBits", d.stopBits),
            boardType = enumOf(sp.getString("boardType", null), d.boardType),
            resetOnConnect = sp.getBoolean("resetOnConnect", d.resetOnConnect),
            dtr = sp.getBoolean("dtr", d.dtr),
            rts = sp.getBoolean("rts", d.rts),
            lineEnding = enumOf(sp.getString("lineEnding", null), d.lineEnding),
            sendHex = sp.getBoolean("sendHex", d.sendHex),
            crc = enumOf(sp.getString("crc", null), d.crc),
            autoscroll = sp.getBoolean("autoscroll", d.autoscroll),
            timestamps = sp.getBoolean("timestamps", d.timestamps),
            hexView = sp.getBoolean("hexView", d.hexView),
            showSent = sp.getBoolean("showSent", d.showSent),
            maxLines = sp.getInt("maxLines", d.maxLines),
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            autoReconnect = sp.getBoolean("autoReconnect", d.autoReconnect),
            autoConnectOnAttach = sp.getBoolean("autoConnectOnAttach", d.autoConnectOnAttach),
            errorKeywords = sp.getString("errorKeywords", d.errorKeywords) ?: d.errorKeywords,
            warnKeywords = sp.getString("warnKeywords", d.warnKeywords) ?: d.warnKeywords,
            plotWindow = sp.getInt("plotWindow", d.plotWindow),
            csvLogging = sp.getBoolean("csvLogging", d.csvLogging),
            fileLogging = sp.getBoolean("fileLogging", d.fileLogging),
            macros = sp.getString("macros", null)?.let { parseMacros(it) } ?: d.macros,
            macrosExpanded = sp.getBoolean("macrosExpanded", d.macrosExpanded),
            macroTipShown = sp.getBoolean("macroTipShown", d.macroTipShown),
            history = sp.getString("history", null)?.let { s ->
                runCatching { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull()
            } ?: d.history,
        )
    }

    private fun save(p: Prefs) = sp.edit {
        putString("theme", p.theme.name)
        putInt("baud", p.baud); putInt("dataBits", p.dataBits); putInt("parity", p.parity); putInt("stopBits", p.stopBits)
        putString("boardType", p.boardType.name)
        putBoolean("resetOnConnect", p.resetOnConnect); putBoolean("dtr", p.dtr); putBoolean("rts", p.rts)
        putString("lineEnding", p.lineEnding.name); putBoolean("sendHex", p.sendHex); putString("crc", p.crc.name)
        putBoolean("autoscroll", p.autoscroll); putBoolean("timestamps", p.timestamps); putBoolean("hexView", p.hexView)
        putBoolean("showSent", p.showSent); putInt("maxLines", p.maxLines); putBoolean("keepScreenOn", p.keepScreenOn)
        putBoolean("autoReconnect", p.autoReconnect); putBoolean("autoConnectOnAttach", p.autoConnectOnAttach)
        putString("errorKeywords", p.errorKeywords); putString("warnKeywords", p.warnKeywords)
        putInt("plotWindow", p.plotWindow); putBoolean("csvLogging", p.csvLogging); putBoolean("fileLogging", p.fileLogging)
        putString("macros", JSONArray().apply {
            p.macros.forEach { m -> put(JSONObject().put("name", m.name).put("command", m.command).put("hex", m.hex)) }
        }.toString())
        putString("history", JSONArray(p.history).toString())
        putBoolean("macrosExpanded", p.macrosExpanded); putBoolean("macroTipShown", p.macroTipShown)
    }

    private fun parseMacros(s: String): List<Macro>? = runCatching {
        val a = JSONArray(s)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Macro(o.getString("name"), o.getString("command"), o.optBoolean("hex", false))
        }
    }.getOrNull()
}

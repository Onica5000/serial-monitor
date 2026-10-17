package com.matthew.serialmonitor.core

/**
 * Collects ESP32 / ESP8266 crash dumps (Guru Meditation, abort(), Backtrace, ESP8266 stack dumps)
 * so the user can copy them for decoding with addr2line / the desktop exception decoder.
 */
class CrashDetector(private val maxLines: Int = 120) {
    private val current = ArrayList<String>()
    private var active = false
    private var sawBacktrace = false
    private var inEsp8266Stack = false
    private var afterBacktrace = false

    /** Last completed (or in-progress) dump. */
    var lastDump: String? = null
        private set
    var dumpCount = 0
        private set

    /** Feed one decoded line. Returns true if the line belongs to a crash dump. */
    fun feed(line: String): Boolean {
        if (!active) {
            if (!isCrashMarker(line)) return false
            active = true; sawBacktrace = false; inEsp8266Stack = false; afterBacktrace = false
            current.clear()
            dumpCount++
        } else if (isRebootLine(line)) {
            finish(); return false
        } else if (afterBacktrace && line.isNotBlank()) {
            // A blank line ended the backtrace; keep the ELF hash (needed for decoding), stop at anything else.
            if (!line.contains("ELF file SHA256")) { finish(); return false }
        }
        current += line
        if (line.contains(">>>stack>>>")) inEsp8266Stack = true
        if (line.contains("Backtrace:")) sawBacktrace = true
        lastDump = current.joinToString("\n")
        if (sawBacktrace && line.isBlank() && !inEsp8266Stack) afterBacktrace = true
        val end = line.contains("ELF file SHA256") || line.contains("Rebooting...") || line.contains("<<<stack<<<") || current.size >= maxLines
        if (end) finish()
        return true
    }

    fun reset() { current.clear(); active = false; lastDump = null; dumpCount = 0 }

    private fun finish() {
        if (current.isNotEmpty()) lastDump = current.joinToString("\n").trimEnd()
        active = false
        afterBacktrace = false
        current.clear()
    }

    companion object {
        private val MARKERS = listOf(
            "Guru Meditation Error", "Backtrace:", "abort() was called", "panic'ed", "***ERROR*** A stack overflow",
            "Stack smashing protect failure", "CORRUPT HEAP", "assert failed:", "Debug exception reason",
            ">>>stack>>>", "CUT HERE FOR EXCEPTION DECODER", "Soft WDT reset", "Task watchdog got triggered",
        )
        private val ESP8266_EXC = Regex("^Exception \\(\\d+\\):")
        private val REBOOT = Regex("^(ets [A-Z][a-z]{2} |rst:0x|ESP-ROM:)")

        fun isCrashMarker(line: String): Boolean =
            MARKERS.any { line.contains(it) } || ESP8266_EXC.containsMatchIn(line)

        fun isRebootLine(line: String): Boolean = REBOOT.containsMatchIn(line)
    }
}

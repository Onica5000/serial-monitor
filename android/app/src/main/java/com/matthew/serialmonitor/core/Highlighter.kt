package com.matthew.serialmonitor.core

enum class Level { NONE, INFO, WARN, ERROR, CRASH }

/** Classifies a line by user keywords (case-insensitive) plus ESP-IDF / Arduino-ESP32 log prefixes. */
class Highlighter(errorKeywords: List<String>, warnKeywords: List<String>) {
    private val errors = errorKeywords.map { it.trim() }.filter { it.isNotEmpty() }
    private val warns = warnKeywords.map { it.trim() }.filter { it.isNotEmpty() }

    fun classify(line: String): Level {
        if (CrashDetector.isCrashMarker(line)) return Level.CRASH
        if (ESP_IDF_E.containsMatchIn(line) || line.startsWith("[E]") || ARDUINO_ESP_E.containsMatchIn(line)) return Level.ERROR
        if (errors.any { line.contains(it, ignoreCase = true) }) return Level.ERROR
        if (ESP_IDF_W.containsMatchIn(line) || line.startsWith("[W]") || ARDUINO_ESP_W.containsMatchIn(line)) return Level.WARN
        if (warns.any { line.contains(it, ignoreCase = true) }) return Level.WARN
        return Level.NONE
    }

    companion object {
        val DEFAULT_ERROR = listOf("ERROR", "FAIL", "FATAL", "Brownout")
        val DEFAULT_WARN = listOf("WARN")
        private val ESP_IDF_E = Regex("^E \\(\\d+\\)")
        private val ESP_IDF_W = Regex("^W \\(\\d+\\)")
        private val ARDUINO_ESP_E = Regex("^\\[\\s*\\d+]\\[E]")
        private val ARDUINO_ESP_W = Regex("^\\[\\s*\\d+]\\[W]")
    }
}

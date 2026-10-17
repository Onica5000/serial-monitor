package com.matthew.serialmonitor.core

/**
 * Parses lines the way the Arduino IDE 2.x Serial Plotter does:
 *  - "1 2 3", "1,2,3", "1\t2\t3"           -> unnamed series ("value 1", ...)
 *  - "temp:21.5 hum:40" / "temp:21.5,hum:40" -> named series
 *  - a comma/tab separated line of only labels ("temp,hum") sets names for subsequent unnamed values
 * Any other text line is ignored (returns null) so normal log output can be mixed in.
 */
class PlotParser {
    var headerLabels: List<String> = emptyList()
        private set

    fun reset() { headerLabels = emptyList() }

    fun parse(line: String): Map<String, Double>? {
        val s = COLON_SPACE.replace(line.trim(), ":")
        if (s.isEmpty()) return null
        val delimited = s.contains(',') || s.contains('\t') || s.contains(';')
        val tokens = (if (delimited) s.split(',', '\t', ';') else s.split(' '))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val result = LinkedHashMap<String, Double>()
        var unnamed = 0
        var allLabels = true
        for (tok in tokens) {
            val colon = tok.lastIndexOf(':')
            if (colon > 0) {
                val label = tok.substring(0, colon).trim()
                if (label.first().isDigit()) return null // e.g. a "12:30:45" clock, not a label
                val v = number(tok.substring(colon + 1)) ?: return null
                result[label] = v
                allLabels = false
            } else {
                val v = number(tok)
                if (v == null) {
                    if (!isLabel(tok)) return null
                    continue
                }
                allLabels = false
                val name = headerLabels.getOrNull(unnamed) ?: "value ${unnamed + 1}"
                unnamed++
                result[name] = v
            }
        }
        if (allLabels) {
            if (delimited && tokens.size >= 2 && tokens.all { isLabel(it) }) headerLabels = tokens
            return null
        }
        // Mixed numbers and bare words (e.g. "Temp 23 C") are ordinary text, not plot data.
        if (tokens.any { it.lastIndexOf(':') <= 0 && number(it) == null }) return null
        return result.ifEmpty { null }
    }

    private companion object { val COLON_SPACE = Regex(":\\s+") }

    private fun isLabel(t: String) = t.length <= 32 && t.first().isLetter() && t.all { it.isLetterOrDigit() || it == '_' || it == ' ' || it == '.' || it == '-' }

    private fun number(t: String): Double? {
        val v = t.trim().toDoubleOrNull() ?: return null
        return if (v.isFinite()) v else null
    }
}

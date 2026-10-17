package com.matthew.serialmonitor.core

import java.io.Writer

/** Writes plot samples as wide CSV. A new header row is written whenever a new series appears. */
class CsvLogger(private val out: Writer) {
    private val columns = ArrayList<String>()

    fun write(timeMs: Long, sample: Map<String, Double>) {
        var changed = columns.isEmpty()
        for (k in sample.keys) if (k !in columns) { columns += k; changed = true }
        if (changed) out.write("time_ms," + columns.joinToString(",") { escape(it) } + "\n")
        val sb = StringBuilder().append(timeMs)
        for (c in columns) { sb.append(','); sample[c]?.let { sb.append(fmt(it)) } }
        out.write(sb.append('\n').toString())
    }

    fun flush() = out.flush()
    fun close() = out.close()

    private fun fmt(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    companion object {
        fun escape(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

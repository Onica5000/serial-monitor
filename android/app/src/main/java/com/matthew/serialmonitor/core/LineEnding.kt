package com.matthew.serialmonitor.core

enum class LineEnding(val label: String, val short: String, val bytes: ByteArray) {
    NONE("No line ending", "None", ByteArray(0)),
    NL("Newline", "NL", byteArrayOf(0x0A)),
    CR("Carriage return", "CR", byteArrayOf(0x0D)),
    BOTH("Both NL & CR", "NL&CR", byteArrayOf(0x0D, 0x0A));

    fun apply(payload: ByteArray): ByteArray = payload + bytes
}

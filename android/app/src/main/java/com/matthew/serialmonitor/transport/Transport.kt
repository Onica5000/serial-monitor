package com.matthew.serialmonitor.transport

import java.io.IOException

interface TransportListener {
    fun onData(data: ByteArray)
    /** Connection lost (unplug, link loss, read error). Not called after a user-initiated close(). */
    fun onLost(error: Exception?)
    fun onInfo(message: String) {}
}

interface Transport {
    val description: String
    val supportsControlLines: Boolean get() = false

    @Throws(IOException::class)
    suspend fun open(listener: TransportListener)

    /** Queue bytes for sending. Thread safe. */
    fun write(data: ByteArray)

    fun setDtr(value: Boolean) {}
    fun setRts(value: Boolean) {}

    fun close()
}

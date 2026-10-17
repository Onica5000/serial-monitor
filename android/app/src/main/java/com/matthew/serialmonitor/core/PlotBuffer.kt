package com.matthew.serialmonitor.core

class PlotSnapshot(val names: List<String>, val series: List<FloatArray>, val total: Long) {
    val size: Int get() = series.firstOrNull()?.size ?: 0
    companion object { val EMPTY = PlotSnapshot(emptyList(), emptyList(), 0) }
}

/** Fixed-window ring buffer of samples; each added sample is one X step for every series. */
class PlotBuffer(capacity: Int = 500, private val maxSeries: Int = 12) {
    var capacity = capacity
        private set
    private val names = ArrayList<String>()
    private val data = ArrayList<FloatArray>()
    private var count = 0L

    @Synchronized fun add(sample: Map<String, Double>) {
        for (name in sample.keys) {
            if (name !in names && names.size < maxSeries) {
                names += name
                data += FloatArray(capacity) { Float.NaN }
            }
        }
        val idx = (count % capacity).toInt()
        for (i in names.indices) data[i][idx] = sample[names[i]]?.toFloat() ?: Float.NaN
        count++
    }

    @Synchronized fun clear() { names.clear(); data.clear(); count = 0 }

    @Synchronized fun resize(newCapacity: Int) {
        if (newCapacity == capacity) return
        capacity = newCapacity.coerceIn(10, 100_000)
        clear()
    }

    /** Oldest-to-newest copy of the visible window. */
    @Synchronized fun snapshot(): PlotSnapshot {
        val n = minOf(count, capacity.toLong()).toInt()
        val start = if (count > capacity) (count % capacity).toInt() else 0
        val series = data.map { arr -> FloatArray(n) { i -> arr[(start + i) % capacity] } }
        return PlotSnapshot(names.toList(), series, count)
    }
}

package io.github.yuroyami.kiteplayer.compose

import java.util.TreeMap

/** Pure timestamp/batch ownership logic behind Android's Window frame-metrics observer. */
internal class GpuCompletionBatchLedger<T> {
    private val batches = TreeMap<Long, MutableList<T>>()
    private val awaitingProof = mutableListOf<T>()

    val hasPending: Boolean get() = synchronized(batches) {
        batches.isNotEmpty() || awaitingProof.isNotEmpty()
    }

    /**
     * Retains a draw which could not be paired with a Window frame yet. The next exact draw adds
     * these older values to its batch, so that draw's GPU completion proves them in queue order.
     */
    fun holdUntilNextProof(value: T) = synchronized(batches) {
        awaitingProof += value
    }

    fun record(vsyncMillis: Long, value: T) = synchronized(batches) {
        batches.getOrPut(vsyncMillis, ::mutableListOf).apply {
            addAll(awaitingProof)
            awaitingProof.clear()
            add(value)
        }
    }

    /**
     * Returns null unless [vsyncMillis] exactly identifies one recorded draw. Once it does, GPU
     * command ordering proves every older batch complete too, including callbacks metrics dropped.
     */
    fun completeThroughExact(vsyncMillis: Long): List<T>? = synchronized(batches) {
        if (!batches.containsKey(vsyncMillis)) return@synchronized null
        val completed = mutableListOf<T>()
        val iterator = batches.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key > vsyncMillis) break
            completed += entry.value
            iterator.remove()
        }
        completed
    }

    /** Drops references after producer teardown makes every outstanding lease safe to release. */
    fun clear() = synchronized(batches) {
        batches.clear()
        awaitingProof.clear()
    }
}

/** Main-thread token ownership shared by every KiteVideo node drawing one state in one Window. */
internal class GpuConsumerBindingBook<T> {
    private val bindings = LinkedHashMap<Long, T>()
    private var nextBinding = 1L

    val isNotEmpty: Boolean get() = bindings.isNotEmpty()

    fun bind(value: T): Long = nextBinding++.also { bindings[it] = value }

    operator fun get(binding: Long): T? = bindings[binding]

    fun remove(binding: Long) {
        bindings.remove(binding)
    }

    fun values(): List<T> = bindings.values.toList()

    fun clear() = bindings.clear()
}

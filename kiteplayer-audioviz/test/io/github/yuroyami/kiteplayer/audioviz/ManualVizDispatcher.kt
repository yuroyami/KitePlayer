package io.github.yuroyami.kiteplayer.audioviz

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/** Runs scheduled analysis only when a test explicitly advances the worker. */
internal class ManualVizDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        var turns = 0
        while (tasks.isNotEmpty()) {
            check(++turns < 10_000) { "analysis worker did not suspend after draining its queue" }
            tasks.removeFirst().run()
        }
    }
}

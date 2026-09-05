@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package probe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate

/** Apple networking runs with the same active main run loop an application provides. */
fun main(args: Array<String>) {
    val completion = CompletableDeferred<Result<Unit>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        completion.complete(runCatching { runProbe(args) })
    }
    try {
        while (!completion.isCompleted) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        // The deferred safely publishes the background result. Await is already complete here.
        runBlocking { completion.await() }.getOrThrow()
    } finally {
        scope.cancel()
    }
}

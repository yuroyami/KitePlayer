@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@JsFun("(index) => process.argv[index + 2] ?? ''")
private external fun commandArgument(index: Int): String

@JsFun("(code) => { process.exitCode = code; }")
private external fun setExitCode(code: Int)

fun main() {
    CoroutineScope(Dispatchers.Default).launch {
        try {
            runProbe(arrayOf(commandArgument(0), commandArgument(1)))
        } catch (failure: Throwable) {
            println("NETWORK_PROBE_FAILED: $failure")
            setExitCode(1)
        }
    }
}

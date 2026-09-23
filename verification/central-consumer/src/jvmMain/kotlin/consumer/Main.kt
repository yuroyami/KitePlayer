package consumer

import io.github.yuroyami.kiteplayer.KitePlayerPlatform

fun main() {
    println("CONSUMER reached ${reachedSymbols().joinToString()}")
    println("CONSUMER availability ${KitePlayerPlatform.availability}")
}

package io.github.yuroyami.kiteplayer.io

/** The slice rule of [io.github.yuroyami.kiteplayer.MediaIo.read], which every door checks before it reads. */
internal fun requireReadSlice(into: ByteArray, offset: Int, length: Int) {
    require(offset in 0..into.size && length >= 0 && length <= into.size - offset) {
        "Read slice is outside the destination array"
    }
}

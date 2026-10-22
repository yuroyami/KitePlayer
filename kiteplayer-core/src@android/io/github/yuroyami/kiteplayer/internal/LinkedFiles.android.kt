package io.github.yuroyami.kiteplayer.internal

internal actual fun namesInFolder(folder: String): List<String>? = java.io.File(folder).list()?.toList()

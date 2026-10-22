package io.github.yuroyami.kiteplayer.io

/** A door could not open or read its bytes. The message names the file and the reason. */
public class MediaIoException(message: String) : Exception(message)

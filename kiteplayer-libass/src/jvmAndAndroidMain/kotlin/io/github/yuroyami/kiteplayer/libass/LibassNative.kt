package io.github.yuroyami.kiteplayer.libass

/**
 * The raw JNI boundary. Every method has a body in `native/src/libass_jni.c` and nowhere else,
 * and that file is marshalling around the shared `kite_ass.h` driver.
 *
 * Loading is the one platform difference between Android and the desktop JVM, so it is an
 * expect: Android's loader finds the packaged `.so` by name, the desktop unpacks the jar's copy.
 */
internal object LibassNative {
    /**
     * The reason loading failed, or null once it succeeded. Computed once: a library that cannot
     * load is reported once and never retried, and the desktop loader's temp-directory extraction
     * counts as a load failure too, not as a crash.
     */
    val loadFailure: Throwable? by lazy {
        try {
            loadLibassJni()
            null
        } catch (failure: UnsatisfiedLinkError) {
            failure
        } catch (failure: SecurityException) {
            failure
        } catch (failure: java.io.IOException) {
            failure
        }
    }

    val isLoaded: Boolean get() = loadFailure == null

    @JvmStatic external fun open(): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun openTrack(handle: Long, header: ByteArray): Boolean
    @JvmStatic external fun openDocument(handle: Long, script: ByteArray): Boolean
    @JvmStatic external fun addEvent(handle: Long, payload: ByteArray, startMillis: Long, durationMillis: Long)
    @JvmStatic external fun clearEvents(handle: Long)
    @JvmStatic external fun addFont(handle: Long, name: String, data: ByteArray)
    @JvmStatic external fun setFrame(
        handle: Long,
        frameWidth: Int, frameHeight: Int, storageWidth: Int, storageHeight: Int,
        marginTop: Int, marginBottom: Int, marginLeft: Int, marginRight: Int,
        fontScale: Double, linePosition: Double,
    )
    @JvmStatic external fun render(handle: Long, nowMillis: Long): ByteArray?
    @JvmStatic external fun libraryVersion(): Int
}

/** Loads `kiteplayer_libass_jni`, or throws [UnsatisfiedLinkError] when this build carries none. */
internal expect fun loadLibassJni()

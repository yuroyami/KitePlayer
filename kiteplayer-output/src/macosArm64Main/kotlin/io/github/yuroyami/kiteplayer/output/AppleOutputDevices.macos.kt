@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.AutofreeScope
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.CoreAudio.AudioObjectAddPropertyListener
import platform.CoreAudio.AudioObjectGetPropertyData
import platform.CoreAudio.AudioObjectPropertyAddress
import platform.CoreAudio.AudioObjectRemovePropertyListener
import platform.CoreAudio.kAudioHardwarePropertyDefaultOutputDevice
import platform.CoreAudio.kAudioObjectPropertyElementMain
import platform.CoreAudio.kAudioObjectPropertyName
import platform.CoreAudio.kAudioObjectPropertyScopeGlobal
import platform.CoreAudio.kAudioObjectSystemObject
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8

internal actual fun platformAppleOutputDevices(): AppleOutputDevices = MacOutputDevices

/**
 * The macOS answers, through the CoreAudio hardware API.
 *
 * The sink plays through the DefaultOutput unit, which moves to a new system default output by
 * itself. So a change of default is a notice to the application, not a reason to rebuild the sink.
 */
internal object MacOutputDevices : AppleOutputDevices {

    override fun watchDefaultOutput(onChange: (detail: String) -> Unit): AutoCloseable? =
        HardwareListener.register(
            objectId = kAudioObjectSystemObject.toUInt(),
            selector = kAudioHardwarePropertyDefaultOutputDevice,
        ) {
            val device = defaultOutputDevice()
            val name = deviceName(device) ?: "device $device"
            onChange("the default output changed to $name, and playback follows it")
        }

    /** The system default output device, or 0 when there is none. */
    fun defaultOutputDevice(): UInt = memScoped {
        val device = alloc<UIntVar>()
        val size = alloc<UIntVar>().apply { value = sizeOf<UIntVar>().toUInt() }
        val status = AudioObjectGetPropertyData(
            kAudioObjectSystemObject.toUInt(),
            globalAddress(kAudioHardwarePropertyDefaultOutputDevice).ptr,
            0u,
            null,
            size.ptr,
            device.ptr,
        )
        if (status == 0) device.value else 0u
    }

    /** What the system calls [device], or null when it will not say. */
    fun deviceName(device: UInt): String? = deviceString(device, kAudioObjectPropertyName)
}

/** One global-scope property address on the main element, in [this] scope's memory. */
internal fun AutofreeScope.globalAddress(selector: UInt): AudioObjectPropertyAddress =
    alloc<AudioObjectPropertyAddress>().apply {
        mSelector = selector
        mScope = kAudioObjectPropertyScopeGlobal
        mElement = kAudioObjectPropertyElementMain
    }

/** A string property of one audio object, such as its name or its UID, or null. */
internal fun deviceString(device: UInt, selector: UInt): String? {
    if (device == 0u) return null
    return memScoped {
        val string = alloc<CFStringRefVar>()
        val size = alloc<UIntVar>().apply { value = sizeOf<CFStringRefVar>().toUInt() }
        val status = AudioObjectGetPropertyData(device, globalAddress(selector).ptr, 0u, null, size.ptr, string.ptr)
        val reference = string.value
        if (status != 0 || reference == null) return@memScoped null
        try {
            reference.toKotlinString()
        } finally {
            CFRelease(reference)
        }
    }
}

/** The UTF-8 text of a CoreFoundation string. */
internal fun CFStringRef.toKotlinString(): String? = memScoped {
    val capacity = CFStringGetMaximumSizeForEncoding(CFStringGetLength(this@toKotlinString), kCFStringEncodingUTF8) + 1
    val buffer = allocArray<ByteVar>(capacity)
    if (CFStringGetCString(this@toKotlinString, buffer, capacity, kCFStringEncodingUTF8)) buffer.toKString() else null
}

/**
 * One CoreAudio property listener, registered on one object for one property.
 *
 * CoreAudio calls a C function with one pointer-sized value of ours. That value is a number from
 * [table] rather than a reference to a Kotlin object, so nothing here pins an object for C. [close]
 * removes the listener and then the number, so a notice already in flight when [close] runs finds
 * no entry and does nothing. The function runs on CoreAudio's notification thread, never on the
 * device's render thread.
 */
internal class HardwareListener private constructor(
    private val objectId: UInt,
    private val selector: UInt,
    private val token: Long,
) : AutoCloseable {

    private val closeLock = SynchronizedObject()
    private var closed = false

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        memScoped {
            AudioObjectRemovePropertyListener(objectId, globalAddress(selector).ptr, notice, token.toCPointer<CPointed>())
        }
        table.remove(token)
    }

    internal companion object {

        private val table = ListenerTable()

        /** Listeners registered and not yet closed, in this process. For tests. */
        internal val liveCount: Int get() = table.size

        /** Registers [onNotice], or answers null when CoreAudio refuses. */
        fun register(objectId: UInt, selector: UInt, onNotice: () -> Unit): HardwareListener? {
            val token = table.add(onNotice)
            val status = memScoped {
                AudioObjectAddPropertyListener(objectId, globalAddress(selector).ptr, notice, token.toCPointer<CPointed>())
            }
            if (status != 0) {
                table.remove(token)
                return null
            }
            return HardwareListener(objectId, selector, token)
        }

        /** The one C function every listener registers. It looks its value up in [table]. */
        private val notice = staticCFunction {
                _: UInt, _: UInt, _: CPointer<AudioObjectPropertyAddress>?, clientData: COpaquePointer? ->
            table.fire(clientData.toLong())
            0
        }
    }
}

/** Numbered callbacks. A number is never reused, so a stale notice cannot reach a newer listener. */
private class ListenerTable {
    private val lock = SynchronizedObject()
    private var next = 1L
    private val entries = HashMap<Long, () -> Unit>()

    val size: Int get() = synchronized(lock) { entries.size }

    fun add(callback: () -> Unit): Long = synchronized(lock) {
        val token = next++
        entries[token] = callback
        token
    }

    fun remove(token: Long) {
        synchronized(lock) { entries.remove(token) }
    }

    /** Runs the callback outside the lock. An exception must not cross back into CoreAudio. */
    fun fire(token: Long) {
        val callback = synchronized(lock) { entries[token] } ?: return
        try {
            callback()
        } catch (_: Throwable) {
            // A notice is advisory. Losing one is better than unwinding into a C caller.
        }
    }
}

@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreAudio.AudioHardwareCreateAggregateDevice
import platform.CoreAudio.AudioHardwareDestroyAggregateDevice
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import kotlin.random.Random

/**
 * An output device that a test can make and remove: a private aggregate device with one real output
 * as its only member. Private means that only this process sees it, and CoreAudio removes it when
 * the process ends, so a failed test leaves nothing behind on the Mac.
 */
internal class PrivateAggregateDevice private constructor(val id: UInt, val uid: String) {

    private var destroyed = false

    /** Removes the device, as an unplug would. Idempotent. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        AudioHardwareDestroyAggregateDevice(id)
    }

    companion object {
        /** A device over the output whose UID is [memberUid], or null when CoreAudio refuses. */
        fun create(memberUid: String): PrivateAggregateDevice? = memScoped {
            val uid = "io.github.yuroyami.kiteplayer.test.${Random.nextLong().toULong()}"
            val owned = mutableListOf<CFTypeRef?>()
            fun text(value: String) = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8).also { owned += it }
            val one = alloc<IntVar>().apply { value = 1 }

            val member = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                .also { owned += it }
            CFDictionarySetValue(member, text("uid"), text(memberUid))
            val members = CFArrayCreateMutable(null, 0, kCFTypeArrayCallBacks.ptr).also { owned += it }
            CFArrayAppendValue(members, member)

            // The keys are the values of AudioHardware.h's kAudioAggregateDevice*Key macros.
            val description = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                .also { owned += it }
            CFDictionarySetValue(description, text("uid"), text(uid))
            CFDictionarySetValue(description, text("name"), text("KitePlayer test device"))
            CFDictionarySetValue(description, text("private"), CFNumberCreate(null, kCFNumberSInt32Type, one.ptr).also { owned += it })
            CFDictionarySetValue(description, text("subdevices"), members)

            val id = alloc<UIntVar>()
            val status = AudioHardwareCreateAggregateDevice(description, id.ptr)
            owned.forEach { if (it != null) CFRelease(it) }
            if (status != 0 || id.value == 0u) null else PrivateAggregateDevice(id.value, uid)
        }
    }
}

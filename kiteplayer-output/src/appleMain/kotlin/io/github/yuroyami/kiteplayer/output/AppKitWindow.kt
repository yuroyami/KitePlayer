package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSApp
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSImageScaleProportionallyUpOrDown
import platform.AppKit.NSImageView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakeRect

/**
 * A window to draw video into.
 *
 * Deliberately the smallest thing that puts a picture on screen. AppKit owns the main thread and its
 * run loop, so the shape below is not a choice: the window is created on the main thread, [runEventLoop]
 * blocks there, and playback happens on other threads and hands frames over through
 * [AppKitVideoRenderer].
 *
 * This is the tier 0 presentation path, and it ships rather than being
 * scaffolding: it is the fallback when a GPU path fails, and the reference a GPU renderer is compared
 * against. It is also slow. Converting a frame on the CPU and building an image from it costs several
 * milliseconds at 1080p, which is fine for a small clip and not enough for 4K. A Metal renderer that
 * uploads planes as textures and converts in a shader is the tier 1 replacement.
 */
@OptIn(ExperimentalForeignApi::class)
public class AppKitWindow(
    title: String,
    width: Int,
    height: Int,
) {
    private val window: NSWindow
    internal val imageView: NSImageView

    init {
        NSApplication.sharedApplication().apply {
            setActivationPolicy(platform.AppKit.NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        }

        val frame = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble())
        window = NSWindow(
            contentRect = frame,
            styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or
                NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
            backing = NSBackingStoreBuffered,
            defer = false,
        )
        window.title = title

        imageView = NSImageView(frame = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        imageView.imageScaling = NSImageScaleProportionallyUpOrDown
        window.contentView = imageView

        window.center()
        window.makeKeyAndOrderFront(null)
    }

    /**
     * Hands the main thread to AppKit.
     *
     * Blocks until the application stops. Everything else, meaning demuxing, decoding, the audio device
     * and the presentation schedule, has to be running on other threads before this is called.
     */
    public fun runEventLoop() {
        NSApp?.activateIgnoringOtherApps(true)
        NSApp?.run()
    }

    /** Ends the event loop, which lets [runEventLoop] return. */
    public fun stop() {
        NSApp?.stop(null)
    }
}

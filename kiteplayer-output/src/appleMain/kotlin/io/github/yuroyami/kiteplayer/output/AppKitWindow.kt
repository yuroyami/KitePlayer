package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSApp
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeApplicationDefined
import platform.AppKit.NSImageScaleProportionallyUpOrDown
import platform.AppKit.NSImageView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.postEvent
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.darwin.NSObject

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

    /**
     * Called on the main thread when this window is closing, whatever closed it.
     *
     * A closed window is not a finished application. AppKit keeps its run loop going with nothing on
     * screen, so a player that ignores this keeps demuxing, decoding and playing audio headless, which
     * is what this window used to do. Whoever owns the playback session sets this, ends the session,
     * and calls [stop].
     */
    public var onCloseRequested: (() -> Unit)? = null

    /**
     * Retained here on purpose: `NSWindow.delegate` is a weak reference, so a delegate that only the
     * constructor held would be collected and the close would go unnoticed.
     */
    private val closeDelegate = WindowCloseDelegate { onCloseRequested?.invoke() }

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

        window.delegate = closeDelegate
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

    /**
     * Ends the event loop, which lets [runEventLoop] return.
     *
     * `stop` alone is a request, not an exit: AppKit checks it only after it finishes handling an event,
     * and a run loop with nothing to handle is asleep. Called from a playback thread when a file ends,
     * that leaves a window sitting there until the viewer happens to move the mouse. The dummy event
     * below is the wake-up, and posting one is safe from any thread.
     */
    public fun stop() {
        NSApp?.stop(null)
        val wake = NSEvent.otherEventWithType(
            type = NSEventTypeApplicationDefined,
            location = NSMakePoint(0.0, 0.0),
            modifierFlags = 0u,
            timestamp = 0.0,
            windowNumber = 0,
            context = null,
            subtype = 0,
            data1 = 0,
            data2 = 0,
        ) ?: return
        NSApp?.postEvent(wake, atStart = true)
    }
}

/**
 * Turns the window's own close into a callback.
 *
 * `windowWillClose` is the notification that arrives for every way a window goes away, including the
 * red button, the menu and a programmatic close, which is why it is the one to listen to rather than
 * `windowShouldClose`: that one is a veto and answering it is not the same as being told.
 */
private class WindowCloseDelegate(
    private val onWillClose: () -> Unit,
) : NSObject(), NSWindowDelegateProtocol {
    override fun windowWillClose(notification: NSNotification) {
        onWillClose()
    }
}

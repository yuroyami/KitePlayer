package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AppKit.NSApp
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeApplicationDefined
import platform.AppKit.NSImageScaleProportionallyUpOrDown
import platform.AppKit.NSImageView
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.postEvent
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.QuartzCore.CAMetalLayer
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.darwin.NSObject

/** What an [AppKitWindow] hosts for the picture. */
public enum class AppKitSurface {
    /** An image view, for [AppKitVideoRenderer]: the CPU fallback and the reference. */
    Image,

    /** A [CAMetalLayer], for [MetalVideoRenderer]: the default GPU path. */
    Metal,

    /**
     * An `AVSampleBufferDisplayLayer`, for [SampleBufferVideoRenderer]. It is the only layer a
     * picture in picture controller accepts, so this is the choice when the small window matters.
     */
    SampleBuffer,
}

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
    /** What the window hosts for the picture, which decides the renderer that can draw into it. */
    surface: AppKitSurface,
) {
    /**
     * The window with an image view, or with a [platform.QuartzCore.CAMetalLayer] when
     * [useMetalLayer] is true, for [MetalVideoRenderer]. The image view stays the CG fallback's home.
     */
    public constructor(
        title: String,
        width: Int,
        height: Int,
        useMetalLayer: Boolean = false,
    ) : this(title, width, height, if (useMetalLayer) AppKitSurface.Metal else AppKitSurface.Image)

    private val window: NSWindow
    internal val imageView: NSImageView

    /** The layer a [MetalVideoRenderer] draws into; null unless built with [AppKitSurface.Metal]. */
    public val metalLayer: platform.QuartzCore.CAMetalLayer?

    /**
     * The layer a [SampleBufferVideoRenderer] and a picture in picture controller share. Null unless
     * built with [AppKitSurface.SampleBuffer].
     */
    public val sampleBufferLayer: AVSampleBufferDisplayLayer?

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

        val host = surfaceHost(
            surface = surface,
            imageView = imageView,
            width = width.toDouble(),
            height = height.toDouble(),
            scale = window.screen?.backingScaleFactor ?: 2.0,
        )
        window.contentView = host.view
        metalLayer = host.metalLayer
        sampleBufferLayer = host.sampleBufferLayer

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

/** The view a window shows for its surface, and the layer inside it that a renderer draws into. */
internal class SurfaceHost(
    val view: NSView,
    val metalLayer: CAMetalLayer?,
    val sampleBufferLayer: AVSampleBufferDisplayLayer?,
)

/**
 * Builds the content view for [surface], [width] by [height] points at [scale].
 *
 * Both layers are the backing layer of their own host view, so they follow the window's size.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun surfaceHost(
    surface: AppKitSurface,
    imageView: NSImageView,
    width: Double,
    height: Double,
    scale: Double,
): SurfaceHost = when (surface) {
    AppKitSurface.Image -> SurfaceHost(imageView, metalLayer = null, sampleBufferLayer = null)
    AppKitSurface.Metal -> {
        // The drawable is sized in physical pixels, so a Retina window is not half resolution, and
        // the host view re-sizes it on every live resize and backing-scale change.
        val layer = CAMetalLayer()
        layer.contentsScale = scale
        layer.drawableSize = metalDrawableSize(width, height, scale)
        val host = MetalHostView(frame = CGRectMake(0.0, 0.0, width, height), metalLayer = layer)
        host.wantsLayer = true
        host.layer = layer
        SurfaceHost(host, metalLayer = layer, sampleBufferLayer = null)
    }
    AppKitSurface.SampleBuffer -> {
        // The layer scales the picture itself, keeping its aspect, so the host needs no callbacks.
        val layer = AVSampleBufferDisplayLayer()
        layer.videoGravity = AVLayerVideoGravityResizeAspect
        val host = NSView(frame = CGRectMake(0.0, 0.0, width, height))
        host.wantsLayer = true
        host.layer = layer
        SurfaceHost(host, metalLayer = null, sampleBufferLayer = layer)
    }
}

/**
 * The drawable size a Metal layer of [width] by [height] points needs at [scale].
 *
 * A drawable is measured in physical pixels and never rounds to zero: a window dragged to nothing
 * would otherwise ask Metal for a texture it refuses to make.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun metalDrawableSize(width: Double, height: Double, scale: Double): CValue<CGSize> =
    CGSizeMake((width * scale).coerceAtLeast(1.0), (height * scale).coerceAtLeast(1.0))

/**
 * The Metal layer's host view.
 *
 * A [platform.QuartzCore.CAMetalLayer] does not resize its own drawable, so without these two
 * callbacks a resized window drew a stale-sized picture that AppKit then scaled, and a window
 * dragged onto a display with another backing scale stayed at the old one.
 */
@OptIn(ExperimentalForeignApi::class)
internal class MetalHostView(
    frame: CValue<CGRect>,
    private val metalLayer: CAMetalLayer,
) : NSView(frame) {

    override fun setFrameSize(newSize: CValue<CGSize>) {
        super.setFrameSize(newSize)
        resizeDrawable()
    }

    override fun viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        resizeDrawable()
    }

    /** Also called by hand at construction, so the first drawable is right too. */
    fun resizeDrawable() {
        val scale = window?.backingScaleFactor ?: metalLayer.contentsScale
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = bounds.useContents {
            metalDrawableSize(size.width, size.height, scale)
        }
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

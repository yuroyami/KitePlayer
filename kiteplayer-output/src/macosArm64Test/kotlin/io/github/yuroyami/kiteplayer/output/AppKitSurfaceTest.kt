@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AppKit.NSImageView
import platform.CoreGraphics.CGRectMake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What each window surface hosts, built without a window.
 *
 * The sample buffer surface is the one a picture in picture controller can take, so its layer has
 * to be the host view's own layer and has to keep the picture's aspect as the window resizes.
 *
 * Layers read back from a view are compared by equality, not identity: Kotlin/Native can hand back
 * a new wrapper for the same Objective-C object.
 */
class AppKitSurfaceTest {

    private fun imageView() = NSImageView(frame = CGRectMake(0.0, 0.0, 640.0, 480.0))

    @Test
    fun theSampleBufferSurfaceHostsTheLayerAPictureInPictureControllerTakes() {
        val host = surfaceHost(AppKitSurface.SampleBuffer, imageView(), 640.0, 480.0, 2.0)
        val layer = assertNotNull(host.sampleBufferLayer)
        assertEquals(layer, host.view.layer, "the layer must be the host view's own layer")
        assertEquals(AVLayerVideoGravityResizeAspect, layer.videoGravity)
        assertNull(host.metalLayer)
    }

    @Test
    fun theImageAndMetalSurfacesHaveNoSampleBufferLayer() {
        val image = imageView()
        val imageHost = surfaceHost(AppKitSurface.Image, image, 640.0, 480.0, 2.0)
        assertSame(image, imageHost.view)
        assertNull(imageHost.sampleBufferLayer)
        assertNull(imageHost.metalLayer)

        val metalHost = surfaceHost(AppKitSurface.Metal, imageView(), 640.0, 480.0, 2.0)
        val metal = assertNotNull(metalHost.metalLayer)
        assertEquals(metal, metalHost.view.layer)
        assertNull(metalHost.sampleBufferLayer)
    }
}

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteffmpeg.HardwareAccel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The desktop JVM picks its hardware route by the operating system it runs on (#101). */
class DesktopJvmRouteTest {

    private val direct3d = HardwareRoute.Accel(HardwareAccel.D3d11va, HwdecKind.D3d11va)
    private val videoToolbox = HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox)

    @Test
    fun windowsAsksDirect3dForTheCodecsItAttaches() {
        listOf("Windows 11", "Windows 10", "Windows Server 2022").forEach { os ->
            assertEquals(direct3d, desktopJvmRoute("h264", os), os)
            assertEquals(direct3d, desktopJvmRoute("vc1", os), os)
            assertNull(desktopJvmRoute("av1", os), os)
        }
    }

    @Test
    fun macAsksVideoToolboxAsBefore() {
        assertEquals(videoToolbox, desktopJvmRoute("hevc", "Mac OS X"))
        assertNull(desktopJvmRoute("vp9", "Mac OS X"), "no Apple silicon decodes VP9")
    }

    @Test
    fun linuxAndEveryOtherSystemStaySoftware() {
        listOf("Linux", "FreeBSD", "SunOS", "").forEach { os ->
            assertNull(desktopJvmRoute("h264", os), "'$os' must decode in software")
        }
    }
}

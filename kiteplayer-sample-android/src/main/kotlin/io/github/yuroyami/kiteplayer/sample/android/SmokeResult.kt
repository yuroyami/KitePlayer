package io.github.yuroyami.kiteplayer.sample.android

import android.system.Os
import android.system.OsConstants
import io.github.yuroyami.kiteplayer.HwdecStatus
import java.io.File
import java.io.FileOutputStream

/**
 * The eleven-key smoke oracle of S1.c.6 step 6, written atomically: the temporary file is
 * flushed, fd-synced and renamed over `files/s1c-smoke.json`, so the polling harness never reads
 * a half-written object. The key set and every label are pinned by the jq predicate in the plan;
 * changing either here without changing there is a failed gate, on purpose.
 */
internal data class SmokeResult(
    val seekRequested: Boolean,
    val seekLanded: Boolean,
    val terminalState: String,
    val decodedFrames: Long,
    val submittedFrames: Long,
    val presentedFrames: Long,
    val surfaceFrame: Boolean,
    val audioUnderruns: Long,
    val hardwareDecode: String,
    val teardownCompleted: Boolean,
) {
    fun writeAtomically(filesDir: File) {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val json = buildString {
            append("{")
            append("\"pageSize\":").append(pageSize).append(",")
            append("\"seekRequested\":").append(seekRequested).append(",")
            append("\"seekLanded\":").append(seekLanded).append(",")
            append("\"terminalState\":\"").append(terminalState).append("\",")
            append("\"decodedFrames\":").append(decodedFrames).append(",")
            append("\"submittedFrames\":").append(submittedFrames).append(",")
            append("\"presentedFrames\":").append(presentedFrames).append(",")
            append("\"surfaceFrame\":").append(surfaceFrame).append(",")
            append("\"audioUnderruns\":").append(audioUnderruns).append(",")
            append("\"hardwareDecode\":\"").append(hardwareDecode).append("\",")
            append("\"teardownCompleted\":").append(teardownCompleted)
            append("}")
        }
        val tmp = File(filesDir, "s1c-smoke.json.tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write(json.toByteArray())
            stream.flush()
            stream.fd.sync()
        }
        check(tmp.renameTo(File(filesDir, "s1c-smoke.json"))) { "atomic rename failed" }
    }

    companion object {
        /**
         * The stable label of a hardware-decode status. Never the data class toString: the jq
         * predicate compares these exact strings, and a Kotlin rename must break the build here,
         * not silently change the oracle.
         */
        fun label(status: HwdecStatus): String = when (status) {
            HwdecStatus.Software -> "Software"
            is HwdecStatus.HardwareWithDownload -> "HardwareWithDownload(${status.kind})"
            is HwdecStatus.HardwareZeroCopy -> "HardwareZeroCopy(${status.kind})"
        }
    }
}

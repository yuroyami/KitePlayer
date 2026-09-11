package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import io.github.yuroyami.kiteplayer.audioviz.viz.TraceGain
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState

/** The readings every program gets. Strips are written once a frame, then handed to any number of programs. */
internal class ShaderInputs(private val seed: Float = 1f) {
    private val data = ShaderData()
    private val traceGain = TraceGain()
    private var writtenAt = Float.NaN

    /** Rewrites the spectrum, waveform, palette and history strips. Safe to call twice in a frame. */
    fun update(state: VizRenderState) {
        if (state.timeSeconds == writtenAt) return
        writtenAt = state.timeSeconds
        val frame = state.frame
        data.writeBands(frame.bandsRel)
        data.writeScope(frame.scope, traceGain.update(frame.scope, frame.scope, state.deltaSeconds))
        data.writePalette(state.palette)
        data.writeHistory(frame.bandsRel)
    }

    /** Sets the shared uniform block and the strips on [program]. */
    fun publish(program: ShaderProgram, state: VizRenderState, width: Float, height: Float) {
        val frame = state.frame
        program.uniform("uResolution", width, height)
        program.uniform("uTime", state.timeSeconds)
        program.uniform("uMusicTime", state.musicTime)
        program.uniform("uDelta", state.deltaSeconds)

        program.uniform("uLevel", frame.levelRel)
        program.uniform("uBass", state.bassMotion)
        program.uniform("uMid", state.body)
        program.uniform("uTreble", state.air)
        program.uniform("uEnergy", frame.energy)
        program.uniform("uMood", frame.mood)
        program.uniform("uDrive", state.drive)
        program.uniform("uDensity", frame.density)

        program.uniform("uBeat", frame.beat)
        program.uniform("uPulse", frame.pulse)
        program.uniform("uKick", frame.kickPulse)
        program.uniform("uSnare", frame.snarePulse)
        program.uniform("uHat", frame.hatPulse)

        program.uniform("uBpm", frame.bpm)
        program.uniform("uBeatPhase", frame.beatPhase)
        program.uniform("uBarPhase", frame.barPhase)
        program.uniform("uPhrasePhase", frame.phrasePhase)
        program.uniform("uBeatIn", frame.beatInSeconds)

        program.uniform("uCentroid", frame.centroid)
        program.uniform("uFlatness", frame.flatness)
        program.uniform("uWidth", frame.width)
        program.uniform("uKeyHue", frame.keyHue * frame.keyConfidence)
        program.uniform("uSeed", seed)
        data.bindTo(program)
    }

    fun reset() {
        data.clearHistory()
        traceGain.reset()
        writtenAt = Float.NaN
    }
}

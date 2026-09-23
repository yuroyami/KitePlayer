package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizResponse
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random

/**
 * A musical flight through districts of generated shapes.
 *
 * Every district is a [Recipe]: eight fold steps ending in a primitive, composed from a
 * per-launch seed by [RecipeComposer] and screened by [RecipeScreen] before it is shown. Music
 * drives thrust, banked reveals, travelling impact fronts and the recipe numbers themselves.
 * The eye stays in a protected corridor without orbit resets, cuts or preset crossfades.
 */
internal class Odyssey(worldSeed: Long = Random.nextLong()) : ShaderPreset(
    source = OdysseyScene.SOURCE,
    name = "Odyssey",
    bucket = VizEnergy.Mid,
    seed = 29f,
    kit = Kit(seed = 2_029L, detailKind = null,
        camera = Camera2D(wander = 0f, punch = 0f, roll = 0f, shake = 0f, cuts = false)),
) {
    override val mapping: VizMapping by mappingOf(
        VizDrive(VizDriver.Bands, VizProperty.Brightness),
        VizDrive(VizDriver.Level, VizProperty.Brightness),
        VizDrive(VizDriver.Bass, VizProperty.Shape, response = VizResponse.envelope(0.07f)),
        VizDrive(VizDriver.Mid, VizProperty.Shape, response = VizResponse.envelope(0.16f)),
        VizDrive(VizDriver.Treble, VizProperty.Brightness, response = VizResponse.envelope(0.08f)),
        VizDrive(VizDriver.LowHit, VizProperty.Brightness, VizCurve.Scaled),
        VizDrive(VizDriver.LowHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.LowHit, VizProperty.Speed, VizCurve.Scaled, VizResponse.envelope(0.12f)),
        VizDrive(VizDriver.LowHit, VizProperty.Spawn, VizCurve.Scaled, VizResponse.lifetime(1.1f)),
        VizDrive(VizDriver.BodyHit, VizProperty.Brightness, VizCurve.Scaled),
        VizDrive(VizDriver.BodyHit, VizProperty.Shape, VizCurve.Scaled),
        VizDrive(VizDriver.BodyHit, VizProperty.Speed, VizCurve.Scaled, VizResponse.envelope(0.12f)),
        VizDrive(VizDriver.HighHit, VizProperty.Brightness, VizCurve.Scaled),
        VizDrive(VizDriver.HighHit, VizProperty.Speed, VizCurve.Scaled, VizResponse.envelope(0.12f)),
        VizDrive(VizDriver.Level, VizProperty.Speed, response = VizResponse.envelope(0.12f)),
        VizDrive(VizDriver.Mood, VizProperty.Speed, response = VizResponse.envelope(0.16f)),
        VizDrive(VizDriver.Mood, VizProperty.Camera, response = VizResponse.envelope(0.35f)),
        VizDrive(VizDriver.Pulse, VizProperty.Camera, response = VizResponse.Rate),
        VizDrive(VizDriver.Drop, VizProperty.Speed, VizCurve.Discrete, VizResponse.envelope(0.12f)),
        VizDrive(VizDriver.Breakdown, VizProperty.Speed, VizCurve.Discrete, VizResponse.envelope(0.65f)),
    )
    // Native shader detail, with emission authored in the world, not a blur over the finished view.
    override val post: PostSpec get() = PostSpec.Off

    private val flightSpeed = VizParam("Flight speed", 0f, 2.5f, 1f)
    private val musicMotion = VizParam("Music motion", 0f, 2f, 1f)
    private val contrast = VizParam("Section contrast", 0.5f, 2f, 1.15f)
    private val pathCurves = VizParam("Path curves", 0f, 1f, 0.65f)
    private val banking = VizParam("Camera banking", 0f, 1f, 0.75f)
    private val cameraMotion = VizParam("Camera sweeps", 0f, 1.5f, 1f)
    private val waveMotion = VizParam("Impact waves", 0f, 1.5f, 1f)
    private val fieldOfView = VizParam("Field of view", 45f, 100f, 72f).apply { step = 1f }
    // How many of a recipe's eight folds run: four at zero, all eight at one.
    private val recipeDetail = VizParam("Recipe detail", Recipe.LEAST_STEPS.toFloat(), Recipe.STEPS.toFloat(), Recipe.STEPS.toFloat()).apply { step = 1f }
    private val architecture = VizParam("Architecture response", 0f, 1f, 0.85f)
    private val lightResponse = VizParam("Light response", 0f, 2f, 1f)
    private val emission = VizParam("Light intensity", 0f, 2f, 0.85f)
    private val atmosphere = VizParam("Atmosphere", 0f, 1f, 0.35f)
    private val particles = VizParam("World particles", 0f, 1f, 0.4f)
    private val colourTravel = VizParam("Colour travel", 0f, 1f, 0.25f)
    private val brightness = VizParam("Brightness", 0.25f, 1.5f, 0.85f)
    private val saturation = VizParam("Saturation", 0f, 1.5f, 0.85f)
    private val surfaceDetail = VizParam("Surface detail", 0f, 1f, 0.8f)
    private val drawDistance = VizParam("Draw distance", 40f, 100f, 72f)
    private val raySteps = VizParam("Ray steps", 64f, 112f, 88f).apply { step = 1f }
    override val params: List<VizParam> = listOf(
        flightSpeed, musicMotion, contrast, pathCurves, banking, cameraMotion, waveMotion, fieldOfView,
        recipeDetail, architecture, lightResponse, emission, atmosphere, particles, colourTravel,
        brightness, saturation, surfaceDetail, drawDistance, raySteps,
    )

    private data class District(val start: Double, val length: Float, val index: Long, val seed: Float) {
        val end: Double get() = start + length
    }

    // One district behind, the current one, and four ahead cover the entire allowed draw distance.
    // Double-precision travel remains on the host; only nearby camera-relative coordinates reach
    // the GPU. Long sessions cannot lose the small surface detail to a huge float camera position.
    private val composer = RecipeComposer(worldSeed)
    private val districts = ArrayList<District>(Recipe.SLOTS)
    private val recipes = ArrayList<Recipe>(Recipe.SLOTS)
    private val packedDistricts = FloatArray(Recipe.SLOTS * 4)
    private val packedRecipes = FloatArray(Recipe.SLOTS * Recipe.STEPS * 4)
    private val packedTails = FloatArray(Recipe.SLOTS * 4)
    private var nextDistrict = 0L
    private val flight = OdysseyFlight()
    private val travelled: Double get() = flight.travelled
    private var activity = 0f
    private var energy = 0f
    private var bass = 0f
    private var middle = 0f
    private var treble = 0f
    private var kick = 0f
    private var snare = 0f
    private var hat = 0f
    private var surge = 0f
    private var colour = 0f
    private var curveAmount = 0.65f

    // The field is compiled twice: one instance answers the quarter-resolution coarse search and
    // one the native-resolution refinement, because each maps a query to its own pixel grid.
    private val coarse by lazy { ShaderProgram(OdysseyScene.COARSE_SOURCE) }
    private val coarseField by lazy { ShaderProgram(OdysseyScene.DISTANCE_SOURCE) }
    private val packedDepth by lazy { ShaderProgram(OdysseyScene.PACK_SOURCE) }
    private val distanceField by lazy { ShaderProgram(OdysseyScene.DISTANCE_SOURCE) }
    private val refinement by lazy { ShaderProgram(OdysseyScene.REFINE_SOURCE) }
    private val nativePasses by lazy { listOf(refinement) }
    private val extraPrograms by lazy { listOf(coarse, coarseField, packedDepth, distanceField, refinement) }
    override val additionalCompileError: String?
        get() = extraPrograms.firstOrNull { !it.available }?.let { it.error ?: "Odyssey program unavailable" }
    private val cameraBasis = FloatArray(12)
    private val routePhases = FloatArray(3)
    private var viewportWidth = 1f
    private var viewportHeight = 1f

    init { resetJourney() }

    override fun advance(state: VizRenderState) {
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.25f)
        val frame = state.frame
        val audible = frame.audible
        flight.advance(state, gestures, flightSpeed.value, musicMotion.value, contrast.value,
            cameraMotion.value, banking.value, waveMotion.value)
        activity = flight.activity
        surge = flight.launch
        kick = maxOf(kick * exp(-dt / 0.22f), gestures.kickAccent.coerceAtMost(1.2f) * audible)
        snare = maxOf(snare * exp(-dt / 0.3f), gestures.snareAccent.coerceAtMost(1.2f) * audible)
        hat = maxOf(hat * exp(-dt / 0.14f), gestures.hatAccent.coerceAtMost(1.2f) * audible)
        energy = settle(energy, frame.energy * audible, dt, 0.3f)
        bass = settle(bass, frame.bassRel * audible, dt, 0.07f)
        middle = settle(middle, frame.midRel * audible, dt, 0.16f)
        treble = settle(treble, frame.trebleRel * audible, dt, 0.08f)
        curveAmount = settle(curveAmount, pathCurves.value, dt, 2f)
        colour = (colour + dt * audible * 0.025f * colourTravel.value * (0.15f + activity)) % 1f

        // Retire only a district entirely behind the camera. The replacement is well beyond the
        // far plane, so its new layout cannot appear suddenly in the visible world.
        while (travelled >= districts[1].end) {
            districts.removeAt(0)
            recipes.removeAt(0)
            addDistrict(makeDistrict(nextDistrict++, districts.last().end))
        }
        packDistricts()
        // The music reaches the shapes as step numbers: bass pushes the mirrors, hits breathe the
        // scales and mids turn the rotations. Reduced motion stills them.
        val response = architecture.value * state.motionScale
        for (slot in recipes.indices) {
            recipes[slot].pack(packedRecipes, packedTails, slot,
                bass = bass * response, hit = kick * response, mid = middle * response)
        }
    }

    override fun shaderSize(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
    }

    override fun extraUniforms(program: ShaderProgram, state: VizRenderState) {
        routePhases[0] = phase(0.025); routePhases[1] = phase(0.061); routePhases[2] = phase(0.021)
        OdysseyCamera.write(cameraBasis, routePhases, curveAmount, flight.x, flight.y,
            flight.yaw, flight.pitch, flight.bank)
        val coarseWidth = viewportWidth * OdysseyScene.DEPTH_SCALE
        val coarseHeight = viewportHeight * OdysseyScene.DEPTH_SCALE
        // A child is bound after its uniforms are set, because Android captures its state at binding.
        coarseField.uniform("uResolution", coarseWidth, coarseHeight)
        publishJourney(coarseField, state)
        coarse.uniform("uResolution", coarseWidth, coarseHeight)
        publishJourney(coarse, state)
        coarse.childProgram("uScene", coarseField)
        packedDepth.childProgram("uMarch", coarse)
        distanceField.uniform("uResolution", viewportWidth, viewportHeight)
        publishJourney(distanceField, state)
        refinement.uniform("uResolution", viewportWidth, viewportHeight)
        publishJourney(refinement, state)
        refinement.uniform("uDepthScale", OdysseyScene.DEPTH_SCALE)
        refinement.childProgram("uScene", distanceField)
        publishJourney(program, state)
        program.uniform("uEnergy", energy)
        program.uniform("uExposure", brightness.value * (0.6f + 0.4f * energy) * state.lightScale)
    }

    override fun DrawScope.drawShader(program: ShaderProgram, state: VizRenderState) {
        program.drawPasses(this, packedDepth, viewportWidth * OdysseyScene.DEPTH_SCALE,
            viewportHeight * OdysseyScene.DEPTH_SCALE, nativePasses, "uDepth")
    }

    private fun publishJourney(program: ShaderProgram, state: VizRenderState) {
        program.uniforms("uDistricts", packedDistricts)
        program.uniforms("uRecipe", packedRecipes)
        program.uniforms("uRecipeTail", packedTails)
        program.uniforms("uRoutePhase", routePhases)
        program.uniforms("uCamera", cameraBasis)
        program.uniform("uRoute", curveAmount, banking.value * state.motionScale,
            1f / tan(fieldOfView.value * (PI / 360.0).toFloat()) /
                (1f + (0.16f * activity + 0.05f * flight.impact) * cameraMotion.value * state.motionScale), drawDistance.value)
        program.uniform("uDynamics", flight.bank, (flight.speed / 26f).coerceIn(0f, 1f), flight.impact, activity)
        program.uniforms("uWave", flight.waves)
        program.uniform("uShape",
            recipeDetail.value.roundToInt().toFloat(),
            0f, 0f, 0f)
        program.uniform("uSound", bass, middle, treble, activity)
        program.uniform("uHits", kick, snare, hat, surge)
        program.uniform("uResponse", architecture.value, lightResponse.value, emission.value, surfaceDetail.value)
        program.uniform("uFinish", atmosphere.value, particles.value, saturation.value, raySteps.value)
        program.uniform("uColour", colour)
        program.uniform("uParticleTravel", (travelled % 96.0).toFloat())
    }

    // All detail lives at a world-space depth and is occluded by architecture.
    override fun DrawScope.drawTop(state: VizRenderState) {}

    override fun onReset() {
        flight.reset()
        activity = 0f; energy = 0f; bass = 0f; middle = 0f; treble = 0f
        kick = 0f; snare = 0f; hat = 0f; surge = 0f
        colour = 0f
        curveAmount = pathCurves.value
        composer.reset()
        resetJourney()
    }

    private fun resetJourney() {
        districts.clear()
        recipes.clear()
        val previous = makeDistrict(-1L, 0.0)
        addDistrict(previous.copy(start = -previous.length.toDouble()))
        nextDistrict = 0L
        while (districts.size < Recipe.SLOTS) addDistrict(makeDistrict(nextDistrict++, districts.last().end))
        packDistricts()
        for (slot in recipes.indices) recipes[slot].pack(packedRecipes, packedTails, slot)
    }

    /** The composer keeps its own state, so only the thread that advances this drawing may call it. */
    private fun addDistrict(district: District) {
        districts.add(district)
        recipes.add(composer.recipeFor(district.index, district.length))
    }

    private fun packDistricts() {
        for (slot in districts.indices) {
            val district = districts[slot]
            packedDistricts[slot * 4] = (district.start - travelled).toFloat()
            packedDistricts[slot * 4 + 1] = district.length
            packedDistricts[slot * 4 + 2] = recipes[slot].safety
            packedDistricts[slot * 4 + 3] = district.seed
        }
    }

    private fun makeDistrict(index: Long, start: Double): District {
        // Integer mixing is stable on every target. The seed sets the length and the colour;
        // the composer chooses the shape.
        var bits = (index xor (index ushr 32)).toInt() xor 2_029
        bits = (bits xor (bits ushr 16)) * 0x45d9f3b
        bits = (bits xor (bits ushr 16)) * 0x45d9f3b
        bits = bits xor (bits ushr 16)
        val seed = (bits and 0xffff) / 65_535f
        return District(start, 66f + 18f * seed, index, seed)
    }

    private fun phase(rate: Double): Float = ((travelled * rate) % (2.0 * PI)).toFloat()

    private fun settle(value: Float, target: Float, dt: Float, seconds: Float): Float =
        value + (target - value) * (1f - exp(-dt / seconds))
}

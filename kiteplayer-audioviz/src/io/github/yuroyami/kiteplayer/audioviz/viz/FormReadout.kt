package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

/**
 * What form a drawing is in and how often it has changed, for a test or a readout.
 *
 * A drawing with named forms publishes one through [Visualization.forms]. A drawing with none
 * publishes null, which keeps it out of the evolution test.
 */
@AudioVizAuthoringApi
public class FormReadout(
    /** The name of the form on screen, such as "Vortex". */
    public val form: String,
    /** How many morphs have started since the drawing was shown. */
    public val morphs: Int,
    /** How many births have happened since the drawing was shown. */
    public val births: Int,
)

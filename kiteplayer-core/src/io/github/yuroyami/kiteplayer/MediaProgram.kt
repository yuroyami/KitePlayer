package io.github.yuroyami.kiteplayer

/**
 * One programme of the media: tracks that play together, such as one channel of a DVB recording or
 * an IPTV multiplex, which carries several channels in one transport stream (#505).
 *
 * [Tracks.programs] lists them and [Tracks.selectedProgram] names the one the player picks its
 * tracks from. [KitePlayer.selectProgram] switches to another, and [DemuxPolicy.program] names one
 * before the open.
 */
public data class MediaProgram(
    /**
     * The programme number the container states, which in a transport stream is the service id
     * that the broadcaster's guide names the channel by. No other programme of the media has it.
     */
    val number: Int,
    /** The tracks of this programme, in the order the container lists them. */
    val tracks: List<TrackId>,
    /** The channel's name, or null when the container names none. */
    val name: String? = null,
    /** Who provides the channel, or null when the container says nothing. */
    val provider: String? = null,
    /** Every tag the container gives the programme. */
    val metadata: Map<String, String> = emptyMap(),
)

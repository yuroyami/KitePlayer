#!/usr/bin/env bash
# Regenerate the test clips used by KitePlayer's playback tests.
# Needs the ffmpeg CLI on PATH, recent enough to know -fps_mode and -enc_time_base, which the
# variable frame rate clip below uses. Writes into testmedia/, which is gitignored.
set -euo pipefail

# ---------------------------------------------------------------------------------------------
# The ffmpeg series these clips were last generated and verified against.
#
# This RECORDS rather than refuses, and the reason is the same one the checksums below give for not
# gating: an ffmpeg upgrade is legitimate, and a gate that turns every legitimate upgrade into a red
# build is a gate somebody turns off. This one did exactly that. It refused from 2026-08-25 until
# 2026-08-31, on every run, because the macOS runner image moved to 8.1 while this line said 8.0.
# Six consecutive red builds, none of them about the code.
#
# It was added after the 2026-08-24 incident, where the subtitle-cue matrix row passed on a Mac
# against 8.0 and failed on a runner against 8.1.2 because the two muxers interleave the first cue
# differently. THAT TEST WAS WRONG TO DEPEND ON INTERLEAVING AND WAS FIXED. The pin was belt and
# braces over a hole that had already been closed, and the braces cost more than the hole did.
#
# What survives is the useful half. A mismatch prints loudly and lands in the MANIFEST, so the next
# "why did that row behave differently" starts from a diff instead of an investigation. Set
# TESTMEDIA_STRICT_FFMPEG=1 to make it a refusal again, which is worth doing while bisecting a
# version-sensitive failure.
#
# Compared at the MAJOR, because that is the boundary the oracles move on: 8.0 and 8.1.2 produce the
# same reference PCM, and 9.0 trims 384 trailing AAC frames that 8.x keeps. The player decodes
# through KiteFFmpeg's own FFmpeg 9.x (KiteFFmpeg 0.3.0 carries 9.0.2), so an oracle from another
# major is a different answer, and a minor bump that once changed packet interleaving was answered
# by making that test robust. When KiteFFmpeg moves to the next major, this number and CI's ffmpeg
# formula move with it.
EXPECTED_FFMPEG_SERIES=9

# Field 3 of ffmpeg's first line, reduced to the major. The regex tolerates a leading "n" and any
# build suffix, so "8.0", "8.1.2" and "n8.0-static" all answer 8.
ffmpeg_series() {
    ffmpeg -version 2>/dev/null | head -1 | awk '{print $3}' \
        | grep -oE '[0-9]+' | head -1
}

check_ffmpeg() {
    if ! command -v ffmpeg >/dev/null 2>&1; then
        echo "testmedia.sh: no ffmpeg on PATH. It builds every clip these tests read." >&2
        exit 1
    fi
    local actual
    actual="$(ffmpeg_series)"
    if [ -z "$actual" ]; then
        echo "testmedia.sh: could not read a version out of '$(ffmpeg -version 2>/dev/null | head -1)'." >&2
        exit 1
    fi
    if [ "$actual" = "$EXPECTED_FFMPEG_SERIES" ]; then
        return 0
    fi
    if [ "${TESTMEDIA_STRICT_FFMPEG:-}" = "1" ]; then
        cat >&2 <<REFUSAL
testmedia.sh refuses: ffmpeg is $actual, the recorded series is $EXPECTED_FFMPEG_SERIES, and
TESTMEDIA_STRICT_FFMPEG=1 asked for a refusal.

Install ffmpeg $EXPECTED_FFMPEG_SERIES, or drop the variable to generate anyway.
REFUSAL
        exit 2
    fi
    cat >&2 <<NOTICE
testmedia.sh: ffmpeg is $actual; these clips were last recorded against $EXPECTED_FFMPEG_SERIES.

Generating anyway. The encoder version can change the bytes, so if a media-backed test behaves
differently after this, the MANIFEST records which ffmpeg made the clips and that is the first
diff to look at. TESTMEDIA_STRICT_FFMPEG=1 turns this back into a refusal.
NOTICE
    return 0
}

# --check-only verifies the pin and generates nothing, so CI can fail on a toolchain move in one
# second instead of after the 27 seconds of encoding below.
if [ "${1:-}" = "--check-only" ]; then
    check_ffmpeg
    # Re-derived rather than assumed: check_ffmpeg also returns 0 when the override waved a
    # MISMATCH through, and reporting that as "matches" would be the check telling its own lie.
    checked="$(ffmpeg_series)"
    if [ "$checked" = "$EXPECTED_FFMPEG_SERIES" ]; then
        echo "ffmpeg $checked matches the recorded series ($EXPECTED_FFMPEG_SERIES)."
    else
        echo "ffmpeg $checked differs from the recorded series ($EXPECTED_FFMPEG_SERIES); the MANIFEST will say so."
    fi
    exit 0
fi
check_ffmpeg

# ---------------------------------------------------------------------------------------------
# What this ffmpeg can do, asked of the ffmpeg itself rather than read off its version (#418).
#
# The series above is recorded and never gates, so every step below has to work on whatever ffmpeg
# is on PATH, or say plainly that it cannot. Two steps need more than ffmpeg 6.1, which Ubuntu 24.04
# ships, has:
#
# - The colour clips stamp their chroma siting. From ffmpeg 7 the encoder copies the filtered
#   frame's siting and ignores its own option, so the siting has to be on the frame, through
#   setparams. ffmpeg 6.1's setparams has no chroma_location, but its encoder still takes
#   -chroma_sample_location, so there the siting goes on the encoder. Each clip's siting is then
#   read back, so a fallback that stamps nothing fails here instead of in a golden test.
# - The fragmented TTML-in-MP4 clip. ffmpeg 8 and earlier refuse to fragment TTML in MP4, so that
#   one clip cannot be made, and nothing else can stand in for it. The step is skipped with the
#   reason, the skip is listed in the MANIFEST, and the test that reads the clip skips naming it.
if ffmpeg -hide_banner -h filter=setparams 2>/dev/null | grep -q chroma_location; then
    siting_on_frame=1
else
    siting_on_frame=0
    echo "testmedia.sh: this ffmpeg's setparams has no chroma_location, so the chroma siting goes on the encoder." >&2
fi

# The setparams text that stamps chroma siting $1 on the frame, or nothing where the encoder does it.
frame_siting() {
    if [ "$siting_on_frame" = "1" ]; then printf ':chroma_location=%s' "$1"; fi
}

# The encoder options that stamp chroma siting $1, or none where the frame carries it. Read into an
# array with read -a, so an empty answer adds no argument.
encoder_siting() {
    if [ "$siting_on_frame" = "0" ]; then printf -- '-chroma_sample_location %s' "$1"; fi
}

# Fails unless the first video stream of $1 reads back with chroma siting $2.
expect_siting() {
    command -v ffprobe >/dev/null 2>&1 || return 0
    local actual
    actual="$(ffprobe -v error -select_streams v:0 -show_entries stream=chroma_location -of csv=p=0 "$1")"
    if [ "$actual" != "$2" ]; then
        echo "testmedia.sh: $1 reads back with chroma siting '$actual', not '$2'. This ffmpeg takes the siting neither from setparams nor from the encoder." >&2
        exit 1
    fi
}

# Fixtures this ffmpeg cannot make, one "path: reason" line each, which the MANIFEST lists.
skipped_fixtures=()

skip_fixture() {
    echo "SKIPPED $1: $2" >&2
    skipped_fixtures+=("$1: $2")
}

cd "$(dirname "$0")/.."
mkdir -p testmedia
cd testmedia

echo "1080p30 h264 + aac, 10s, keyframe every 30 frames"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=10" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=10" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 30 \
  -c:a aac -b:a 128k -shortest sync1080p30.mp4

echo "Sparse keyframes, five seconds apart, for bounded backward seeking"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=160x90:rate=10:duration=12" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 sparse-keyframes.mp4

echo "720p variable frame rate h264 + aac, 8s, five frame durations in a repeating cycle"
# Genuinely variable, not a fractional constant rate: no two neighbouring frames last the same
# time. settb pins the timebase at 1/90000, then setpts rewrites every presentation timestamp
# onto a five frame cycle of 1/60, 1/30, 1/20, 1/40 and 1/24 of a second, which is 1500, 3000,
# 4500, 2250 and 3750 ticks and 15000 ticks per cycle. The cycle averages exactly 30 fps, so the
# 240 source frames still cover 8 seconds. passthrough stops ffmpeg from putting the timestamps
# back on a constant grid, and pinning the encoder and track timebases to 1/90000 keeps every
# tick above exact instead of rounded.
vfr_pts="floor(N/5)*15000"
vfr_pts="$vfr_pts + if(eq(mod(N,5),0), 0, if(eq(mod(N,5),1), 1500, \
  if(eq(mod(N,5),2), 4500, if(eq(mod(N,5),3), 9000, 11250))))"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=8" \
  -f lavfi -i "sine=frequency=330:sample_rate=44100:duration=8" \
  -vf "settb=1/90000,setpts='$vfr_pts'" \
  -fps_mode:v passthrough -enc_time_base:v 1/90000 -video_track_timescale 90000 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac truevfr720.mp4

echo "MPEG-TS remux of the 1080p clip, timestamps pushed 1400 seconds into the future"
# The relative timeline fixture. An MPEG-TS capture never starts at zero: the muxer already begins
# at 1.4s, and -output_ts_offset adds another 1400 seconds on top, so the container start is about
# 1401.4s. A player that fails to normalise the origin exactly once reports a first frame 23 minutes
# in. -c copy keeps the pictures and the packet durations identical to sync1080p30.mp4, which is what
# makes the two comparable: a duration must come out the same in both, because an interval has no
# origin to subtract.
ffmpeg -v error -y -i sync1080p30.mp4 -c copy \
  -output_ts_offset 1400 -f mpegts tsoffset1400.ts

echo "4K HEVC Main10, 6s, no audio, for hardware decode"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=3840x2160:rate=30:duration=6" \
  -c:v libx265 -preset ultrafast -pix_fmt yuv420p10le -tag:v hvc1 hevc4k10.mp4

echo "Matroska with an ASS subtitle track"
printf '1\n00:00:00,500 --> 00:00:03,000\nHello from KitePlayer\n\n2\n00:00:03,500 --> 00:00:06,000\nSecond cue with <i>italics</i>\n\n' > subs.srt
ffmpeg -v error -y -i sync1080p30.mp4 -i subs.srt \
  -c:v copy -c:a copy -c:s ass -map 0:v -map 0:a -map 1:0 subbed.mkv

echo "Small clips plus reference RGBA dumps, for renderer correctness"
# Small so a per-pixel comparison is fast, and colourful so a wrong matrix is unmissable.
for space in bt709 bt601; do
  if [ "$space" = "bt709" ]; then csp=bt709; trc=bt709; prm=bt709; else csp=smpte170m; trc=smpte170m; prm=smpte170m; fi
  ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" \
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 1 \
    -colorspace $csp -color_trc $trc -color_primaries $prm -color_range tv \
    "colors-$space.mp4"
  # The reference the renderer is checked against. Nearest-neighbour chroma upsampling, because
  # that is what tier 0 does and what a nearest-filtered GPU texture does.
  ffmpeg -v error -y -i "colors-$space.mp4" -frames:v 1 \
    -vf "format=rgba" -sws_flags neighbor -f rawvideo "colors-$space.rgba"
done

echo "Raw H.264 elementary stream with no timestamps at all"
# An Annex B stream carries no container timestamps, so every packet and every decoded frame arrives
# with none and the player has to synthesise them from the previous one. The clip is one second of
# 25 fps, which the decoder reports as a 40ms duration per frame, so the synthesised timeline is
# checkable to the microsecond.
ffmpeg -v error -y -i colors-bt709.mp4 -c copy -bsf:v h264_mp4toannexb -f h264 novts.h264

echo "10-bit clip, to check the high bits are the ones kept"
ffmpeg -v error -y -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p10le -g 1 colors-10bit.mp4
ffmpeg -v error -y -i colors-10bit.mp4 -frames:v 1 \
  -vf "format=rgba" -sws_flags neighbor -f rawvideo colors-10bit.rgba

read -r -a left_on_encoder <<< "$(encoder_siting left)"
read -r -a center_on_encoder <<< "$(encoder_siting center)"

echo "SMPTE 240M tagged clip plus its reference dump"
# SMPTE 240M has its own inverse matrix and is not BT.601 with a different name. Converting this
# clip with the BT.601 numbers gives a mean component error of 7.7 against the reference below,
# where the correct row gives 0.18, so the golden test separates the two.
# The colour metadata is stamped by setparams rather than by -colorspace, because ffmpeg copies the
# filtered frame's own properties onto the encoder and the frame is where the values have to be.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" \
  -vf "setparams=colorspace=smpte240m:color_trc=smpte240m:color_primaries=smpte240m:range=tv$(frame_siting left)" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 1 ${left_on_encoder[@]+"${left_on_encoder[@]}"} \
  colors-smpte240m.mp4
expect_siting colors-smpte240m.mp4 left
ffmpeg -v error -y -i colors-smpte240m.mp4 -frames:v 1 \
  -vf "format=rgba" -sws_flags neighbor -f rawvideo colors-smpte240m.rgba

echo "Centre-sited NV12 clip plus its reference dump"
# NV12 is only ever produced by a raw video track or by a hardware decoder, so the clip stores raw
# NV12: no software codec in FFmpeg outputs a semi-planar format. Matroska is the container that
# keeps both the pixel format and the colour metadata, chroma siting included.
# The pattern is a dense sine field rather than testsrc2 because a half sample chroma error has to
# be unmissable: with it, applying a chroma shift moves the mean error from 0.31 to 8.12.
# Two frames is enough for a first-frame golden and keeps an uncompressed clip small.
nv12_geq="geq=r='128+120*sin(X/5)':g='128+120*sin(X/8+Y/7)':b='128+120*sin(X/11+Y/13+4)'"
nv12_tag="setparams=colorspace=bt709:color_trc=bt709:color_primaries=bt709:range=tv$(frame_siting center)"
ffmpeg -v error -y \
  -f lavfi -i "color=c=black:size=320x240:rate=25:duration=1" -frames:v 2 \
  -vf "$nv12_geq,format=nv12,$nv12_tag" \
  -c:v rawvideo ${center_on_encoder[@]+"${center_on_encoder[@]}"} colors-nv12.mkv
expect_siting colors-nv12.mkv center
# The reference goes through yuv420p on the way to RGBA. That step is a plane deinterleave and
# nothing else, so it changes no sample value, and it avoids one difference between two swscale
# paths: converting NV12 straight to RGB reads chroma row (row+1)/2 for a luma row, while the planar
# path reads row/2. Measured, the two disagree by a mean of 0.98 on a clip with vertical colour
# detail. The planar rule is the one nearest neighbour gives, so it is the one to compare against.
ffmpeg -v error -y -i colors-nv12.mkv -frames:v 1 \
  -vf "format=yuv420p,format=rgba" -sws_flags neighbor -f rawvideo colors-nv12.rgba

echo "P010 source clip plus its high-aligned reference dump"
# P010 keeps its ten bits in the HIGH bits of each 16 bit word, where yuv420p10le keeps them in the
# low ten. Reading one as the other is not a rounding difference: the mean component error against
# the reference below is 97 the wrong way round and 0.58 the right way.
# No container can hold raw P010: FFmpeg has no P010 entry in its raw pixel format tag table, so
# every container writes a tag that reads back as something else (mkv, nut, avi and mov all answered
# rgb555le, measured), and no software decoder outputs the format either. So the clip on disk is
# tagged 10 bit planar and the test lifts the decoded frame to P010 with a one filter graph. The
# reference below goes through the same P010 intermediate, which is where the high alignment is
# interpreted, so it is a reference for P010 and not for the planar source.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" \
  -vf "setparams=colorspace=bt709:color_trc=bt709:color_primaries=bt709:range=tv$(frame_siting left)" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p10le -g 1 ${left_on_encoder[@]+"${left_on_encoder[@]}"} \
  colors-p010.mp4
expect_siting colors-p010.mp4 left
ffmpeg -v error -y -i colors-p010.mp4 -frames:v 1 \
  -vf "format=p010le,format=rgba" -sws_flags neighbor -f rawvideo colors-p010.rgba

echo "PQ tagged HDR clip and a BT.2020 constant luminance clip"
# Neither has a reference dump. Both exist so the converter's one-time warning can be counted: there
# is no tone mapping and no constant luminance path, and both clips are converted approximately.
pq_tag="setparams=colorspace=bt2020nc:color_trc=smpte2084:color_primaries=bt2020:range=tv$(frame_siting left)"
cl_tag="setparams=colorspace=bt2020c:color_trc=bt2020-10:color_primaries=bt2020:range=tv$(frame_siting left)"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 5 \
  -vf "format=yuv420p10le,$pq_tag" ${left_on_encoder[@]+"${left_on_encoder[@]}"} \
  -c:v libx265 -preset ultrafast -x265-params log-level=error -tag:v hvc1 -g 1 colors-pq.mp4
expect_siting colors-pq.mp4 left

echo "black and white strobe at 5 Hz, three pictures each, for the flash guard"
ffmpeg -v error -y \
  -f lavfi -i "color=c=black:size=640x360:rate=30:duration=4" \
  -vf "geq=lum='if(lt(mod(N,6),3),16,235)':cb=128:cr=128,format=yuv420p" \
  -c:v libx264 -preset ultrafast -g 30 -bf 0 strobe-5hz.mp4
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 5 \
  -vf "format=yuv420p10le,$cl_tag" ${left_on_encoder[@]+"${left_on_encoder[@]}"} \
  -c:v libx265 -preset ultrafast -x265-params log-level=error -tag:v hvc1 -g 1 colors-bt2020cl.mp4
expect_siting colors-bt2020cl.mp4 left

echo "ICtCp tagged clip"
# No reference dump either. ICtCp needs the PQ curve inside its inverse, so the converters approximate
# it and the source warns once, and this clip exists so that warning can be counted.
ictcp_tag="setparams=colorspace=ictcp:color_trc=smpte2084:color_primaries=bt2020:range=tv$(frame_siting left)"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 5 \
  -vf "format=yuv420p10le,$ictcp_tag" ${left_on_encoder[@]+"${left_on_encoder[@]}"} \
  -c:v libx265 -preset ultrafast -x265-params log-level=error -tag:v hvc1 -g 1 colors-ictcp.mp4
expect_siting colors-ictcp.mp4 left

echo "Identity (GBR) clip plus its reference dump"
# Identity means the three planes hold G, B and R in the slots where YCbCr keeps Y, Cb and Cr. The
# H.264, HEVC and VP9 decoders turn such a stream into planar GBR, which the engine does not model, so
# this clip stores the planes losslessly as 4:4:4 and tags them. dav1d also hands over 4:4:4 tagged
# Identity, for AV1 whose primaries and transfer are not BT.709 and sRGB.
# The planes are a planar GBR picture relabelled as 4:4:4 with no conversion: the raw bytes are
# written once and read back under the other name. So the reference is the original picture. The
# range and matrix are codec options on both sides, because setparams alone let the encoder's range
# negotiation squeeze the planes to studio range, measured.
ffmpeg -v error -y -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 2 \
  -vf "format=gbrp" -f rawvideo colors-gbr.planes
ffmpeg -v error -y -f rawvideo -pixel_format yuv444p -video_size 320x240 -framerate 25 \
  -color_range pc -colorspace rgb -i colors-gbr.planes \
  -c:v ffv1 -color_range pc -colorspace rgb colors-gbr.mkv
rm colors-gbr.planes
ffmpeg -v error -y -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 1 \
  -vf "format=rgba" -f rawvideo colors-gbr.rgba

echo "RGB-coded H.264 clip, which decodes to planar GBR"
# What a lossless screen capture looks like: H.264 coded as RGB, which the decoder hands over as
# gbrp. Lossless, so its first frame is the same picture as colors-gbr.rgba. The pattern is drawn in
# bgr0: drawn in rgb24 or bgr24 it differs from that reference before the encoder sees it, measured.
ffmpeg -v error -y -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 2 \
  -vf "format=bgr0" -c:v libx264rgb -qp 0 colors-rgb-h264.mp4

echo "Rotated clip, for the renderer's quarter turn"
# What a phone writes: the pixels are stored landscape and a display matrix in the container tells the
# player to turn them. -display_rotation is an INPUT option and its own unit is counter-clockwise
# degrees, so 90 here is the matrix for a quarter turn counter-clockwise, which is 270 clockwise, and
# that is the number KiteFFmpeg reports because it reports clockwise. Either quarter turn swaps the
# output width and height, which is what the test checks.
# It has to be a two step recipe. Applied to a decoded input with autorotation on, ffmpeg turns the
# PIXELS at the filter stage and writes no matrix at all, which is the opposite of the fixture wanted.
# Remuxing an already encoded clip with -c copy leaves the pictures alone and writes the matrix, so the
# stored frames stay 320x240 and only the metadata says otherwise.
ffmpeg -v error -y -display_rotation:v 90 -i colors-bt709.mp4 -c copy rotated90ccw.mp4

echo "Mirrored clips, for the renderer's mirror"
# The same remux with a mirror in the matrix. A left-right mirror reads as a mirror and no turn. An
# upside-down mirror reads as a mirror and a half turn, because the renderer mirrors first and turns
# after.
ffmpeg -v error -y -display_hflip -i colors-bt709.mp4 -c copy mirrored.mp4
ffmpeg -v error -y -display_vflip -i colors-bt709.mp4 -c copy mirrored-vflip.mp4

echo "5.1 clips plus their stereo reference PCM"
# Six channels, one tone each, so a downmix that routes a channel to the wrong speaker is audible in
# the numbers rather than only in a listening test. aevalsrc is used instead of the sine source
# because it takes an amplitude: 0.35 per channel keeps the worst case sum, one front plus centre
# and one surround at -3 dB, at 0.85 and so below clipping at every stage.
# The LFE is silent on purpose. FFmpeg's own stereo downmix drops the LFE, the engine's mixer sends
# it at -3 dB like the centre, and a silent channel is the one value both agree on. The LFE
# coefficient itself is covered by the mixer's own matrix tests.
six="aevalsrc=0.35*sin(2*PI*200*t):sample_rate=48000:duration=3[fl];"
six="$six aevalsrc=0.35*sin(2*PI*300*t):sample_rate=48000:duration=3[fr];"
six="$six aevalsrc=0.35*sin(2*PI*400*t):sample_rate=48000:duration=3[fc];"
six="$six aevalsrc=0:sample_rate=48000:duration=3[lfe];"
six="$six aevalsrc=0.35*sin(2*PI*700*t):sample_rate=48000:duration=3[sl];"
six="$six aevalsrc=0.35*sin(2*PI*900*t):sample_rate=48000:duration=3[sr]"
# 5.1 in FFmpeg's naming is the back variant, FL FR FC LFE BL BR, which is also AAC's channel
# configuration 6. AAC cannot deliver the side variant: the encoder writes a program config element
# for it and FFmpeg's decoder still reports back channels, measured, so the side layout gets a WAV,
# whose extensible header carries the exact channel mask.
ffmpeg -v error -y -filter_complex "$six;[fl][fr][fc][lfe][sl][sr]join=inputs=6:channel_layout=5.1[a]" \
  -map "[a]" -c:a aac -b:a 384k surround51.mp4
ffmpeg -v error -y -filter_complex "$six;[fl][fr][fc][lfe][sl][sr]join=inputs=6:channel_layout=5.1(side)[a]" \
  -map "[a]" -c:a pcm_f32le surround51side.wav
# The oracle for the whole audio pipeline: FFmpeg's own downmix of the same decoded file. Float
# output means swresample does not normalise the matrix, so these samples are exactly
# FL + 0.7071*FC + 0.7071*SL and the mirror of it on the right. Cut at the content's three seconds:
# FFmpeg 9 trims the padding of the last AAC frame and an older ffmpeg keeps it, 384 frames here.
ffmpeg -v error -y -i surround51.mp4 -ac 2 -t 3 -f f32le surround51-stereo.f32le
ffmpeg -v error -y -i surround51side.wav -ac 2 -f f32le surround51side-stereo.f32le

echo "5.1 carrying content ONLY in the LFE, to settle the downmix's LFE policy"
# The clips above keep their LFE silent on purpose, because the engine used to fold the LFE into
# the stereo mix at -3 dB while FFmpeg's own downmix drops it, and a silent channel was the one
# value both agreed on. That dodged the question instead of answering it. The
# engine now follows FFmpeg, so the answer can be MEASURED: this clip puts a 60 Hz tone in the
# LFE and silence everywhere else, which makes the reference either silence (the LFE is dropped)
# or that tone at -3 dB (it is folded in), with nothing in between to argue about.
lfeonly="aevalsrc=0:sample_rate=48000:duration=2[fl];"
lfeonly="$lfeonly aevalsrc=0:sample_rate=48000:duration=2[fr];"
lfeonly="$lfeonly aevalsrc=0:sample_rate=48000:duration=2[fc];"
lfeonly="$lfeonly aevalsrc=0.5*sin(2*PI*60*t):sample_rate=48000:duration=2[lfe];"
lfeonly="$lfeonly aevalsrc=0:sample_rate=48000:duration=2[bl];"
lfeonly="$lfeonly aevalsrc=0:sample_rate=48000:duration=2[br]"
ffmpeg -v error -y -filter_complex "$lfeonly;[fl][fr][fc][lfe][bl][br]join=inputs=6:channel_layout=5.1[a]" \
  -map "[a]" -c:a pcm_f32le surround51lfe.wav
ffmpeg -v error -y -i surround51lfe.wav -ac 2 -f f32le surround51lfe-stereo.f32le

echo "30 minute 360p clip for leak and drift soak tests"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=1800" \
  -f lavfi -i "sine=frequency=200:sample_rate=48000:duration=1800" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest soak30min.mp4

echo "MP4 with an MP4 text subtitle track, which a Matroska recording cannot hold"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=30:duration=4" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=4" \
  -i subs.srt \
  -map 0:v -map 1:a -map 2:0 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 96k -c:s mov_text \
  movtext.mp4

echo "MP4 timed text with styled runs: italic, bold, underline and a colour, the last after non-Latin text"
# FFmpeg's encoder writes each SubRip style as a run in the sample's styl box (#512).
printf '1\n00:00:00,500 --> 00:00:01,500\n<i>Off screen, a voice</i>\n\n2\n00:00:01,500 --> 00:00:02,500\nA plain line with one <b>bold</b> word\n\n3\n00:00:02,500 --> 00:00:03,500\n日本語 and a <font color="#ff0000">red</font> <u>word</u>\n\n' > movtext-styled.srt
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=30:duration=4" \
  -i movtext-styled.srt \
  -map 0:v -map 1:0 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:s mov_text \
  movtext-styled.mp4
rm -f movtext-styled.srt

# ---------------------------------------------------------------------------------------------
# The format conformance matrix. Every clip below is a matrix row; the
# table itself is FormatMatrix.kt in kiteplayer-ffmpeg. Small and short on purpose: the matrix
# proves formats open, decode, seek and close, not that they look good for minutes.
# ---------------------------------------------------------------------------------------------

echo "Matroska multi-track: one video, two audio languages, two SubRip tracks"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=6" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=6" \
  -i subs.srt -i subs.srt \
  -map 0:v -map 1:a -map 2:a -map 3:0 -map 4:0 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 96k -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn \
  -metadata:s:s:0 language=eng -metadata:s:s:1 language=jpn \
  multitrack.mkv

echo "Matroska baseline, ordered-chapters-free: plain h264 + aac"
ffmpeg -v error -y -i sync1080p30.mp4 -c copy -t 6 baseline.mkv

echo "VP9 + Opus WebM"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=6" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
  -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 500k \
  -c:a libopus -b:a 96k vp9.webm

echo "AV1 + Opus Matroska. The DEVICE verdict for this row is measured, not assumed:"
echo "the phone FFmpeg profile enables the av1 decoder but vendors no software AV1 codec."
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=4" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=4" \
  -c:v libsvtav1 -preset 12 -b:v 500k \
  -c:a libopus -b:a 96k av1.mkv

echo "MPEG-4 part 2 + AAC"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=6" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
  -c:v mpeg4 -qscale:v 5 -c:a aac -b:a 96k mpeg4part2.mp4

echo "Audio-only files: AAC in M4A, MP3, FLAC"
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
  -c:a aac -b:a 128k audio-aac.m4a
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=6" \
  -c:a libmp3lame -b:a 128k audio-mp3.mp3
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
  -c:a flac audio-flac.flac

echo "Songs whose tags carry lyrics: LRC lines in an ID3 USLT frame, and plain words in a FLAC comment"
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=6" \
  -metadata "lyrics-eng=$(printf '[ar:KitePlayer]\n[00:00.50]First line\n[00:02.00]Second line\n[00:04.00]Third line')" \
  -c:a libmp3lame -b:a 128k audio-lyrics.mp3
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
  -metadata "LYRICS=$(printf 'A plain first line\nA plain second line')" \
  -c:a flac audio-lyrics.flac

echo "A song with its album cover, a JPEG of 600 by 600 attached to an MP3 as ID3 tags hold it"
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=4" \
  -f lavfi -i "testsrc2=size=600x600:rate=1:duration=1" -map 0:a -map 1:v -frames:v 1 \
  -c:a libmp3lame -b:a 128k -c:v mjpeg -disposition:v attached_pic -id3v2_version 3 \
  -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" audio-cover.mp3

echo "A chained Ogg, two songs one after the other as a station plays them, each with its own comments"
# Some runner builds omit libvorbis. Keep this fixture on those builds with the built-in encoder,
# which requires experimental opt-in and stereo input. Read the whole encoder list under pipefail.
vorbis_options=(-c:a libvorbis)
if ! ffmpeg -hide_banner -encoders 2>/dev/null | awk '$2 == "libvorbis" { found = 1 } END { exit !found }'; then
    vorbis_options=(-c:a vorbis -strict -2 -ac 2)
fi
ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=3" \
  -metadata title="First Song" -metadata artist="The Band" "${vorbis_options[@]}" chain-1.ogg
ffmpeg -v error -y -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=3" \
  -metadata title="Second Song" -metadata artist="The Band" "${vorbis_options[@]}" chain-2.ogg
cat chain-1.ogg chain-2.ogg > audio-chained.ogg
rm -f chain-1.ogg chain-2.ogg

echo "Torture cases, from bytes rather than encoders, deterministic"
# The first 40% of the sync clip. The default mp4 layout writes moov after mdat, so this
# amputates the index entirely; a player must refuse it with a typed error or survive whatever
# partial view the demuxer offers, and must never crash or hang.
sync_bytes=$(wc -c < sync1080p30.mp4)
head -c $((sync_bytes * 2 / 5)) sync1080p30.mp4 > torture-truncated.mp4
# Not media at all: a repeating deterministic pattern behind a media extension.
python3 -c "import sys; sys.stdout.buffer.write(bytes(range(256)) * 4096)" > torture-garbage.mp4

ls -la

echo "Chaptered Matroska with exact millisecond bounds, for the chapter round-trip row"
cat > chapters.ffmeta <<'META'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=2000
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=2000
END=5000
title=Middle
[CHAPTER]
TIMEBASE=1/1000
START=5000
END=9000
title=Ending
META
ffmpeg -v error -y -i sync1080p30.mp4 -i chapters.ffmeta -map_metadata 1 -map 0 -c copy -t 9 chapters.mkv
rm -f chapters.ffmeta

# The wide-profile rows. Every clip below is a format the NARROW profile
# could not open or could not decode; each one ran RED against the narrow trees before the wide
# trees existed, which is the whole evidentiary point. All are synthesizable with a stock
# ffmpeg CLI; VC-1 and RealVideo have no FFmpeg encoders, so those two wait for real sample
# files and are a named absence rather than rows here.

echo "AVI with MPEG-4 part 2 video and MP3 audio, the classic downloaded file of 2005"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=5" \
  -f lavfi -i "sine=frequency=300:sample_rate=44100:duration=5" \
  -c:v mpeg4 -q:v 5 -c:a libmp3lame -b:a 128k -shortest avi-mpeg4.avi

echo "WMV: ASF container, MS-MPEG4v3 video, WMA2 audio"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=5" \
  -f lavfi -i "sine=frequency=350:sample_rate=44100:duration=5" \
  -c:v msmpeg4 -q:v 5 -c:a wmav2 -b:a 96k -shortest wmv-msmpeg4.wmv

echo "FLV: Sorenson Spark video with MP3 audio, the web video of 2008"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=5" \
  -f lavfi -i "sine=frequency=400:sample_rate=44100:duration=5" \
  -c:v flv -q:v 5 -c:a libmp3lame -ar 44100 -b:a 96k -shortest flv-flv1.flv

echo "VOB: MPEG-PS container, MPEG-2 video, AC-3 audio, the DVD shape"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=720x576:rate=25:duration=5" \
  -f lavfi -i "sine=frequency=250:sample_rate=48000:duration=5" \
  -c:v mpeg2video -b:v 3M -c:a ac3 -b:a 192k -shortest -f vob vob-mpeg2.vob

echo "E-AC-3 audio in Matroska, the broadcast and anime-release codec the narrow set lacked"
ffmpeg -v error -y \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=5" \
  -c:a eac3 -b:a 192k audio-eac3.mkv

echo "DTS core audio in Matroska (dca encoder is experimental but decode-representative)"
ffmpeg -v error -y \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=5" \
  -c:a dca -strict experimental -b:a 768k audio-dts.mkv

echo "TrueHD audio in Matroska (experimental encoder, decode-representative)"
ffmpeg -v error -y \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=5" \
  -c:a truehd -strict experimental audio-truehd.mkv

echo "ALAC in m4a, the Apple lossless shape"
ffmpeg -v error -y \
  -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=5" \
  -c:a alac audio-alac.m4a

echo "ASS subtitle track in Matroska; the row asserts the stream is SEEN (cue decode is S4's)"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=6" \
  -i subs.srt \
  -map 0:v -map 1 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:s ass asssubbed.mkv

# typeset.mkv: a genuinely typeset ASS track (a moving sign, an animated transform, a karaoke
# line) beside a font ATTACHED to the container under the family the styles name. This is what the
# libass module's end-to-end tests play: the engine must route the track to the typesetter, load the
# attachment as a font, and re-render as the sign moves. The font is whichever sans-serif TrueType
# file this host has; the attachment name is what libass matches, so the style resolves either way.
cat > typeset.ass <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,KiteTestSans,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
Style: Sign,KiteTestSans,48,&H0000FF00,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,3,0,8,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:07.00,Default,,0,0,0,,Static line at the bottom
Dialogue: 1,0:00:01.00,0:00:06.00,Sign,,0,0,0,,{\move(40,40,560,40)}Moving sign
Dialogue: 2,0:00:02.00,0:00:06.00,Sign,,0,0,0,,{\pos(320,180)\t(\frz360)\t(\c&HFF0000&)}Turning and tinting
Dialogue: 3,0:00:03.00,0:00:07.00,Default,,0,0,0,,{\k100}Kara{\k100}oke {\k100}fill {\k100}line
ASS
TYPESET_FONT=""
for candidate in \
  "/System/Library/Fonts/Supplemental/Arial.ttf" \
  "/System/Library/Fonts/Supplemental/Verdana.ttf" \
  "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf" \
  "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf" \
  "/usr/share/fonts/dejavu/DejaVuSans.ttf"; do
  if [ -f "$candidate" ]; then TYPESET_FONT="$candidate"; break; fi
done
if [ -n "$TYPESET_FONT" ]; then
  ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=640x360:rate=25:duration=8" \
    -i typeset.ass \
    -attach "$TYPESET_FONT" -metadata:s:t:0 mimetype=font/ttf -metadata:s:t:0 filename=KiteTestSans.ttf \
    -map 0:v -map 1 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:s ass typeset.mkv
else
  echo "testmedia.sh: no sans-serif TrueType font found on this host; typeset.mkv has no attachment" >&2
  ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=640x360:rate=25:duration=8" \
    -i typeset.ass \
    -map 0:v -map 1 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:s ass typeset.mkv
fi

echo "HEVC Main10 PQ, 1s, with HDR10 static metadata: a 4000 nit master and MaxCLL 4000, MaxFALL 400"
# The mastering display is in x265's units: primaries in 0.00002, luminance in 0.0001 nits.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=1" \
  -vf "format=yuv420p10le" -c:v libx265 -preset ultrafast -tag:v hvc1 \
  -x265-params "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited:hdr10=1:master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(40000000,50):max-cll=4000,400" \
  hdr10-meta.mp4

echo "HLS streams in hls/: two variants, a separate audio rendition, AES-128, fMP4 byte ranges, and an fMP4 ladder"
# Read by the HLS tests through a reader that maps addresses to these files, so every playlist
# names its segments relatively, as a server's would. Two second segments with a keyframe at each.
mkdir -p hls
hls_video=(-c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 30 -keyint_min 30 -sc_threshold 0)
hls_vod=(-f hls -hls_time 2 -hls_playlist_type vod -hls_list_size 0)
# A master playlist with a 320x180 and a 640x360 variant, each with its own muxed sound.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=12" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=12" \
  -filter_complex "[0:v]split=2[hi][lo0];[lo0]scale=320:180[lo]" \
  -map "[lo]" -map 1:a -map "[hi]" -map 1:a \
  "${hls_video[@]}" -b:v:0 300k -b:v:1 900k -c:a aac -b:a 96k \
  "${hls_vod[@]}" -hls_segment_filename "hls/ts-%v-%d.ts" -master_pl_name ts.m3u8 \
  -var_stream_map "v:0,a:0 v:1,a:1" "hls/ts-%v.m3u8"
# A master playlist whose one variant has no sound, and whose sound is an EXT-X-MEDIA rendition.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=12" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=12" \
  -map 0:v -map 1:a "${hls_video[@]}" -b:v 300k -c:a aac -b:a 96k \
  "${hls_vod[@]}" -hls_segment_filename "hls/alt-%v-%d.ts" -master_pl_name alt.m3u8 \
  -var_stream_map "v:0,agroup:sound a:0,agroup:sound,language:en,default:yes" "hls/alt-%v.m3u8"
# A media playlist of AES-128 segments. The key info names the key's address in the playlist,
# the key file, and a fixed IV.
printf '0123456789abcdef' > hls/aes.key
printf 'aes.key\nhls/aes.key\n000102030405060708090a0b0c0d0e0f\n' > hls-aes.keyinfo
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=8" \
  -f lavfi -i "sine=frequency=550:sample_rate=48000:duration=8" \
  "${hls_video[@]}" -b:v 300k -c:a aac -b:a 96k \
  "${hls_vod[@]}" -hls_key_info_file hls-aes.keyinfo -hls_segment_filename "hls/aes-%d.ts" hls/aes.m3u8
rm -f hls-aes.keyinfo
# A media playlist of fMP4 fragments in one file, each named by a byte range, after an init range.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=12" \
  -f lavfi -i "sine=frequency=770:sample_rate=48000:duration=12" \
  "${hls_video[@]}" -b:v 300k -c:a aac -b:a 96k \
  "${hls_vod[@]}" -hls_segment_type fmp4 -hls_flags single_file hls/fmp4.m3u8
# A master playlist of three fMP4 variants, 640x360, 1280x720 and 1920x1080, each with its own
# muxed sound, for a variant change with no new open (#464). The smallest has no B-frames and the
# other two have, so their edit lists and their decode times differ, as a real ladder's do.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=12" \
  -f lavfi -i "sine=frequency=880:sample_rate=48000:duration=12" \
  -filter_complex "[0:v]split=3[hi][mid0][lo0];[mid0]scale=1280:720[mid];[lo0]scale=640:360[lo]" \
  -map "[lo]" -map 1:a -map "[mid]" -map 1:a -map "[hi]" -map 1:a \
  "${hls_video[@]}" -b:v:0 300k -b:v:1 900k -b:v:2 2000k -bf:v:0 0 -bf:v:1 2 -bf:v:2 2 \
  -c:a aac -b:a:0 64k -b:a:1 96k -b:a:2 96k \
  "${hls_vod[@]}" -hls_segment_type fmp4 -hls_fmp4_init_filename "ladder-%v-init.mp4" \
  -hls_segment_filename "hls/ladder-%v-%d.m4s" -master_pl_name ladder.m3u8 \
  -var_stream_map "v:0,a:0 v:1,a:1 v:2,a:2" "hls/ladder-%v.m3u8"
# The same master in AV1 and in VP9, 256x144, 640x360 and 1280x720. A key frame of either codec
# states its own picture size, so a variant change needs nothing added to the samples (#564).
ladder_av1=(-c:v libsvtav1 -preset 12 -svtav1-params "keyint=30:scd=0")
ladder_vp9=(-c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -g 30 -keyint_min 30)
for codec in av1 vp9; do
  if [ "$codec" = "av1" ]; then ladder_codec=("${ladder_av1[@]}"); else ladder_codec=("${ladder_vp9[@]}"); fi
  ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=12" \
    -f lavfi -i "sine=frequency=880:sample_rate=48000:duration=12" \
    -filter_complex "[0:v]split=3[hi][mid0][lo0];[mid0]scale=640:360[mid];[lo0]scale=256:144[lo]" \
    -map "[lo]" -map 1:a -map "[mid]" -map 1:a -map "[hi]" -map 1:a \
    "${ladder_codec[@]}" -pix_fmt yuv420p -b:v:0 200k -b:v:1 500k -b:v:2 1200k -c:a aac -b:a 96k \
    "${hls_vod[@]}" -hls_segment_type fmp4 -hls_fmp4_init_filename "ladder-$codec-%v-init.mp4" \
    -hls_segment_filename "hls/ladder-$codec-%v-%d.m4s" -master_pl_name "ladder-$codec.m3u8" \
    -var_stream_map "v:0,a:0 v:1,a:1 v:2,a:2" "hls/ladder-$codec-%v.m3u8"
done

echo "DASH presentations in dash/: separate video and audio sets, one numbered set, and indexed single files"
# Read by the DASH tests, which play them through the HLS path (#295). Seventy seconds each, so a
# seek to 60 s lands well inside, in two second fMP4 segments with a keyframe at each.
mkdir -p dash
dash_video=(-c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0)
# Two video representations and the sound, in sets of their own, addressed by timelines.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=70" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=70" \
  -filter_complex "[0:v]split=2[hi][lo0];[lo0]scale=320:180[lo]" \
  -map "[lo]" -map "[hi]" -map 1:a \
  "${dash_video[@]}" -b:v:0 300k -b:v:1 900k -c:a aac -b:a 96k \
  -f dash -seg_duration 2 -use_template 1 -use_timeline 1 -adaptation_sets "id=0,streams=v id=1,streams=a" \
  -init_seg_name 'separate-$RepresentationID$-init.m4s' \
  -media_seg_name 'separate-$RepresentationID$-$Number%05d$.m4s' dash/separate.mpd
# One set whose segments are numbered at a fixed length, which the live test also serves as live.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=70" \
  "${dash_video[@]}" -b:v 300k \
  -f dash -seg_duration 2 -use_template 1 -use_timeline 0 \
  -init_seg_name 'single-$RepresentationID$-init.m4s' \
  -media_seg_name 'single-$RepresentationID$-$Number$.m4s' dash/single.mpd
# One file per set whose segment index (sidx) names its fragments, as an on-demand packager writes
# them. The test writes the manifest, with a SegmentBase for each.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=70" \
  "${dash_video[@]}" -b:v 300k -an \
  -movflags +frag_keyframe+empty_moov+default_base_moof+global_sidx -f mp4 dash/ondemand-video.mp4
ffmpeg -v error -y \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=70" \
  -c:a aac -b:a 96k -frag_duration 2000000 \
  -movflags +empty_moov+default_base_moof+global_sidx -f mp4 dash/ondemand-audio.mp4
# WebM, which the DASH tests also play through the HLS path (#401): VP9 and Opus in sets of their
# own in numbered two second segments, as a live packager writes them, and one file per set whose
# Cues name its clusters, with the manifest ffmpeg writes for those files.
dash_vp9=(-c:v libvpx-vp9 -deadline realtime -cpu-used 8 -row-mt 1 -pix_fmt yuv420p -g 60 -keyint_min 60)
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=70" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=70" \
  -map 0:v -map 1:a "${dash_vp9[@]}" -b:v 300k -c:a libopus -b:a 64k \
  -f dash -dash_segment_type webm -seg_duration 2 -use_template 1 -use_timeline 1 \
  -adaptation_sets "id=0,streams=v id=1,streams=a" \
  -init_seg_name 'webm-$RepresentationID$-init.webm' \
  -media_seg_name 'webm-$RepresentationID$-$Number%05d$.webm' dash/webm.mpd
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x180:rate=30:duration=70" \
  "${dash_vp9[@]}" -b:v 300k -an -f webm -dash 1 dash/ondemand-video.webm
ffmpeg -v error -y \
  -f lavfi -i "sine=frequency=660:sample_rate=48000:duration=70" \
  -c:a libopus -b:a 64k -vn -f webm -dash 1 -cluster_time_limit 2000 dash/ondemand-audio.webm
ffmpeg -v error -y \
  -f webm_dash_manifest -i dash/ondemand-video.webm -f webm_dash_manifest -i dash/ondemand-audio.webm \
  -c copy -map 0 -map 1 -f webm_dash_manifest -adaptation_sets "id=0,streams=0 id=1,streams=1" \
  dash/webm-ondemand.mpd
# Periods for the multi-period tests (#403), each twenty seconds of picture and sound in two second
# segments, whose manifests the tests write, and each with its own media time starting from zero,
# as ad insertion stitches them: ffmpeg's DASH muxer starts every media timeline at zero whatever
# offset it is given. In fMP4: a 320x180 Period, a 640x360 one, so its parameter sets differ, and a
# 320x180 one again. In WebM: a 320x180 Period and a 640x360 one. In MPEG-TS: two Periods.
for spec in "a|testsrc2=size=320x180|440" "b|smptebars=size=640x360|880" "c|testsrc=size=320x180|660"; do
  IFS='|' read -r name picture tone <<< "$spec"
  ffmpeg -v error -y \
    -f lavfi -i "$picture:rate=30:duration=20" -f lavfi -i "sine=frequency=$tone:sample_rate=48000:duration=20" \
    "${dash_video[@]}" -b:v 300k -c:a aac -b:a 96k \
    -f dash -seg_duration 2 -use_template 1 -use_timeline 0 -adaptation_sets "id=0,streams=v id=1,streams=a" \
    -init_seg_name "period-$name-\$RepresentationID\$-init.m4s" \
    -media_seg_name "period-$name-\$RepresentationID\$-\$Number\$.m4s" "dash/period-$name.mpd"
done
for spec in "a|testsrc2=size=320x180" "b|smptebars=size=640x360"; do
  IFS='|' read -r name picture <<< "$spec"
  ffmpeg -v error -y \
    -f lavfi -i "$picture:rate=30:duration=20" -f lavfi -i "sine=frequency=550:sample_rate=48000:duration=20" \
    -map 0:v -map 1:a "${dash_vp9[@]}" -b:v 300k -c:a libopus -b:a 64k \
    -f dash -dash_segment_type webm -seg_duration 2 -use_template 1 -use_timeline 0 \
    -adaptation_sets "id=0,streams=v id=1,streams=a" \
    -init_seg_name "webm-period-$name-\$RepresentationID\$-init.webm" \
    -media_seg_name "webm-period-$name-\$RepresentationID\$-\$Number\$.webm" "dash/webm-period-$name.mpd"
  ffmpeg -v error -y \
    -f lavfi -i "testsrc2=size=320x180:rate=30:duration=20" -f lavfi -i "sine=frequency=550:sample_rate=48000:duration=20" \
    "${dash_video[@]}" -b:v 300k -c:a aac -b:a 96k -muxdelay 0 -muxpreload 0 \
    -f segment -segment_time 2 -segment_format mpegts -segment_start_number 1 -reset_timestamps 0 \
    "dash/ts-period-$name-%d.ts"
done
# TTML in MP4 (stpp), one file whose segment index names it, which the DASH reader serves as WebVTT
# (#402): a cue every two seconds across the seventy, each shown for 900 ms.
for cue in $(seq 0 34); do
  printf '%d\n00:%02d:%02d,000 --> 00:%02d:%02d,900\nLigne %d\n\n' \
    $((cue + 1)) $((cue * 2 / 60)) $((cue * 2 % 60)) $((cue * 2 / 60)) $((cue * 2 % 60)) $((cue + 1))
done > dash/subs.srt
# ffmpeg 8 and earlier refuse to fragment TTML in MP4, so the clip is skipped there and the MANIFEST
# says so (#418). Any other failure of this step still stops the script.
stpp_log="$(mktemp)"
if ! ffmpeg -v error -y -i dash/subs.srt -c:s ttml -frag_duration 2000000 \
  -movflags +empty_moov+default_base_moof+global_sidx -f mp4 dash/subs-stpp.mp4 2> "$stpp_log"; then
    if grep -q "Fragmentation is not currently supported for TTML" "$stpp_log"; then
        rm -f dash/subs-stpp.mp4
        skip_fixture dash/subs-stpp.mp4 "ffmpeg $(ffmpeg_series) cannot write fragmented TTML in MP4; ffmpeg 9 can"
    else
        cat "$stpp_log" >&2
        rm -f "$stpp_log"
        exit 1
    fi
fi
rm -f "$stpp_log"

# ---------------------------------------------------------------------------------------------
# Provenance.
#
# These clips are gitignored and regenerated by whatever `ffmpeg` happens to be on PATH, and the
# encoder VERSION changes what comes out. That is not theory: on 2026-08-24 the subtitle-cue matrix
# row passed on this Mac against ffmpeg 8.0 and failed on a runner against 8.1.2, because the two
# muxers interleave the first cue differently. The test was wrong to depend on interleaving and was
# fixed, but the day was spent finding that out.
#
# The CHECKSUMS here still gate nothing, on purpose: a checksum gate would turn every legitimate
# ffmpeg upgrade into a red build. They record what made the clips so the next difference is a diff
# rather than an investigation. CI prints it; a reader compares two runs and sees the version move.
#
# The generator VERSION is a different matter and IS gated, at the top of this script, because it
# fires only when the toolchain genuinely moves rather than on every regeneration. That was the
# open half of the fixture pin.
echo "H.264 elementary stream, 320x240 at 30 fps with no B-frames, for captions written into it"
# The A/53 caption messages are written into it by the test that reads it, byte by byte, so every
# caption it expects is visible there (#236). No ffmpeg puts captions of its own making into a
# video stream: libx264 writes only the ones its input frames already carry.
ffmpeg -v error -y -f lavfi -i "testsrc2=size=320x240:rate=30:duration=3" \
  -c:v libx264 -preset ultrafast -bf 0 -g 30 -pix_fmt yuv420p -f h264 cc-base.h264

MANIFEST=MANIFEST.txt
{
    echo "# KitePlayer test fixtures. Generated by scripts/testmedia.sh."
    echo "# The generator SERIES below is RECORDED, not gated: a mismatch prints and continues."
    echo "# not verified, so that 'why did that row behave differently' has an answer that is a"
    echo "# diff rather than a guess."
    echo
    echo "generator: $(ffmpeg -version 2>/dev/null | head -1)"
    echo "host:      $(uname -sm)"
    echo
    # One line per fixture this ffmpeg could not make, which the test that reads it checks (#418).
    for skipped in ${skipped_fixtures[@]+"${skipped_fixtures[@]}"}; do
        echo "skipped:   $skipped"
    done
    echo
    printf "%-64s %12s  %s\n" "FILE" "BYTES" "SHA256"
    for f in $(ls | grep -v "^${MANIFEST}$" | sort); do
        [ -f "$f" ] || continue
        printf "%-64s %12s  %s\n" "$f" "$(wc -c < "$f" | tr -d ' ')" "$(shasum -a 256 "$f" | cut -d' ' -f1)"
    done
} > "$MANIFEST"

echo
# Counted, not offset from the header's line count: that was a magic "- 6" that silently went
# wrong the moment this header gained a line.
fixture_count=$(ls | grep -v "^${MANIFEST}$" | wc -l | tr -d ' ')
echo "wrote testmedia/$MANIFEST: $fixture_count fixtures, generator $(ffmpeg_series)"

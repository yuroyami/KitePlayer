#!/usr/bin/env bash
# Regenerate the test clips used by KitePlayer's playback tests.
# Needs the ffmpeg CLI on PATH, recent enough to know -fps_mode and -enc_time_base, which the
# variable frame rate clip below uses. Writes into testmedia/, which is gitignored.
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p testmedia
cd testmedia

echo "1080p30 h264 + aac, 10s, keyframe every 30 frames"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=10" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=10" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 30 \
  -c:a aac -b:a 128k -shortest sync1080p30.mp4

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

echo "SMPTE 240M tagged clip plus its reference dump"
# SMPTE 240M has its own inverse matrix and is not BT.601 with a different name. Converting this
# clip with the BT.601 numbers gives a mean component error of 7.7 against the reference below,
# where the correct row gives 0.18, so the golden test separates the two.
# The colour metadata is stamped by setparams rather than by -colorspace, because ffmpeg copies the
# filtered frame's own properties onto the encoder and the frame is where the values have to be.
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" \
  -vf "setparams=colorspace=smpte240m:color_trc=smpte240m:color_primaries=smpte240m:range=tv:chroma_location=left" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 1 \
  colors-smpte240m.mp4
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
nv12_tag="setparams=colorspace=bt709:color_trc=bt709:color_primaries=bt709:range=tv:chroma_location=center"
ffmpeg -v error -y \
  -f lavfi -i "color=c=black:size=320x240:rate=25:duration=1" -frames:v 2 \
  -vf "$nv12_geq,format=nv12,$nv12_tag" \
  -c:v rawvideo colors-nv12.mkv
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
  -vf "setparams=colorspace=bt709:color_trc=bt709:color_primaries=bt709:range=tv:chroma_location=left" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p10le -g 1 \
  colors-p010.mp4
ffmpeg -v error -y -i colors-p010.mp4 -frames:v 1 \
  -vf "format=p010le,format=rgba" -sws_flags neighbor -f rawvideo colors-p010.rgba

echo "PQ tagged HDR clip and a BT.2020 constant luminance clip"
# Neither has a reference dump. Both exist so the converter's one-time warning can be counted: there
# is no tone mapping and no constant luminance path, and both clips are converted approximately.
pq_tag="setparams=colorspace=bt2020nc:color_trc=smpte2084:color_primaries=bt2020:range=tv:chroma_location=left"
cl_tag="setparams=colorspace=bt2020c:color_trc=bt2020-10:color_primaries=bt2020:range=tv:chroma_location=left"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 5 \
  -vf "format=yuv420p10le,$pq_tag" \
  -c:v libx265 -preset ultrafast -x265-params log-level=error -tag:v hvc1 -g 1 colors-pq.mp4
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=320x240:rate=25:duration=1" -frames:v 5 \
  -vf "format=yuv420p10le,$cl_tag" \
  -c:v libx265 -preset ultrafast -x265-params log-level=error -tag:v hvc1 -g 1 colors-bt2020cl.mp4

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
# FL + 0.7071*FC + 0.7071*SL and the mirror of it on the right.
ffmpeg -v error -y -i surround51.mp4 -ac 2 -f f32le surround51-stereo.f32le
ffmpeg -v error -y -i surround51side.wav -ac 2 -f f32le surround51side-stereo.f32le

echo "30 minute 360p clip for leak and drift soak tests"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=1800" \
  -f lavfi -i "sine=frequency=200:sample_rate=48000:duration=1800" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest soak30min.mp4

ls -la

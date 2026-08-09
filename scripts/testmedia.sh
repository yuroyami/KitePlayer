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

echo "30 minute 360p clip for leak and drift soak tests"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=1800" \
  -f lavfi -i "sine=frequency=200:sample_rate=48000:duration=1800" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest soak30min.mp4

ls -la

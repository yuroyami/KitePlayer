#!/usr/bin/env bash
# Regenerate the test clips used by KitePlayer's playback tests.
# Needs the ffmpeg CLI on PATH. Writes into testmedia/, which is gitignored.
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

echo "720p59.94 h264 + aac, 8s, non-integer frame rate"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=1280x720:rate=59.94:duration=8" \
  -f lavfi -i "sine=frequency=330:sample_rate=44100:duration=8" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac vfr720p60.mp4

echo "4K HEVC Main10, 6s, no audio, for hardware decode"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=3840x2160:rate=30:duration=6" \
  -c:v libx265 -preset ultrafast -pix_fmt yuv420p10le -tag:v hvc1 hevc4k10.mp4

echo "Matroska with an ASS subtitle track"
printf '1\n00:00:00,500 --> 00:00:03,000\nHello from KitePlayer\n\n2\n00:00:03,500 --> 00:00:06,000\nSecond cue with <i>italics</i>\n\n' > subs.srt
ffmpeg -v error -y -i sync1080p30.mp4 -i subs.srt \
  -c:v copy -c:a copy -c:s ass -map 0:v -map 0:a -map 1:0 subbed.mkv

echo "30 minute 360p clip for leak and drift soak tests"
ffmpeg -v error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25:duration=1800" \
  -f lavfi -i "sine=frequency=200:sample_rate=48000:duration=1800" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest soak30min.mp4

ls -la

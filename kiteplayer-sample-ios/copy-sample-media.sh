#!/bin/sh
#
# A build phase of module.xcodeproj. It copies the test clip and the sample songs into the app bundle.
#
# The songs: the one that KITEPLAYER_SAMPLE_SONG or kiteplayer.sample.song in the root
# local.properties names, else every song in kiteplayer-sample-shared/media. They land as
# sample-song, then sample-song-2 up to sample-song-9, with a lower-case extension. The app looks
# them up by these names.

set -eu

REPO_ROOT="$(cd "$SRCROOT/.." && pwd -P)"
: "${TARGET_BUILD_DIR:?}"
: "${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}"
RESOURCES="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
/bin/mkdir -p "$RESOURCES"

CLIP="$REPO_ROOT/testmedia/sync1080p30.mp4"
if ! test -f "$CLIP"; then
  echo "error: $CLIP is missing. Run ./scripts/testmedia.sh first." >&2
  exit 1
fi
/bin/cp -f "$CLIP" "$RESOURCES/sync1080p30.mp4"

SONG="${KITEPLAYER_SAMPLE_SONG:-}"
if test -z "$SONG"; then
  SONG="$( (grep '^kiteplayer.sample.song=' "$REPO_ROOT/local.properties" 2>/dev/null || true) | tail -n 1 | cut -d= -f2-)"
fi
case "$SONG" in
  ""|/*) ;;
  *) SONG="$REPO_ROOT/$SONG" ;;
esac

/bin/rm -f "$RESOURCES"/sample-song.* "$RESOURCES"/sample-song-[2-9].*
if test -n "$SONG"; then
  SONGS="$SONG"
else
  SONGS="$(/usr/bin/find "$REPO_ROOT/kiteplayer-sample-shared/media" -maxdepth 1 -type f \( -iname '*.mp3' -o -iname '*.m4a' -o -iname '*.flac' -o -iname '*.ogg' -o -iname '*.wav' -o -iname '*.aac' \) | LC_ALL=C /usr/bin/sort)"
fi
INDEX=1
printf '%s\n' "$SONGS" | while IFS= read -r FILE; do
  test -f "$FILE" || continue
  test "$INDEX" -le 9 || break
  if test "$INDEX" -eq 1; then NAME=sample-song; else NAME="sample-song-$INDEX"; fi
  EXTENSION="$(printf '%s' "${FILE##*.}" | /usr/bin/tr '[:upper:]' '[:lower:]')"
  /bin/cp -f "$FILE" "$RESOURCES/$NAME.$EXTENSION"
  INDEX=$((INDEX + 1))
done

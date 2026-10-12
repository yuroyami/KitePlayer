# Background playback

The [README](../README.md#background-playback) shows how to set up background playback on Android
and iOS. This page describes what the Android media notification does, and what each other platform
does when the app is in the background.

## The Android notification

- The notification shows the title, the artist, and the previous, play or pause, and next
  buttons. Set `title`, `artist` and `album` on the `MediaItem` to choose the text. Otherwise the
  text comes from the file's tags, then from its file name. `session.setCustomActions` adds your
  own buttons.
- The picture is the file's own cover when the file has one. `session.setArtworkLoader` supplies
  another picture, which wins over the cover. `player.coverArt` gives the cover's bytes to your
  own screens too.
- Skip back and skip forward move 15 seconds. Pass `skipInterval` to `attachMediaSession` for
  another interval.
- The session takes audio focus, so a call or another app pauses or ducks the player, and the
  player pauses when the headphones come out. The focus request and the audio track say whether
  the item is music, speech or a film, from `MediaItem.audioContent`, which by default says film
  for a picture and music for sound alone. `interruptions = null` turns that off, and
  `background = null` leaves the app's background behavior alone.
- While the player plays or buffers, the notification keeps the processor and Wi-Fi awake, so a
  stream keeps loading with the screen off. That needs `WAKE_LOCK`. Pick another `wakeLocks`
  policy in `MediaNotificationOptions`, or `WakeLockPolicy.None` to hold nothing.
- After a pause, the service stays in the foreground for ten minutes (`pausedForegroundTimeout`).
  Then the user can swipe the notification away, which stops the service and leaves the player
  paused. `onDismissed` tells you when the user puts the notification away.
- From Android 12, Android can refuse a start from the background. `onForegroundRefused` tells
  you, and the notification still shows. Android 13 and later need no notification permission for
  it.
- `MediaNotificationOptions` also sets the notification channel (`channelId`, `channelName`), the
  `notificationId`, the `contentIntent` that a tap opens, and the translated button `labels`.
- KitePlayer declares no service and no permission for background playback. Your manifest declares
  the service and the three permissions that the README lists.

## Apple

On iOS, `player.attachMediaSession()` puts the player on the lock screen, once `UIBackgroundModes`
holds `audio` in `Info.plist`.

A native Mac app calls `player.attachMediaSession()` too. The player then shows in Now Playing, the
media keys and an AirPods tap control it, and it pauses when headphones disconnect and the speakers
take over.

## The desktop and the web

A desktop JVM app keeps playing without help. The desktop has no media session, so
`attachMediaSession()` exists there only for shared code to compile, and it shows nothing.

A web page plays while its tab is open. `attachMediaSession()` mirrors the player into the
browser's media session, which the operating system's media overlay, the media keys and a headset
read. Browsers show that session only while the page plays sound through an `<audio>` or `<video>`
element, and KitePlayer plays through Web Audio, which no browser counts. The overlay therefore
appears only when the page also plays through such an element.

# Sample songs

Five songs by **Skullbeatz**, from the [Newgrounds Audio Portal](https://www.newgrounds.com/audio).
The sample apps step through them in this order, and the visualiser draws each one.

| File | Song | Length | Page |
| --- | --- | --- | --- |
| `bad-cat.mp3` | Bad Cat [Master Version] | 4:02 | [376737](https://www.newgrounds.com/audio/listen/376737) |
| `blue.mp3` | Blue | 2:58 | [939134](https://www.newgrounds.com/audio/listen/939134) |
| `helios.mp3` | Skullbeatz - Helios | 5:46 | [763736](https://www.newgrounds.com/audio/listen/763736) |
| `hurry-up-2021.mp3` | Hurry Up 2021 | 4:23 | [998087](https://www.newgrounds.com/audio/listen/998087) |
| `what-a-horrible-night.mp3` | What A Horrible Night To Have A Curse | 4:22 | [843752](https://www.newgrounds.com/audio/listen/843752) |

They cover different genres on purpose, because the visualiser answers each one differently: a
quiet ambient piece, a chiptune with sharp attacks, a steady techno track, a dance track long
enough to use every scan worker, and the original hip hop beat.

## Licence

Every song is under
[CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/), as each song's page states. You
may share and adapt them, including commercially, with credit, under the same licence.

- KitePlayer's Apache-2.0 licence does not cover them.
- Only the sample apps use them. No published artifact contains them.

The audio is each original file. Only the tags were rewritten, so the sample can show the title,
the artist and the licence on screen. The licence is in each file's copyright tag, and the sample
prints that tag under the picture.

A previous version of this file said `bad-cat.mp3` was CC BY-NC-SA 3.0. Its page states CC BY-SA
3.0, with no non-commercial term, so the file and this table now say that.

## Adding or replacing a song

Drop an `.mp3`, `.m4a`, `.flac`, `.ogg`, `.wav` or `.aac` file in this directory. The Android
sample bundles every song here, the desktop sample reads them from here, and the screen offers
them in file name order. Give the file a title, artist and copyright tag, or the sample shows
nothing for it. `-Pkiteplayer.sample.song=<path>` plays one file instead of all of these.

# Segment store

A player downloads the segments of an HLS or DASH presentation again each time it opens the
presentation. A segment store keeps the segments on disk, so a later player reads them from
there. This page describes what the store keeps, when a stored segment is used, and what the
store never holds.

The store is off by default. With no store, a player sends the same requests as before.

Terms used on this page:

- A **segment** is a media segment or an initialization segment that a playlist or a manifest
  names.
- A **store** is a `SegmentStore`: a place that keeps bytes under names, with a size limit.
- An **entry** is what the store holds for one address: a record of the response and the byte
  spans that were read.
- A **span** is one run of bytes of a resource, such as bytes 0 to 49,999.
- A **lifetime** is the time from creating a player to closing it.

## Set-up

Create one store for the application and give it to each player.

```kotlin
val store = fileSegmentStore(
    directory = "$cacheDirectory/kite-segments",
    maxBytes = 512L * 1024 * 1024,
)

val player = KitePlayer.create(
    PlayerConfig(network = NetworkConfig(segmentStore = store)),
)
```

- `fileSegmentStore` is in `kiteplayer-network`. It works on the JVM, Android and Apple targets.
- `fileSegmentStore` returns null in a browser, and when the directory cannot be used. A null is
  safe to pass on: the player then keeps nothing.
- `NetworkConfig.segmentStore` reaches the automatic transport only. A `KtorMediaIoResolver` of
  your own takes the store as its `segmentStore` parameter.
- `Dash.mediaItemFor`, which plays DASH through a client of your own, uses no store.
- Close the store when the application is done with it. A player never closes it.

Give the store a directory of its own. The store removes what it does not know in the part of
the directory that it writes.

## What is stored

Only a segment is stored. The player learns which addresses are segments in two ways:

- **HLS.** The reader looks at each playlist as its bytes pass. The segment lines and the
  `EXT-X-MAP` addresses of a playlist count as segments after its `EXT-X-ENDLIST` line.
- **DASH.** The DASH reader knows the media and initialization addresses from the manifest. They
  count as segments when the manifest is not `dynamic`.

These never reach the store:

- A playlist or a manifest. The player fetches them at each open.
- Any address of an `EXT-X-KEY` or `EXT-X-SESSION-KEY` line, even when a playlist also names it
  as a segment.
- The clock address of a DASH `UTCTiming` element.
- Any address that no playlist or manifest named.
- Anything of a live presentation: an HLS playlist with no `EXT-X-ENDLIST`, or a `dynamic` DASH
  manifest.

The store keeps the bytes that the player read, as spans. A segment that was read in part is
stored in part, and a later read asks the server only for the rest.

## Which responses are stored

The store follows a subset of RFC 9111. A response is stored only when all of these are true:

- The request was a GET, and the status is 200 or 206.
- `Cache-Control` has no `no-store`. A `private` response is stored, because the store belongs
  to one application.
- `Vary` is not `*`.
- The response has a strong `ETag`, a `Last-Modified` date, or a stated lifetime (`max-age` or
  `Expires`).
- The response states its size.

A response from a server with no byte ranges is used again only when the store holds all of it.

## When a stored segment is used

A stored response is **fresh** while its age is less than its lifetime.

- A fresh entry is read with no request.
- The lifetime is `max-age`, or `Expires` minus `Date`.
- With neither, the lifetime is a tenth of the time since `Last-Modified`, at most one day.
- A response with only an `ETag` is never fresh. A `no-cache` response is never fresh.

An entry that is not fresh sends one conditional request at the open, with `If-None-Match` or
`If-Modified-Since`.

- A 304 answer keeps the spans. The entry is fresh again by the headers of the 304.
- A 200 or 206 answer with another validator removes every span first. The new bytes then
  start a new entry. Old and new bytes never mix.
- An answer that refuses the address, such as a 404, removes the entry.

A read of a part that is not stored sends a range request with `If-Range`. When the server then
answers with another file, the store removes the entry and that read fails. The next open reads
the new file.

## Names, logins and Vary

The name of an entry is a SHA-256 digest of these values:

- the namespace of the store,
- the address that was asked for,
- the values of the request headers that the response lists in `Vary`,
- the `Authorization` and `Cookie` values that the item's headers state.

So a store with another namespace cannot read an entry. A request with another login, or another
value of a varied header, cannot read it either. A file name on disk holds no address and no
credential. The record inside an entry holds the address that answered, after redirects.

A request that carries a login or a cookie is stored only in a store that was created with
`privateToOneAccount = true`. Pass true only when every item played with the store belongs to
one account. Use `namespace` to keep two accounts apart in one directory.

## The file store

`fileSegmentStore` keeps each entry in a folder of the directory.

- **Whole spans only.** A span is written to a temporary file and then renamed. A reader sees a
  span only after the rename.
- **Restart.** A store that starts over a directory removes the temporary files that an earlier
  process left.
- **Limit.** `maxBytes` counts the bytes of published spans. When a new span would pass the
  limit, the store removes the entries used longest ago. It never removes an entry that a reader
  holds. When that frees too little, the store drops the new span.
- **Removal.** `remove` and `clear` never cut a read. A reader that holds an entry reads it to
  the end, and the files go when the reader closes. Until then `sizeBytes` still counts them.
- **One process.** The store locks its directory. A second store over the same directory, in the
  same process or in another, gets null.
- **Several players.** One store serves any number of players at once.

## When the store fails

A failure of the store never fails playback. The reader goes on from the network, the player
reports one `PlaybackWarning.SegmentStoreFailed`, and that item uses the store no more.

Bytes read from the store are not counted as downloaded. The network rate that HLS and DASH
quality selection reads stays the rate of the network.

## A store of your own

`SegmentStore` in `kiteplayer-core` is a small interface with no HTTP in it. An entry has a
record, which is opaque bytes, and spans. Implement it to keep segments somewhere else, such as
a database.

```kotlin
public interface SegmentStore : AutoCloseable {
    public val namespace: String
    public val privateToOneAccount: Boolean
    public val sizeBytes: Long
    public fun open(name: String): SegmentStoreEntry
    public fun remove(name: String)
    public fun clear()
}
```

A custom store must give the same promises as the file store: a reader sees only published
spans, and `remove` and `clear` never cut a read.

## Limits

- A browser has no store.
- The player in a Web Worker uses no store.
- Media that is not HLS or DASH, such as one MP4 file, is not stored.
- A response with no stated size is not stored.
- A segment whose address holds a playlist variable (`{$name}`) is not stored.

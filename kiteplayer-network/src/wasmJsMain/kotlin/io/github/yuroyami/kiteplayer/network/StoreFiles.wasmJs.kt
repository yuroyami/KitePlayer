package io.github.yuroyami.kiteplayer.network

/** The web has no store (#547): the browser's own HTTP cache serves a page's requests. */
internal actual fun platformStoreFiles(): StoreFiles? = null

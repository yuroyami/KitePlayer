# Discovery metadata names these classes. Preserve names and the provider constructor under R8.
-keepnames interface io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
-keep class io.github.yuroyami.kiteplayer.network.KtorMediaIoResolverProvider { *; }

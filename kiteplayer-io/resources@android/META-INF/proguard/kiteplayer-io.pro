# Discovery metadata names these classes. Preserve names and the provider constructors under R8 (#869).
-keepnames interface io.github.yuroyami.kiteplayer.spi.MediaIoResolverProvider
-keep class io.github.yuroyami.kiteplayer.io.ResourceUriResolverProvider { *; }
-keep class io.github.yuroyami.kiteplayer.io.LocalFileResolverProvider { *; }

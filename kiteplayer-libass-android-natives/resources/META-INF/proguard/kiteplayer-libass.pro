# Discovery metadata names these classes. Preserve names and the provider constructor under R8.
-keepnames interface io.github.yuroyami.kiteplayer.spi.SubtitleTypesetterProvider
-keep class io.github.yuroyami.kiteplayer.libass.LibassTypesetterProvider { *; }
# JNI resolves these by name.
-keepclasseswithmembernames class io.github.yuroyami.kiteplayer.libass.LibassNative { native <methods>; }

package io.github.yuroyami.kiteplayer.audioviz.viz

/** The earlier name of [WebAudioAnalyser] for pages with a short FFT. Both names are one class now. */
@Deprecated("WebAudioAnalyser adds up the page's bin shape for every FFT size.", ReplaceWith("WebAudioAnalyser"))
internal typealias ShortFftAnalyser = WebAudioAnalyser

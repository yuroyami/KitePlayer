# Calibrated audio feature contract

This documents the raw spectrum portion of the [audio visualiser standard](audioviz-standard.md).
It is separate from recognition accuracy, display scaling and physical performance qualification.
`SpectrumFrame.power` is the raw measurement API. Display heights, momentary programme loudness
and recognition each retain a separate contract and must not be mistaken for raw spectral power.

## Window and availability

`SpectrumAnalyzer` defaults to a nominal 10 ms hop rounded to whole samples, 40 bands, and the
largest supported power-of-two window no longer than 50 ms. This gives 2048 samples at 44.1/48 kHz,
512 at 16 kHz and 256 at 8 kHz. Timestamps always derive from sample counts. Constructor zero
values for `fftSize` or `hop` select these defaults; their public getters report resolved sizes.
An explicit FFT or hop is an authoring override. Supported sample rates are 1000..768000 Hz,
explicit FFT sizes are powers of two from 4 through 32768, and a hop must fit its FFT window.

`AnalysisAvailability` distinguishes unavailable data, warming up and a ready short window.
Before a complete window exists, `SpectrumFrame.power` is null. Ready digital silence has zero
power. A standalone PCM analyser can produce a ready measurement with an unknown media timestamp;
that is different from a missing measurement. Player timelines reject unknown timestamps.

`PowerSpectrum.window` describes the half-open sample interval with `startMicros`, `endMicros`,
`referenceMicros`, sample rate and sample count. Its reference is the interval centre. Unknown
timestamps are null; zero and negative positions are valid. It retains its own measurement time
when a renderer interpolates the surrounding display frame. No interpolation relabels the raw
measurement as newer than the samples it used.

## Power and frequency convention

Each channel is measured independently through a symmetric Hann window. With unnormalised DFT
`X`, length `N`, window `w` and one-sided fold factor `d[k]`, bin power is:

`d[k] * abs(X[k])^2 / (N * sum(w^2))`

The fold factor is one at DC and Nyquist, two elsewhere. This equals power spectral density times
FFT-bin spacing, with nominal full-scale amplitude one. It is mean-square power per bin, not a
coherent-gain tone-amplitude spectrum. Tone amplitude uses a separate coherent-sum conversion.
The distinction follows the density and spectrum conventions in the
[SciPy periodogram documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.periodogram.html).

The implementation packs two real channels into one complex transform. For `Z = FFT(a + i*b)`,
`(|Z[k]|^2 + |Z[-k]|^2)/2` equals `|FFT(a)[k]|^2 + |FFT(b)[k]|^2`. This follows from the
conjugate symmetry of a real-input DFT, described in the
[FFTW transform definition](https://www.fftw.org/fftw3_doc/The-1d-Real_002ddata-DFT.html).
It is an algebraic reduction of the work, with no channel downmix. An odd final channel uses zero
imaginary input. Independent direct-transform tests include unrelated channels, odd channel
counts, opposite polarity, DC and Nyquist. No FFTW implementation or dependency is used.

Channel bin powers are combined with equal weights summing to one. Mono and identical or
opposite-polarity stereo therefore have the same spectral power; left-only stereo has half the
mono power. LFE participates in this musical spectrum. Programme loudness has a different channel
sum and LFE policy. The snapshot preserves channel count, layout and native-order mask. The
plain channel-count `feed` overload reports unknown layout; the `AudioFormat` overload preserves
declared metadata. Reset before changing channels or layout; changing sample rate needs a new
analyser. The player feed handles those resets.

ERB edges run from 30 Hz to `min(16000, 0.95 * sampleRate / 2)` using
`21.4 * log10(1 + 0.00437 * frequencyHz)`. Every bin's power is distributed uniformly over its
frequency cell, bounded by neighbouring-bin midpoints and clipped at DC/Nyquist. Fractional
overlaps with fixed band edges partition its power. Changing band count preserves total in-range
power; a narrow band receives a fraction of a bin and does not invent additional resolution.
`totalMeanSquare` includes the whole Nyquist range and can exceed the sum of displayed bands.

The snapshot owns its bin, band and edge storage. Indexed reads do not expose mutable arrays;
copy methods return caller-owned copies. This allocates snapshots and does not claim zero
allocation. Consumers can retain old measurements while the worker continues safely.

Direct PCM input validates frame counts before indexing. Non-finite input becomes zero and finite
samples are limited to amplitude +/-16; `sanitizedSamples` counts changed input samples across
resets. Player-fed sanitisation is counted in the shared `AudioAnalysisStats` before analysis.

## Verification boundary

Tests compare against a separately calculated direct DFT, check full-scale DC and Nyquist,
bin-centred and off-bin tones, opposite-polarity/left-only stereo, ring wrap, fractional integration
and conservation across 32/40/64 bands. They also check availability, negative/unknown timestamps,
layout metadata and retained snapshot ownership. These are mathematical and software checks;
they do not establish recognition scores, display response or the phone CPU budget.

## Programme reference and display drivers

`SpectrumFrame.programme` describes ungated 400 ms K-weighted mean-square programme power, with
its own window and availability. Its LUFS conversion is `-0.691 + 10*log10(power)`; zero power
has no finite LUFS value and reports null. This is momentary loudness, not integrated loudness,
and no absolute or relative gating is applied. A raw digital-silence flag prevents the reference
from learning the filter's residual decay after silence begins.

Each channel passes through the two BS.1770 filters before weighted powers are summed. The
published 48 kHz coefficients are exact; other rates use a prewarped bilinear transform with the
RLB stage's unit numerator. Native speaker masks take precedence. Mono and front speakers have
weight 1, LFE 0, side surrounds 1.41, and elevated/rear-centre speakers 1. Conventional quad and
5.1 rear-layout pairs follow the Annex 1 surround weight 1.41. In 7.1 and extended layouts, the
rear pair is treated as +/-135 degrees and weighted 1 under Annex 3. Native masks through
top-back-right are supported. Unknown multichannel layouts, inconsistent masks and rates below
8 kHz report unavailable programme loudness; the unweighted spectrum remains usable. Layouts
with nonstandard speaker positions require an explicit future positional description.

These definitions follow [ITU-R BS.1770-5](https://www.itu.int/dms_pubrec/itu-r/rec/bs/R-REC-BS.1770-5-202311-I%21%21PDF-E.pdf).
The programme channel sum intentionally differs from the spectral channel mean. In particular,
identical stereo has twice the mono programme power, while its mean spectral power is unchanged.

One bounded power gain applies to all energy drivers. The causal reference starts at 0.01,
follows non-silent complete momentary power with a 1 s rise and 15 s fall, and clamps gain to
+/-24 dB. `SpectrumAnalyzer.setSongReferencePower` accepts a positive finite complete-song
reference in this same linear-power convention, transitions log gain over 2 s, and holds it.
`reset` retains that fixed reference for same-song seeks; call the setter with null before
feeding a different song. Player-managed map identity and scan delivery are separate integration.

`SpectrumFrame.drivers` carries overall, bass (0..250 Hz), mid (250..2000 Hz), treble
(2000 Hz..Nyquist) and ERB-band fast/slow/peak display heights. The frequency ranges split bin
cells with the same fractional integration as the raw bands. All use the standard power curve
with a -50 dB floor, fast 10/120 ms attack/release and slow 100 ms/2 s attack/release, in media
seconds. Peak power holds 500 ms and then falls 12 dB/s. Snapshot diagnostics expose the shared
gain, its limiting/handover state, and the number of saturated drivers in that analysis.

Compatibility names `bandsRel`, `levelRel`, `bassRel`, `midRel` and `trebleRel` now alias the
shared-gain fast drivers. Their former per-band/per-driver automatic ranges are removed. The
legacy long bass display is removed; a future resolved spectrum needs its own measurement time.
The old constructor options for per-frame smoothing, gravity, dB floor and log-frequency limits
are removed in this breaking authoring API update. The standard's fixed defaults apply uniformly.

## Waveform convention

`scopeMetadata` and `stereoScopeMetadata` describe the exact contiguous samples in the raw traces.
They remain null until a complete source window exists. Each records its own sample interval,
first sample index since reset, trigger displacement from the newest possible slice, source
channel count and projection. The mono trace is the mean of all channels; the paired trace uses
source channels 0 and 1, duplicating channel 0 for mono. Layout metadata identifies their speakers.
A trigger changes decorative trace placement only and does not supply event timestamps.

Raw trace gain is 1, with nominal full scale +/-1 and sanitised headroom through +/-16. The
recommended drawing multiplier `waveformGain` is the square root of the shared power gain.
Waveform drawings and shader strips use that multiplier instead of a private peak-following gain.
Fixed artistic reach factors remain separate. Raw traces are not resampled during analysis.

When drawing into fewer trace points, `WaveformResampler` uses a centred windowed-sinc low-pass
filter with Blackman window, cutoff 0.45 of the output sample rate and radius eight input/output
steps. Reflected boundaries preserve constant signals. Coefficients are rebuilt only when input
or output length changes; paired channels use identical coefficients. Equal lengths copy samples,
upsampling uses linear interpolation, and a one-point output is the mean. This is a filtered trace,
not a peak-preserving min/max envelope. Shader waveform strips follow the same rule.

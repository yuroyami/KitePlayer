@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.js.JsAny
import kotlin.math.ceil

/**
 * The web text raster engine: each active text cue becomes one image through a 2D canvas, the
 * browser's own text engine, so fonts, shaping and right-to-left order are the browser's. An
 * `OffscreenCanvas` is used where there is one, so it also works in a worker. Bitmap cues pass
 * their pixels through untouched.
 *
 * The placement arithmetic is `DesktopSubtitleRasterizer`'s line for line, and both go through
 * `rasterizeCues`, `cueOrigin` and `regionImages`, so a cue lands at the same place on every
 * platform.
 *
 * A canvas draws text and does not break lines, so the breaking is here, in JavaScript: at spaces,
 * and between words as `Intl.Segmenter` finds them in scripts that write none, such as Chinese,
 * Japanese and Thai. A word wider than the line is cut between characters.
 *
 * An unpositioned cue is laid out in a box as wide as the safe area, as on the desktop, and the
 * image is then cut to the columns that hold ink, so only those pixels cross from JavaScript.
 * They cross as one Latin-1 string per image and come out premultiplied, as [RgbaBitmap] says.
 */
internal class WebSubtitleRasterizer private constructor(private val engine: JsAny) : SubtitleRasterizer {

    override fun rasterize(
        cues: List<SubtitleCue>,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        position: Float,
    ): List<OverlayImage> = rasterizeCues(cues, viewportWidth, viewportHeight) { cue, stackedBottom, budget ->
        rasterizeText(cue, viewportWidth, viewportHeight, fontScale, stackedBottom, position, budget)
    }

    private fun rasterizeText(
        cue: SubtitleCue.Text,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        stackedBottom: Int,
        position: Float,
        budget: OverlayPixels,
    ): OverlayImage? {
        if (cue.spans.all { it.text.isEmpty() }) return null
        val layoutSpec = cue.layout
        val firstStyle = cue.spans.first().style
        fun sizeOf(style: CueStyle) = cueFontSizePx(style, layoutSpec, viewportHeight, fontScale)
        val safeWidth = cueSafeWidth(layoutSpec, viewportWidth)
        if (safeWidth <= 0) return null

        val runs = StringBuilder("[")
        for (span in cue.spans) {
            if (span.text.isEmpty()) continue
            val style = span.style
            val size = sizeOf(style)
            if (runs.length > 1) runs.append(',')
            runs.append("{\"t\":").appendJson(span.text)
                .append(",\"f\":").appendJson(canvasFont(style, size))
                .append(",\"s\":").append(size)
                .append(",\"c\":").appendJson(canvasColor(style.primaryColor))
                .append(",\"o\":").appendJson(canvasColor(style.outlineColor))
                .append(",\"w\":").append(cueOutlinePx(style, fontScale, size))
                .append(",\"u\":").append(style.underline)
                .append(",\"k\":").append(style.strikeThrough)
                .append('}')
        }
        runs.append(']')
        val cueState = webCuePrepare(engine, runs.toString()) ?: return null

        // The cue's own wrap mode decides the width the lines break at; see wrapWidthFor.
        val wrapWidth = wrapWidthFor(layoutSpec.wrap, safeWidth) { webCueLineCount(engine, cueState, it) }
        // Only the lines that fit the viewport are kept, from the top, as on the other platforms.
        if (webCueMeasure(engine, cueState, wrapWidth, viewportHeight) == 0) return null
        val height = webCueHeight(cueState).coerceIn(1, viewportHeight)

        // A positioned cue's box is its text extent, and the placement anchors that extent on the
        // authored point. An unpositioned cue keeps the full-width box, whose alignment inside it
        // is its horizontal placement. Never wider than the viewport.
        val positioned = layoutSpec.positionX != null || layoutSpec.positionY != null
        val textWidth = webCueWidth(cueState)
        val width = if (positioned) {
            textWidth.coerceIn(1, safeWidth)
        } else {
            textWidth.coerceIn(safeWidth, maxOf(safeWidth, viewportWidth))
        }

        // The shadow and the viewer's box land outside the text box, so the image grows for them
        // and the placement below subtracts that back off. See CueShadow.
        val shadow = cueShadow(firstStyle, fontScale)
        val boxPad = cueBoxPadPx(firstStyle, fontScale)
        val imageWidth = width + shadow.pad + 2 * boxPad
        val imageHeight = height + shadow.pad + 2 * boxPad
        val inset = shadow.origin + boxPad
        val align = when (layoutSpec.alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> ALIGN_LEFT
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> ALIGN_RIGHT
            else -> ALIGN_CENTER
        }

        // The columns of the image that hold ink. Nothing else is allocated or copied.
        val cropLeft = webCueCropLeft(cueState, width, align, inset, boxPad, shadow.pad, imageWidth)
        val cropWidth = webCueCropWidth(cueState)
        if (cropWidth <= 0) return null
        if (!budget.take(cropWidth, imageHeight)) return null

        val packed = webCueDraw(
            engine, cueState, width, align, inset, cropLeft, cropWidth, imageHeight,
            boxPad, if (boxPad > 0) canvasColor(firstStyle.backgroundColor) else "",
            shadow.draws, shadow.offset, canvasColor(firstStyle.shadowColor),
        )
        if (packed.length != cropWidth * imageHeight * 4) return null
        // Latin-1 by construction: every code unit is one byte.
        val pixels = ByteArray(packed.length) { packed[it].code.toByte() }

        // Shared by every rasterizer, so a cue lands in the same place on every platform.
        val origin = cueOrigin(layoutSpec, viewportWidth, viewportHeight, width, height, position, stackedBottom)
        return OverlayImage(
            x = origin.x - shadow.origin - boxPad + cropLeft,
            y = origin.y - shadow.origin - boxPad,
            bitmap = RgbaBitmap(cropWidth, imageHeight, pixels),
        )
    }

    internal companion object {
        private const val ALIGN_LEFT = 0
        private const val ALIGN_CENTER = 1
        private const val ALIGN_RIGHT = 2

        /** Made once, and null where there is no 2D canvas to draw text with, as under Node. */
        private val shared: WebSubtitleRasterizer? by lazy { webCueEngine()?.let(::WebSubtitleRasterizer) }

        /** The rasterizer of this page or worker, or null when it has no 2D canvas. */
        fun orNull(): WebSubtitleRasterizer? = shared
    }
}

/** A CSS font for a canvas: the family asked for, then the browser's sans face for what it lacks. */
internal fun canvasFont(style: CueStyle, sizePx: Float): String {
    val family = style.fontFamily?.takeIf { it.isNotBlank() }
        ?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\", " }
        .orEmpty()
    return (if (style.italic) "italic " else "") + (if (style.bold) "bold " else "") + sizePx + "px " + family + "sans-serif"
}

/** A straight-alpha ARGB colour as CSS. */
internal fun canvasColor(argb: Int): String {
    val alpha = (argb ushr 24) / 255.0
    return "rgba(" + ((argb shr 16) and 0xFF) + "," + ((argb shr 8) and 0xFF) + "," + (argb and 0xFF) + "," + alpha + ")"
}

/** Appends [text] as a JSON string. Lone surrogates are written as escapes, so the text always parses. */
internal fun StringBuilder.appendJson(text: String): StringBuilder {
    append('"')
    for (character in text) {
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character < ' ' || character.isSurrogate() || character == '\u2028' || character == '\u2029' -> {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            }
            else -> append(character)
        }
    }
    return append('"')
}

/**
 * The canvas, its context and the caches of one page or worker, or null when it has no 2D canvas.
 * `willReadFrequently` keeps the canvas on the CPU, because every image is read back.
 */
@JsFun(
    """() => {
      let canvas = null;
      try {
        if (typeof OffscreenCanvas !== 'undefined') canvas = new OffscreenCanvas(1, 1);
        else if (typeof document !== 'undefined') canvas = document.createElement('canvas');
      } catch (e) { canvas = null; }
      if (!canvas) return null;
      let ctx = null;
      try { ctx = canvas.getContext('2d', { willReadFrequently: true }); } catch (e) { ctx = null; }
      if (!ctx || typeof ctx.measureText !== 'function' || typeof ctx.getImageData !== 'function') return null;
      let words = null, graphemes = null;
      try {
        if (typeof Intl !== 'undefined' && Intl.Segmenter) {
          words = new Intl.Segmenter(undefined, { granularity: 'word' });
          graphemes = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
        }
      } catch (e) { words = null; graphemes = null; }
      const engine = { canvas, ctx, words, graphemes, widths: new Map(), fonts: new Map() };
      // The width of text in one font. Kept, because balancing a cue measures the same words again.
      engine.measure = (font, text) => {
        if (!text) return 0;
        const key = font + '\n' + text;
        let w = engine.widths.get(key);
        if (w === undefined) {
          if (engine.widths.size > 4096) engine.widths.clear();
          ctx.font = font;
          w = ctx.measureText(text).width;
          engine.widths.set(key, w);
        }
        return w;
      };
      // How far one font reaches above and below its baseline.
      engine.metrics = (font, size) => {
        let m = engine.fonts.get(font);
        if (!m) {
          if (engine.fonts.size > 256) engine.fonts.clear();
          ctx.font = font;
          const t = ctx.measureText('Mg');
          const up = t.fontBoundingBoxAscent, down = t.fontBoundingBoxDescent;
          m = {
            ascent: Number.isFinite(up) && up > 0 ? up : size * 0.8,
            descent: Number.isFinite(down) && down >= 0 ? down : size * 0.2,
          };
          engine.fonts.set(font, m);
        }
        return m;
      };
      // Breaks a cue at a width. The lines are kept on the cue, so the same width breaks nothing again.
      engine.breakLines = (cue, width) => {
        if (cue.linesWidth === width && cue.lines) return cue.lines.length;
        const runs = cue.runs;
        // The width of one stretch of a paragraph, piece by piece, each in its own font.
        const span = (p, from, to) => {
          let w = 0;
          for (const piece of p.pieces) {
            const a = Math.max(from, piece.start), b = Math.min(to, piece.end);
            if (b > a) w += engine.measure(runs[piece.run].f, p.text.slice(a, b));
          }
          return w;
        };
        const lines = [];
        for (const p of cue.paragraphs) {
          if (p.tokens.length === 0) { lines.push({ p, start: 0, end: 0 }); continue; }
          let start = -1, end = 0, used = 0;
          const flush = () => { if (start >= 0) lines.push({ p, start, end }); start = -1; used = 0; };
          for (const token of p.tokens) {
            const ink = span(p, token.start, token.ink);
            const whole = span(p, token.start, token.end);
            if (start >= 0 && used + ink > width) flush();
            if (start < 0 && ink > width) {
              // One word wider than the line: cut between characters, at least one on each line.
              const text = p.text.slice(token.start, token.ink);
              const units = engine.graphemes ? Array.from(engine.graphemes.segment(text), s => s.segment) : Array.from(text);
              let from = token.start, at = token.start, w = 0;
              for (const unit of units) {
                const uw = span(p, at, at + unit.length);
                if (at > from && w + uw > width) { lines.push({ p, start: from, end: at }); from = at; w = 0; }
                w += uw; at += unit.length;
              }
              start = from; end = at; used = w + (whole - ink);
              continue;
            }
            if (start < 0) start = token.start;
            end = token.ink;
            used += whole;
          }
          flush();
        }
        cue.lines = lines;
        cue.linesWidth = width;
        return lines.length;
      };
      return engine;
    }""",
)
private external fun webCueEngine(): JsAny?

/**
 * Reads one cue's runs and finds where its lines may break. Null when the runs do not parse.
 *
 * A paragraph is the text between two authored newlines. A token is the least a line holds: a word
 * with the spaces after it. A break never comes before closing punctuation or after opening
 * punctuation, which is the rule Chinese and Japanese text needs.
 */
@JsFun(
    """(engine, json) => {
      let runs;
      try { runs = JSON.parse(json); } catch (e) { return null; }
      if (!Array.isArray(runs) || runs.length === 0) return null;
      const space = /^[\s\u00a0]+$/u;
      const noSpaceScript = /[\u0e00-\u0eff\u1000-\u109f\u1780-\u17ff\u2e80-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff00-\uffef\u{20000}-\u{3ffff}]/u;
      const closing = /^[\u3001\u3002\uff0c\uff0e\uff01\uff1f\uff1a\uff1b\u300d\u300f\uff09\u3015\u3011\u3009\u300b\u30fb\u30fc\u2026\u2014,.!?:;)\]}%\u00bb\u201d\u2019]/u;
      const opening = /[\u300c\u300e\uff08\u3014\u3010\u3008\u300a(\[{\u00ab\u201c\u2018]$/u;
      const paragraphs = [];
      let current = { text: '', pieces: [] };
      const close = () => { paragraphs.push(current); current = { text: '', pieces: [] }; };
      runs.forEach((run, index) => {
        const parts = String(run.t).split('\n');
        parts.forEach((part, at) => {
          if (at > 0) close();
          // A piece with no text still names the font of an empty line.
          current.pieces.push({ run: index, start: current.text.length, end: current.text.length + part.length });
          current.text += part;
        });
      });
      close();
      for (const p of paragraphs) {
        const text = p.text;
        let segments = [];
        if (engine.words) {
          for (const s of engine.words.segment(text)) segments.push(s.segment);
        } else {
          // Without a segmenter: words at spaces, and each character of a script that writes none.
          let buffer = '';
          for (const ch of text) {
            if (space.test(ch) || noSpaceScript.test(ch)) { if (buffer) segments.push(buffer); buffer = ''; segments.push(ch); }
            else buffer += ch;
          }
          if (buffer) segments.push(buffer);
        }
        const tokens = [];
        let at = 0, token = null, previous = '';
        for (const segment of segments) {
          const isSpace = space.test(segment);
          let breakBefore = false;
          if (token && !isSpace) {
            if (token.spaced) breakBefore = true;
            else if ((noSpaceScript.test(segment) || noSpaceScript.test(previous)) && !closing.test(segment) && !opening.test(previous)) breakBefore = true;
          }
          if (!token || breakBefore) {
            if (token) tokens.push(token);
            token = { start: at, end: at, ink: at, spaced: false };
          }
          at += segment.length;
          token.end = at;
          if (isSpace) token.spaced = true; else { token.ink = at; token.spaced = false; }
          previous = segment;
        }
        if (token) tokens.push(token);
        p.tokens = tokens;
      }
      return { runs, paragraphs, lines: null, linesWidth: -1, shown: [], width: 0, height: 0, cropWidth: 0 };
    }""",
)
private external fun webCuePrepare(engine: JsAny, json: String): JsAny?

/**
 * Breaks the cue at [width] and answers how many lines that makes. The lines are kept on the cue,
 * so measuring at the same width afterwards breaks nothing again.
 */
@JsFun(
    """(engine, cue, width) => engine.breakLines(cue, width)""",
)
private external fun webCueLineCount(engine: JsAny, cue: JsAny, width: Int): Int

/**
 * Measures the lines of the cue broken at [width], keeps those that fit [viewportHeight] from the
 * top, and answers how many that is. Each kept line knows its pieces, its width and how far it
 * reaches above and below its baseline.
 */
@JsFun(
    """(engine, cue, width, viewportHeight) => {
      engine.breakLines(cue, width);
      const runs = cue.runs;
      const rtl = /[\u0590-\u08ff\ufb1d-\ufdff\ufe70-\ufeff]/;
      const ltr = /[A-Za-z\u00c0-\u024f\u0370-\u052f\u0e00-\u0e7f\u3040-\u30ff\u4e00-\u9fff\uac00-\ud7af]/;
      const shown = [];
      let height = 0, widest = 0;
      for (const line of cue.lines) {
        const p = line.p;
        const pieces = [];
        let w = 0, ascent = 0, descent = 0;
        for (const piece of p.pieces) {
          const a = Math.max(line.start, piece.start), b = Math.min(line.end, piece.end);
          const empty = line.end === line.start && piece.start <= line.start && piece.end >= line.start;
          if (b <= a && !empty) continue;
          const run = runs[piece.run];
          const m = engine.metrics(run.f, run.s);
          ascent = Math.max(ascent, m.ascent);
          descent = Math.max(descent, m.descent);
          if (b > a) {
            const text = p.text.slice(a, b);
            const pw = engine.measure(run.f, text);
            pieces.push({ run, text, width: pw });
            w += pw;
          }
        }
        if (ascent === 0 && descent === 0) {
          const m = engine.metrics(runs[0].f, runs[0].s);
          ascent = m.ascent; descent = m.descent;
        }
        const lineHeight = Math.ceil(ascent + descent);
        if (shown.length > 0 && height + lineHeight > viewportHeight) break;
        // The first strong character says which way the line runs.
        const text = p.text.slice(line.start, line.end);
        let rightToLeft = false;
        for (const ch of text) { if (rtl.test(ch)) { rightToLeft = true; break; } if (ltr.test(ch)) break; }
        shown.push({ pieces, width: w, ascent, descent, rightToLeft });
        height += lineHeight;
        if (w > widest) widest = w;
      }
      cue.shown = shown;
      cue.height = height;
      cue.width = Math.ceil(widest);
      return shown.length;
    }""",
)
private external fun webCueMeasure(engine: JsAny, cue: JsAny, width: Int, viewportHeight: Int): Int

@JsFun("(cue) => cue.width")
private external fun webCueWidth(cue: JsAny): Int

@JsFun("(cue) => cue.height")
private external fun webCueHeight(cue: JsAny): Int

/**
 * The first column of the [imageWidth] wide image that can hold ink, and on the cue how many
 * columns from there can. The room around the lines is for the outline, the viewer's box, the
 * shadow and what a slanted or wide glyph draws outside its own advance.
 */
@JsFun(
    """(cue, boxWidth, align, inset, boxPad, shadowPad, imageWidth) => {
      let left = Infinity, right = -Infinity, room = 2;
      for (const line of cue.shown) {
        const x = align === 0 ? 0 : align === 2 ? boxWidth - line.width : (boxWidth - line.width) / 2;
        if (x < left) left = x;
        if (x + line.width > right) right = x + line.width;
        for (const piece of line.pieces) room = Math.max(room, piece.run.w + piece.run.s * 0.3 + 2);
      }
      if (left === Infinity) { cue.cropWidth = 0; return 0; }
      const from = Math.max(0, Math.floor(inset + left - room - boxPad - shadowPad));
      const to = Math.min(imageWidth, Math.ceil(inset + right + room + boxPad + shadowPad));
      cue.cropWidth = Math.max(0, to - from);
      return from;
    }""",
)
private external fun webCueCropLeft(cue: JsAny, boxWidth: Int, align: Int, inset: Int, boxPad: Int, shadowPad: Int, imageWidth: Int): Int

@JsFun("(cue) => cue.cropWidth")
private external fun webCueCropWidth(cue: JsAny): Int

/**
 * Draws the measured lines and answers the pixels of the columns from [cropLeft], premultiplied,
 * as one Latin-1 string of four bytes for each pixel. An empty string when the canvas fails.
 *
 * The order is the desktop's: the viewer's box, one path for all lines so a translucent box does
 * not blend twice where two paddings overlap; then the shadow, the same glyphs and outlines flat in
 * the shadow colour; then each line's outlines, and its fills over them.
 */
@JsFun(
    """(engine, cue, boxWidth, align, inset, cropLeft, cropWidth, imageHeight, boxPad, boxColor, shadowDraws, shadowOffset, shadowColor) => {
      const canvas = engine.canvas, ctx = engine.ctx;
      try {
        canvas.width = cropWidth;
        canvas.height = imageHeight;
        ctx.setTransform(1, 0, 0, 1, -cropLeft, 0);
        ctx.textBaseline = 'alphabetic';
        ctx.textAlign = 'left';
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        const lineX = (line) => (align === 0 ? 0 : align === 2 ? boxWidth - line.width : (boxWidth - line.width) / 2);
        if (boxPad > 0) {
          ctx.beginPath();
          let top = inset;
          for (const line of cue.shown) {
            ctx.rect(lineX(line) + inset - boxPad, top - boxPad, line.width + 2 * boxPad, line.ascent + line.descent + 2 * boxPad);
            top += Math.ceil(line.ascent + line.descent);
          }
          ctx.fillStyle = boxColor;
          ctx.fill('nonzero');
        }
        const pass = (dx, dy, silhouette) => {
          let top = dy;
          for (const line of cue.shown) {
            const baseline = top + line.ascent;
            ctx.direction = line.rightToLeft ? 'rtl' : 'ltr';
            // Pieces are in reading order, so a right-to-left line is filled from its right end.
            const placed = [];
            let x = line.rightToLeft ? lineX(line) + dx + line.width : lineX(line) + dx;
            for (const piece of line.pieces) {
              if (line.rightToLeft) x -= piece.width;
              placed.push({ piece, x });
              if (!line.rightToLeft) x += piece.width;
            }
            for (const { piece, x } of placed) {
              if (!(piece.run.w > 0)) continue;
              ctx.font = piece.run.f;
              ctx.lineWidth = piece.run.w;
              ctx.strokeStyle = silhouette || piece.run.o;
              ctx.strokeText(piece.text, x, baseline);
            }
            for (const { piece, x } of placed) {
              ctx.font = piece.run.f;
              ctx.fillStyle = silhouette || piece.run.c;
              ctx.fillText(piece.text, x, baseline);
              const thick = Math.max(1, piece.run.s / 14);
              if (piece.run.u) ctx.fillRect(x, baseline + piece.run.s * 0.1, piece.width, thick);
              if (piece.run.k) ctx.fillRect(x, baseline - piece.run.s * 0.3, piece.width, thick);
            }
            top += Math.ceil(line.ascent + line.descent);
          }
        };
        if (shadowDraws) pass(inset + shadowOffset, inset + shadowOffset, shadowColor);
        pass(inset, inset, null);
        const data = ctx.getImageData(0, 0, cropWidth, imageHeight).data;
        // The canvas answers straight alpha, and every consumer reads premultiplied.
        for (let i = 0; i < data.length; i += 4) {
          const a = data[i + 3];
          if (a === 255) continue;
          data[i] = Math.round(data[i] * a / 255);
          data[i + 1] = Math.round(data[i + 1] * a / 255);
          data[i + 2] = Math.round(data[i + 2] * a / 255);
        }
        const parts = [];
        for (let i = 0; i < data.length; i += 0x8000) parts.push(String.fromCharCode.apply(null, data.subarray(i, i + 0x8000)));
        return parts.join('');
      } catch (e) {
        return '';
      } finally {
        // The pixels of the last cue are not kept alive between cues.
        try { canvas.width = 1; canvas.height = 1; } catch (e) {}
      }
    }""",
)
private external fun webCueDraw(
    engine: JsAny,
    cue: JsAny,
    boxWidth: Int,
    align: Int,
    inset: Int,
    cropLeft: Int,
    cropWidth: Int,
    imageHeight: Int,
    boxPad: Int,
    boxColor: String,
    shadowDraws: Boolean,
    shadowOffset: Float,
    shadowColor: String,
): String

/*
 * The shared libass driver against the real library, on the host.
 *
 * What it proves, and what every binding of this module then inherits: a streamed track (header,
 * then Matroska-form events) renders the very same bytes as the whole document those events came
 * from; an unchanged frame answers "unchanged" rather than a copy; a clear empties the picture; a
 * geometry change forces a redraw; and the packed layout is what the Kotlin unpackers read.
 *
 * This suite needs libass installed on the host, which run-c-tests.sh checks for and says so when
 * it is missing. It is deliberately not the pack-limits suite: that one runs everywhere and asserts
 * arithmetic, this one asserts pixels and needs a font engine to make any.
 */

#include "harness.h"
#include "kite_ass.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *HEADER =
    "[Script Info]\n"
    "ScriptType: v4.00+\n"
    "PlayResX: 640\n"
    "PlayResY: 360\n"
    "\n"
    "[V4+ Styles]\n"
    "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
    "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
    "Alignment, MarginL, MarginR, MarginV, Encoding\n"
    "Style: Default,Helvetica,40,&H0000FF00,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1\n"
    "\n"
    "[Events]\n"
    "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";

static const char *DIALOGUE = "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Full throttle\n";

/* The same event in the form a Matroska demuxer hands out: ReadOrder first, no timing. */
static const char *CHUNK = "0,0,Default,,0,0,0,,Full throttle";

static int32_t read_int(const unsigned char *at) {
    int32_t value;
    memcpy(&value, at, sizeof(value));
    return value;
}

typedef struct {
    int regions;
    long visible;
    long greenish;
    int premultiplied_ok;
} picture_stats;

static picture_stats inspect(const unsigned char *packed, int size) {
    picture_stats stats = { 0, 0, 0, 1 };
    if (size < 4) return stats;
    int count = read_int(packed);
    stats.regions = count;
    size_t header_at = 4;
    size_t pixel_at = 4 + (size_t) count * 20;
    for (int i = 0; i < count; i++) {
        int32_t width = read_int(packed + header_at + 8);
        int32_t height = read_int(packed + header_at + 12);
        int32_t bytes = read_int(packed + header_at + 16);
        header_at += 20;
        if (bytes != width * height * 4) stats.premultiplied_ok = 0;
        for (int32_t px = 0; px < bytes; px += 4) {
            int red = packed[pixel_at + px], green = packed[pixel_at + px + 1], alpha = packed[pixel_at + px + 3];
            if (alpha > 32) {
                stats.visible++;
                if (green > 200 && red < 64) stats.greenish++;
                if (red > alpha + 1 || green > alpha + 1) stats.premultiplied_ok = 0;
            }
        }
        pixel_at += (size_t) bytes;
    }
    return stats;
}

int main(void) {
    kt_suite_begin("test_kite_ass");

    kite_ass *document = kite_ass_open();
    kite_ass *streamed = kite_ass_open();
    kt_case("the driver opens twice on one host");
    KT_NOT_NULL(document);
    KT_NOT_NULL(streamed);
    if (!document || !streamed) return kt_suite_end();

    kite_ass_set_frame(document, 640, 360, 640, 360, 0, 0, 0, 0, 1.0, 1.0);
    kite_ass_set_frame(streamed, 640, 360, 640, 360, 0, 0, 0, 0, 1.0, 1.0);

    char script[2048];
    snprintf(script, sizeof(script), "%s%s", HEADER, DIALOGUE);
    kt_case("a whole document opens");
    KT_EQ_INT(kite_ass_open_document(document, script, strlen(script)), 1);

    kt_case("a header-only track opens and takes a Matroska event");
    KT_EQ_INT(kite_ass_open_track(streamed, HEADER, (int) strlen(HEADER)), 1);
    kite_ass_add_event(streamed, CHUNK, (int) strlen(CHUNK), 1000, 4000);

    const unsigned char *doc_packed = NULL, *stream_packed = NULL;
    int doc_size = 0, stream_size = 0;
    kt_case("both render a visible green line at two seconds");
    KT_EQ_INT(kite_ass_render(document, 2000, &doc_packed, &doc_size), 1);
    KT_EQ_INT(kite_ass_render(streamed, 2000, &stream_packed, &stream_size), 1);
    picture_stats doc = inspect(doc_packed, doc_size);
    KT_CHECKF(doc.regions > 0, "the document rendered %d regions", doc.regions);
    KT_CHECKF(doc.visible > 100, "only %ld visible pixels for a 40px line", doc.visible);
    KT_CHECKF(doc.greenish > 500, "only %ld of %ld visible pixels were green", doc.greenish, doc.visible);
    KT_CHECKF(doc.premultiplied_ok, "a channel exceeded its alpha: the buffer is not premultiplied");
    kt_detail("%d regions, %ld visible, %ld green", doc.regions, doc.visible, doc.greenish);

    kt_case("the streamed track renders byte for byte what the document renders");
    KT_EQ_INT(stream_size, doc_size);
    KT_CHECKF(stream_size == doc_size && memcmp(doc_packed, stream_packed, (size_t) doc_size) == 0,
              "the two packed buffers differ");

    kt_case("the same frame a millisecond later is reported unchanged, not copied");
    const unsigned char *again = NULL;
    int again_size = 0;
    KT_EQ_INT(kite_ass_render(document, 2001, &again, &again_size), 0);

    kt_case("a time past the event renders an empty picture once, then unchanged");
    KT_EQ_INT(kite_ass_render(document, 20000, &again, &again_size), 1);
    KT_EQ_INT(read_int(again), 0);
    KT_EQ_INT(kite_ass_render(document, 20001, &again, &again_size), 0);

    kt_case("clearing the streamed events empties the picture");
    kite_ass_clear_events(streamed);
    KT_EQ_INT(kite_ass_render(streamed, 2000, &again, &again_size), 1);
    KT_EQ_INT(read_int(again), 0);

    kt_case("re-feeding the event after a clear brings the line back");
    kite_ass_add_event(streamed, CHUNK, (int) strlen(CHUNK), 1000, 4000);
    kite_ass_add_event(streamed, CHUNK, (int) strlen(CHUNK), 1000, 4000);
    KT_EQ_INT(kite_ass_render(streamed, 2000, &again, &again_size), 1);
    KT_EQ_INT(again_size, doc_size);
    kt_note("the duplicate ReadOrder was dropped by libass: the picture matches a single event");

    kt_case("a larger frame forces a redraw with more pixels");
    kite_ass_set_frame(document, 1280, 720, 640, 360, 0, 0, 0, 0, 1.0, 1.0);
    KT_EQ_INT(kite_ass_render(document, 2000, &again, &again_size), 1);
    picture_stats big = inspect(again, again_size);
    KT_CHECKF(big.visible > doc.visible * 2, "a 2x frame drew %ld visible pixels against %ld", big.visible, doc.visible);

    kt_case("letterbox margins move the line into the frame's lower area");
    kite_ass_set_frame(document, 1280, 720, 1280, 540, 90, 90, 0, 0, 1.0, 1.0);
    KT_EQ_INT(kite_ass_render(document, 2000, &again, &again_size), 1);
    {
        int count = read_int(again);
        int lowest_bottom = 0;
        for (int i = 0; i < count; i++) {
            int32_t y = read_int(again + 4 + i * 20 + 4);
            int32_t h = read_int(again + 4 + i * 20 + 12);
            if (y + h > lowest_bottom) lowest_bottom = y + h;
        }
        KT_CHECKF(lowest_bottom <= 720 - 90 + 2, "text reached y=%d, past the video area's bottom bar", lowest_bottom);
        KT_CHECKF(lowest_bottom > 360, "text sat at y=%d, above the middle of a bottom-aligned frame", lowest_bottom);
    }

    kt_case("the family of a real font file is read from its own name table");
    {
        FILE *f = fopen("/System/Library/Fonts/Supplemental/Arial.ttf", "rb");
        if (!f) {
            kt_note("no Arial.ttf on this host, skipping the name table read");
        } else {
            fseek(f, 0, SEEK_END);
            long n = ftell(f);
            fseek(f, 0, SEEK_SET);
            unsigned char *bytes = (unsigned char *) malloc((size_t) n);
            size_t got = fread(bytes, 1, (size_t) n, f);
            fclose(f);
            char family[128] = { 0 };
            KT_EQ_INT(kite_ass_family_of(bytes, got, family, sizeof(family)), 1);
            KT_CHECKF(strcmp(family, "Arial") == 0, "read family '%s' from Arial.ttf", family);
            /* The driver adopts the first loaded family as libass' default family. */
            kite_ass *fallback = kite_ass_open();
            kite_ass_add_font(fallback, "Arial.ttf", (const char *) bytes, (int) got);
            KT_CHECKF(fallback->fallback_family && strcmp(fallback->fallback_family, "Arial") == 0,
                      "fallback family is '%s'", fallback->fallback_family ? fallback->fallback_family : "(none)");
            kite_ass_close(fallback);
            free(bytes);
        }
    }

    kt_case("bytes that are not a font yield no family and no crash");
    {
        char family[16] = { 0 };
        const char *junk = "not a font at all, and long enough to be read";
        KT_EQ_INT(kite_ass_family_of((const unsigned char *) junk, strlen(junk), family, sizeof(family)), 0);
        KT_EQ_INT(kite_ass_family_of(NULL, 0, family, sizeof(family)), 0);
    }

    kt_case("a font added from memory is taken up without a crash");
    {
        const char *fake_font = "not a font at all";
        kite_ass_add_font(document, "Bogus.ttf", fake_font, (int) strlen(fake_font));
        KT_EQ_INT(kite_ass_render(document, 2000, &again, &again_size), 1);
        kt_note("a rejected font leaves the system provider in charge and the line still renders");
    }

    kite_ass_close(document);
    kite_ass_close(streamed);
    kite_ass_close(NULL);
    return kt_suite_end();
}

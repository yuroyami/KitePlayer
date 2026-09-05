/*
 * The one libass driver every binding of :kiteplayer-libass shares.
 *
 * Kotlin/Native reaches it through cinterop, the JVM and Android through the JNI adapter beside
 * it, the web through an emscripten export table. All three used to risk drifting apart in the
 * one place that must not drift: how an ASS_Image becomes premultiplied RGBA. So the driver is a
 * header of static functions, included by each binding and compiled into it, and the bindings are
 * left with nothing but argument marshalling.
 *
 * ONE call renders one frame and answers ONE packed buffer, owned by the driver and valid until
 * the next render or close. The layout, every field a native-order int32:
 *
 *   int32 regionCount
 *   regionCount x { int32 x, y, w, h, pixelByteCount }
 *   the pixel blobs back to back, each premultiplied RGBA8888, row major, no padding
 *
 * Premultiplied because RgbaBitmap documents premultiplied bytes and every consumer uploads them
 * unconverted. libass colour is RRGGBBAA with AA as TRANSPARENCY (0 opaque), inverted once here,
 * and each channel is scaled by the pixel's own alpha at emit. The size arithmetic goes through
 * libass_pack_limits.h, whose ceiling keeps a hostile script from wrapping a 32-bit size_t.
 */

#ifndef KITE_ASS_H
#define KITE_ASS_H

#include <stdarg.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <ass/ass.h>

#include "libass_pack_limits.h"

typedef struct kite_ass {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;

    /* The packed answer of the last render, grown on demand and never shrunk. */
    unsigned char *packed;
    size_t packed_capacity;
    size_t packed_size;

    /* The geometry in force, so a repeated set_frame costs a compare and no renderer call. */
    int frame_w, frame_h, storage_w, storage_h;
    int margin_t, margin_b, margin_l, margin_r;
    double font_scale, line_position;

    /* Fonts arrived since the font selector was built; the next render rebuilds it. */
    int fonts_dirty;
    /* The geometry changed or a track was opened since the last render, so libass' "unchanged"
     * verdict is not to be trusted: it compares against a frame drawn for another world. */
    int force_next;
    /* True once a render has answered, so the very first one always produces a picture. */
    int has_rendered;
    /* How many regions the last answered picture carried. libass' own change detection skips
     * frames on a track with no events and compares the next real frame against the last real one,
     * so "went empty" and "came back" are decided here, not there. */
    int last_regions;
} kite_ass;

/* libass prints every message below level 5 to stderr unless told otherwise. A player's stderr is
 * nobody's log, and a broken script would otherwise spam it per event. */
static void kite_ass_quiet(int level, const char *fmt, va_list args, void *data) {
    (void) level; (void) fmt; (void) args; (void) data;
}

static inline void kite_ass_apply_fonts(kite_ass *self) {
    /* Provider 1 is ASS_FONTPROVIDER_AUTODETECT: CoreText on Apple, DirectWrite on Windows,
     * nothing on Android and the Linux chain, which is why fonts arrive through kite_ass_add_font
     * there. Memory fonts are taken up by this call, so it runs again after a batch of them. */
    ass_set_fonts(self->renderer, NULL, "sans-serif", ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
    self->fonts_dirty = 0;
    self->force_next = 1;
}

static inline kite_ass *kite_ass_open(void) {
    kite_ass *self = (kite_ass *) calloc(1, sizeof(kite_ass));
    if (!self) return NULL;
    self->library = ass_library_init();
    if (!self->library) { free(self); return NULL; }
    ass_set_message_cb(self->library, kite_ass_quiet, NULL);
    /* Fonts embedded in the script's own [Fonts] section are decoded and used. */
    ass_set_extract_fonts(self->library, 1);
    self->renderer = ass_renderer_init(self->library);
    if (!self->renderer) { ass_library_done(self->library); free(self); return NULL; }
    self->font_scale = 1.0;
    self->line_position = 0.0;
    kite_ass_apply_fonts(self);
    return self;
}

static inline void kite_ass_close(kite_ass *self) {
    if (!self) return;
    if (self->track) ass_free_track(self->track);
    if (self->renderer) ass_renderer_done(self->renderer);
    if (self->library) ass_library_done(self->library);
    free(self->packed);
    free(self);
}

static inline void kite_ass_drop_track(kite_ass *self) {
    if (self->track) {
        ass_free_track(self->track);
        self->track = NULL;
    }
    self->force_next = 1;
}

/* A fresh track from a container header. An empty header is legal: events then render with
 * libass' default style, which beats rendering nothing. Returns 0 only on allocation failure. */
static inline int kite_ass_open_track(kite_ass *self, const char *header, int size) {
    if (!self) return 0;
    kite_ass_drop_track(self);
    self->track = ass_new_track(self->library);
    if (!self->track) return 0;
    if (header && size > 0) ass_process_codec_private(self->track, header, size);
    return 1;
}

/* A fresh track from a whole script. ass_read_memory writes into its buffer while parsing, so the
 * caller's bytes are copied first; the track keeps its own strings and the copy is freed. */
static inline int kite_ass_open_document(kite_ass *self, const char *script, size_t size) {
    if (!self || !script) return 0;
    kite_ass_drop_track(self);
    char *copy = (char *) malloc(size + 1);
    if (!copy) return 0;
    memcpy(copy, script, size);
    copy[size] = '\0';
    self->track = ass_read_memory(self->library, copy, size, NULL);
    free(copy);
    return self->track != NULL;
}

/* One Matroska-form event. Duplicates by ReadOrder are dropped by libass itself, which is what
 * makes re-feeding after a seek safe. */
static inline void kite_ass_add_event(kite_ass *self, const char *data, int size,
                                      long long start_ms, long long duration_ms) {
    if (!self || !self->track || !data || size <= 0) return;
    ass_process_chunk(self->track, data, size, start_ms, duration_ms);
}

static inline void kite_ass_clear_events(kite_ass *self) {
    if (!self || !self->track) return;
    ass_flush_events(self->track);
    self->force_next = 1;
}

static inline void kite_ass_add_font(kite_ass *self, const char *name, const char *data, int size) {
    if (!self || !data || size <= 0) return;
    ass_add_font(self->library, name ? name : "", data, size);
    self->fonts_dirty = 1;
}

/*
 * The geometry of the next render. frame is the whole output surface, storage the video's own
 * size, and the margins are the bars between the two; libass places events against the video and
 * answers positions in frame pixels, which is exactly the overlay space the engine composites in.
 * line_position is the engine's fraction (1.0 = authored bottom, 0.5 = mid-screen); libass takes
 * a percentage upward from the bottom.
 */
static inline void kite_ass_set_frame(kite_ass *self, int frame_w, int frame_h,
                                      int storage_w, int storage_h,
                                      int margin_t, int margin_b, int margin_l, int margin_r,
                                      double font_scale, double line_position) {
    if (!self || frame_w <= 0 || frame_h <= 0) return;
    if (self->frame_w != frame_w || self->frame_h != frame_h) {
        ass_set_frame_size(self->renderer, frame_w, frame_h);
        self->frame_w = frame_w;
        self->frame_h = frame_h;
        self->force_next = 1;
    }
    if (self->storage_w != storage_w || self->storage_h != storage_h) {
        ass_set_storage_size(self->renderer, storage_w > 0 ? storage_w : 0, storage_h > 0 ? storage_h : 0);
        self->storage_w = storage_w;
        self->storage_h = storage_h;
        self->force_next = 1;
    }
    if (self->margin_t != margin_t || self->margin_b != margin_b ||
        self->margin_l != margin_l || self->margin_r != margin_r) {
        ass_set_margins(self->renderer, margin_t, margin_b, margin_l, margin_r);
        ass_set_use_margins(self->renderer, 0);
        self->margin_t = margin_t; self->margin_b = margin_b;
        self->margin_l = margin_l; self->margin_r = margin_r;
        self->force_next = 1;
    }
    if (self->font_scale != font_scale) {
        ass_set_font_scale(self->renderer, font_scale > 0.0 ? font_scale : 1.0);
        self->font_scale = font_scale;
        self->force_next = 1;
    }
    double percent = (1.0 - line_position) * 100.0;
    if (percent < 0.0) percent = 0.0;
    if (percent > 100.0) percent = 100.0;
    if (self->line_position != percent) {
        ass_set_line_position(self->renderer, percent);
        self->line_position = percent;
        self->force_next = 1;
    }
}

static inline void kite_ass_put_int(unsigned char *at, int32_t value) {
    memcpy(at, &value, sizeof(int32_t));
}

/* Packs the image list into self->packed. Returns 0 when the total would pass the ceiling. */
static inline int kite_ass_pack(kite_ass *self, ASS_Image *image) {
    int count = 0;
    size_t pixel_bytes = 0;
    size_t header_bytes = sizeof(int32_t);
    for (ASS_Image *at = image; at; at = at->next) {
        if (at->w <= 0 || at->h <= 0 || !at->bitmap) continue;
        size_t bytes = kite_region_bytes(at->w, at->h);
        if (bytes == 0 ||
            kite_add_passes_ceiling(pixel_bytes, bytes) ||
            kite_add_passes_ceiling(header_bytes + pixel_bytes, 5u * sizeof(int32_t))) {
            return 0;
        }
        pixel_bytes += bytes;
        header_bytes += 5u * sizeof(int32_t);
        count++;
    }
    size_t total = header_bytes + pixel_bytes;
    if (total > self->packed_capacity) {
        unsigned char *grown = (unsigned char *) realloc(self->packed, total);
        if (!grown) return 0;
        self->packed = grown;
        self->packed_capacity = total;
    }
    unsigned char *packed = self->packed;
    kite_ass_put_int(packed, count);
    size_t header_at = sizeof(int32_t);
    size_t pixel_at = header_bytes;
    for (ASS_Image *at = image; at; at = at->next) {
        if (at->w <= 0 || at->h <= 0 || !at->bitmap) continue;
        int width = at->w, height = at->h, stride = at->stride;
        uint32_t color = at->color;
        int red = (int) ((color >> 24) & 0xFF);
        int green = (int) ((color >> 16) & 0xFF);
        int blue = (int) ((color >> 8) & 0xFF);
        int opacity = 255 - (int) (color & 0xFF);

        kite_ass_put_int(packed + header_at, at->dst_x);  header_at += sizeof(int32_t);
        kite_ass_put_int(packed + header_at, at->dst_y);  header_at += sizeof(int32_t);
        kite_ass_put_int(packed + header_at, width);      header_at += sizeof(int32_t);
        kite_ass_put_int(packed + header_at, height);     header_at += sizeof(int32_t);
        /* In size_t: the counting pass proved this fits an int32, and int arithmetic on the way
         * there would be signed overflow. */
        kite_ass_put_int(packed + header_at, (int32_t) ((size_t) width * (size_t) height * 4u));
        header_at += sizeof(int32_t);

        for (int row = 0; row < height; row++) {
            const unsigned char *source = at->bitmap + (size_t) row * (size_t) stride;
            for (int column = 0; column < width; column++) {
                int coverage = source[column];
                int alpha = (coverage * opacity) / 255;
                packed[pixel_at++] = (unsigned char) ((red * alpha) / 255);
                packed[pixel_at++] = (unsigned char) ((green * alpha) / 255);
                packed[pixel_at++] = (unsigned char) ((blue * alpha) / 255);
                packed[pixel_at++] = (unsigned char) alpha;
            }
        }
    }
    self->packed_size = total;
    return 1;
}

/*
 * Renders at now_ms. Answers 1 with the packed buffer when the picture changed (an empty picture
 * is a changed picture with zero regions), 0 when it is what it was, -1 when the packed buffer
 * could not be built. With no track open the answer is an empty picture once, then unchanged.
 */
static inline int kite_ass_render(kite_ass *self, long long now_ms,
                                  const unsigned char **out, int *out_size) {
    if (!self || !out || !out_size) return -1;
    if (self->fonts_dirty) kite_ass_apply_fonts(self);
    ASS_Image *image = NULL;
    int change = 0;
    if (self->track && self->frame_w > 0 && self->frame_h > 0) {
        image = ass_render_frame(self->renderer, self->track, now_ms, &change);
    }
    /* An empty picture after a full one, or the reverse, is a change whatever libass says: see
     * last_regions. Two empty pictures in a row are not. */
    if (image == NULL && self->last_regions > 0) change = 2;
    if (image != NULL && self->last_regions == 0 && self->has_rendered) change = 2;
    if (image == NULL && self->has_rendered && self->last_regions == 0 && !self->force_next) return 0;
    if (self->has_rendered && !self->force_next && change == 0) return 0;
    if (!kite_ass_pack(self, image)) return -1;
    self->has_rendered = 1;
    self->force_next = 0;
    {
        int32_t regions;
        memcpy(&regions, self->packed, sizeof(regions));
        self->last_regions = regions;
    }
    *out = self->packed;
    *out_size = (int) self->packed_size;
    return 1;
}

#endif /* KITE_ASS_H */

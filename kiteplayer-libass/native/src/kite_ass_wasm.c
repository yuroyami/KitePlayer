/*
 * The web export table of :kiteplayer-libass, linked by emcc into kiteass.mjs.
 *
 * Every function is a thin exported wrapper around kite_ass.h, which owns the libass calls and the
 * packed-buffer conversion for every binding of this module; nothing about pixels is decided here.
 * The Kotlin/Wasm side calls these through the module object and moves bytes across as Latin-1
 * strings in bulk, the same crossing the codec binding uses, because Kotlin/Wasm has no typed-array
 * bridge.
 *
 * Times cross as doubles, not 64-bit integers: a 64-bit integer across a JavaScript function
 * boundary needs the big-integer build flag and arrives as a BigInt, and a silent truncation there
 * would corrupt every timestamp. A double holds every millisecond a film can have exactly.
 */
#include <emscripten/emscripten.h>
#include <stdlib.h>

#include "kite_ass.h"

EMSCRIPTEN_KEEPALIVE int kass_library_version(void) {
    return ass_library_version();
}

EMSCRIPTEN_KEEPALIVE kite_ass *kass_open(void) {
    return kite_ass_open();
}

EMSCRIPTEN_KEEPALIVE void kass_close(kite_ass *self) {
    kite_ass_close(self);
}

/* Scratch memory the Kotlin side fills before a call and frees after it. */
EMSCRIPTEN_KEEPALIVE unsigned char *kass_alloc(int size) {
    return size > 0 ? (unsigned char *) malloc((size_t) size) : NULL;
}

EMSCRIPTEN_KEEPALIVE void kass_free(unsigned char *block) {
    free(block);
}

EMSCRIPTEN_KEEPALIVE int kass_open_track(kite_ass *self, const char *header, int size) {
    return kite_ass_open_track(self, header, size);
}

EMSCRIPTEN_KEEPALIVE int kass_open_document(kite_ass *self, const char *script, int size) {
    return size > 0 ? kite_ass_open_document(self, script, (size_t) size) : 0;
}

EMSCRIPTEN_KEEPALIVE void kass_add_event(kite_ass *self, const char *data, int size,
                                         double start_ms, double duration_ms) {
    kite_ass_add_event(self, data, size, (long long) start_ms, (long long) duration_ms);
}

EMSCRIPTEN_KEEPALIVE void kass_clear_events(kite_ass *self) {
    kite_ass_clear_events(self);
}

EMSCRIPTEN_KEEPALIVE void kass_add_font(kite_ass *self, const char *name, const char *data, int size) {
    kite_ass_add_font(self, name, data, size);
}

EMSCRIPTEN_KEEPALIVE void kass_set_frame(kite_ass *self, int frame_w, int frame_h,
                                         int storage_w, int storage_h,
                                         int margin_t, int margin_b, int margin_l, int margin_r,
                                         double font_scale, double line_position) {
    kite_ass_set_frame(self, frame_w, frame_h, storage_w, storage_h,
                       margin_t, margin_b, margin_l, margin_r, font_scale, line_position);
}

/* 1 changed (read kass_packed_ptr/size), 0 unchanged, -1 the buffer could not be built. */
EMSCRIPTEN_KEEPALIVE int kass_render(kite_ass *self, double now_ms) {
    const unsigned char *packed = NULL;
    int size = 0;
    return kite_ass_render(self, (long long) now_ms, &packed, &size);
}

EMSCRIPTEN_KEEPALIVE const unsigned char *kass_packed_ptr(kite_ass *self) {
    return self ? self->packed : NULL;
}

EMSCRIPTEN_KEEPALIVE int kass_packed_size(kite_ass *self) {
    return self ? (int) self->packed_size : 0;
}

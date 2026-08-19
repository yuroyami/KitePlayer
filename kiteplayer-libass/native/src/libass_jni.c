/*
 * The JNI half of :kiteplayer-libass, for the JVM targets (Android first, desktop next).
 *
 * The Kotlin/Native targets reach libass through cinterop and need none of this. A JVM target
 * cannot: it needs a shared library with C entry points, so this file mirrors what
 * LibassRenderer.kt does on the native side, and mirrors it deliberately closely so the two can be
 * read against each other.
 *
 * ONE call crosses the boundary per rendered frame, returning ONE byte array. The alternative
 * shapes all cost more: an object per region means a JNI allocation per region, and a two-call
 * "give me the metadata, now give me the pixels" split makes the renderer stateful for no gain.
 * The packed layout is documented on renderPacked below and parsed by LibassRenderer.android.kt.
 */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <ass/ass.h>

#include "libass_pack_limits.h"

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
} KiteLibass;

/* Everything in the packed buffer is a native-order int32, which is what ByteBuffer's
 * nativeOrder() reads on the other side. */
static void put_int(unsigned char *at, int32_t value) {
    memcpy(at, &value, sizeof(int32_t));
}

JNIEXPORT jlong JNICALL
Java_io_github_yuroyami_kiteplayer_libass_LibassNative_open(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    KiteLibass *self = calloc(1, sizeof(KiteLibass));
    if (!self) return 0;
    self->library = ass_library_init();
    if (!self->library) { free(self); return 0; }
    self->renderer = ass_renderer_init(self->library);
    if (!self->renderer) { ass_library_done(self->library); free(self); return 0; }
    /* Provider 1 is ASS_FONTPROVIDER_AUTODETECT, exactly as the Kotlin/Native half asks for. On
     * Android that finds nothing by itself: this chain carries no fontconfig, so the fonts a
     * caller adds through addFont are the only ones there are. */
    ass_set_fonts(self->renderer, NULL, "sans-serif", 1, NULL, 1);
    return (jlong) (intptr_t) self;
}

JNIEXPORT void JNICALL
Java_io_github_yuroyami_kiteplayer_libass_LibassNative_addFont(
        JNIEnv *env, jclass clazz, jlong handle, jstring name, jbyteArray data) {
    (void) clazz;
    KiteLibass *self = (KiteLibass *) (intptr_t) handle;
    if (!self || !data) return;
    /* A NULL from either of these leaves an OutOfMemoryError pending, and every further JNI call
     * made in that state is undefined behaviour, so each one returns rather than continues. */
    const char *utf = NULL;
    if (name) {
        utf = (*env)->GetStringUTFChars(env, name, NULL);
        if (!utf) return;
    }
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) {
        if (utf) (*env)->ReleaseStringUTFChars(env, name, utf);
        return;
    }
    ass_add_font(self->library, utf ? utf : "", (const char *) bytes, (int) size);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (utf) (*env)->ReleaseStringUTFChars(env, name, utf);
}

/*
 * Renders one document and returns it packed, or NULL when nothing is visible.
 *
 * Layout, all int32 in native order:
 *   int32 regionCount
 *   regionCount x { int32 x, y, w, h, pixelByteCount }
 *   the pixel blobs, back to back, each already premultiplied RGBA
 *
 * The premultiplication and the inverted alpha are NOT incidental: libass colour is RRGGBBAA with
 * AA as TRANSPARENCY, so 0 means opaque, and RgbaBitmap documents premultiplied bytes since the
 * 2026-08-17 audit unified that contract. Both conversions happen here so the Kotlin side of the
 * JVM path and the Kotlin/Native path hand the engine identical pixels.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_yuroyami_kiteplayer_libass_LibassNative_renderPacked(
        JNIEnv *env, jclass clazz, jlong handle, jstring script, jlong timeMillis,
        jint frameWidth, jint frameHeight) {
    (void) clazz;
    KiteLibass *self = (KiteLibass *) (intptr_t) handle;
    if (!self || !script || frameWidth <= 0 || frameHeight <= 0) return NULL;

    const char *utf = (*env)->GetStringUTFChars(env, script, NULL);
    if (!utf) return NULL;
    /* ass_read_memory takes a mutable buffer and may modify it, so it gets its own copy. */
    size_t length = strlen(utf);
    char *copy = malloc(length + 1);
    if (!copy) { (*env)->ReleaseStringUTFChars(env, script, utf); return NULL; }
    memcpy(copy, utf, length + 1);
    (*env)->ReleaseStringUTFChars(env, script, utf);

    ASS_Track *track = ass_read_memory(self->library, copy, length, NULL);
    free(copy);
    if (!track) return NULL;

    ass_set_frame_size(self->renderer, frameWidth, frameHeight);
    ASS_Image *image = ass_render_frame(self->renderer, track, (long long) timeMillis, NULL);

    /* One pass to size the buffer, one to fill it. Counting first keeps this to a single
     * allocation and a single JNI array, which is the whole reason for the packed shape. */
    int count = 0;
    size_t pixelBytes = 0;
    size_t headerBytes = sizeof(int32_t);
    for (ASS_Image *at = image; at; at = at->next) {
        if (at->w <= 0 || at->h <= 0 || !at->bitmap) continue;
        size_t bytes = kite_region_bytes(at->w, at->h);
        if (bytes == 0 ||
            kite_add_passes_ceiling(pixelBytes, bytes) ||
            kite_add_passes_ceiling(headerBytes + pixelBytes, 5u * sizeof(int32_t))) {
            ass_free_track(track);
            return NULL;
        }
        pixelBytes += bytes;
        headerBytes += 5u * sizeof(int32_t);
        count++;
    }
    if (count == 0) { ass_free_track(track); return NULL; }

    size_t totalBytes = headerBytes + pixelBytes;
    unsigned char *packed = malloc(totalBytes);
    if (!packed) { ass_free_track(track); return NULL; }

    put_int(packed, count);
    size_t headerAt = sizeof(int32_t);
    size_t pixelAt = headerBytes;
    for (ASS_Image *at = image; at; at = at->next) {
        if (at->w <= 0 || at->h <= 0 || !at->bitmap) continue;
        int width = at->w, height = at->h, stride = at->stride;
        uint32_t color = at->color;
        int red = (int) ((color >> 24) & 0xFF);
        int green = (int) ((color >> 16) & 0xFF);
        int blue = (int) ((color >> 8) & 0xFF);
        int opacity = 255 - (int) (color & 0xFF);

        put_int(packed + headerAt, at->dst_x);        headerAt += sizeof(int32_t);
        put_int(packed + headerAt, at->dst_y);        headerAt += sizeof(int32_t);
        put_int(packed + headerAt, width);            headerAt += sizeof(int32_t);
        put_int(packed + headerAt, height);           headerAt += sizeof(int32_t);
        /* Computed in size_t, not int: the counting pass proved this fits in an int32, and
         * `width * height * 4` in int arithmetic would be signed overflow on the way there. */
        put_int(packed + headerAt, (int32_t) ((size_t) width * (size_t) height * 4u));
        headerAt += sizeof(int32_t);

        for (int row = 0; row < height; row++) {
            const unsigned char *source = at->bitmap + (size_t) row * (size_t) stride;
            for (int column = 0; column < width; column++) {
                int coverage = source[column];
                int alpha = (coverage * opacity) / 255;
                packed[pixelAt++] = (unsigned char) ((red * alpha) / 255);
                packed[pixelAt++] = (unsigned char) ((green * alpha) / 255);
                packed[pixelAt++] = (unsigned char) ((blue * alpha) / 255);
                packed[pixelAt++] = (unsigned char) alpha;
            }
        }
    }
    ass_free_track(track);

    /* totalBytes is at or below INT32_MAX by construction, so the cast is exact. A NULL here
     * means a pending OutOfMemoryError, which the caller must be allowed to see. */
    jbyteArray result = (*env)->NewByteArray(env, (jsize) totalBytes);
    if (!result) { free(packed); return NULL; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) totalBytes, (const jbyte *) packed);
    free(packed);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_yuroyami_kiteplayer_libass_LibassNative_close(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    KiteLibass *self = (KiteLibass *) (intptr_t) handle;
    if (!self) return;
    if (self->renderer) ass_renderer_done(self->renderer);
    if (self->library) ass_library_done(self->library);
    free(self);
}

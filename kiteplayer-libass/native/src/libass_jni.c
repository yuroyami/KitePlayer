/*
 * The JNI half of :kiteplayer-libass, for Android and the desktop JVM.
 *
 * Every method here is argument marshalling around kite_ass.h, which owns the libass calls and the
 * packed-buffer conversion for every binding of this module. Nothing about pixels is decided in
 * this file, on purpose: the Kotlin/Native and web bindings include the same header, so the three
 * cannot disagree on what a frame looks like.
 *
 * ONE call renders one frame and returns ONE byte array, the driver's packed buffer copied into a
 * Java array. Null means "unchanged since the last render"; an empty array means the driver could
 * not build the buffer, which the Kotlin side turns into an exception. A NULL from any JNI
 * allocation leaves an OutOfMemoryError pending and every further JNI call is then undefined, so
 * each one returns rather than continues.
 */
#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#include "kite_ass.h"

#define KITE_JNI(name) Java_io_github_yuroyami_kiteplayer_libass_LibassNative_##name

JNIEXPORT jlong JNICALL KITE_JNI(open)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jlong) (intptr_t) kite_ass_open();
}

JNIEXPORT void JNICALL KITE_JNI(close)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    kite_ass_close((kite_ass *) (intptr_t) handle);
}

/* Borrows a byte array for the duration of one driver call; the driver copies what it keeps. */
typedef struct {
    jbyteArray array;
    jbyte *bytes;
    jsize size;
} kite_borrowed;

static int kite_borrow(JNIEnv *env, jbyteArray array, kite_borrowed *out) {
    out->array = array;
    out->bytes = NULL;
    out->size = 0;
    if (!array) return 1;
    out->size = (*env)->GetArrayLength(env, array);
    out->bytes = (*env)->GetByteArrayElements(env, array, NULL);
    return out->bytes != NULL;
}

static void kite_release(JNIEnv *env, kite_borrowed *borrowed) {
    if (borrowed->bytes) (*env)->ReleaseByteArrayElements(env, borrowed->array, borrowed->bytes, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL KITE_JNI(openTrack)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray header) {
    (void) clazz;
    kite_ass *self = (kite_ass *) (intptr_t) handle;
    kite_borrowed borrowed;
    if (!self || !kite_borrow(env, header, &borrowed)) return JNI_FALSE;
    int ok = kite_ass_open_track(self, (const char *) borrowed.bytes, (int) borrowed.size);
    kite_release(env, &borrowed);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL KITE_JNI(openDocument)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray script) {
    (void) clazz;
    kite_ass *self = (kite_ass *) (intptr_t) handle;
    kite_borrowed borrowed;
    if (!self || !script || !kite_borrow(env, script, &borrowed)) return JNI_FALSE;
    int ok = kite_ass_open_document(self, (const char *) borrowed.bytes, (size_t) borrowed.size);
    kite_release(env, &borrowed);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL KITE_JNI(addEvent)(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray payload, jlong startMillis, jlong durationMillis) {
    (void) clazz;
    kite_ass *self = (kite_ass *) (intptr_t) handle;
    kite_borrowed borrowed;
    if (!self || !payload || !kite_borrow(env, payload, &borrowed)) return;
    kite_ass_add_event(self, (const char *) borrowed.bytes, (int) borrowed.size,
                       (long long) startMillis, (long long) durationMillis);
    kite_release(env, &borrowed);
}

JNIEXPORT void JNICALL KITE_JNI(clearEvents)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    kite_ass_clear_events((kite_ass *) (intptr_t) handle);
}

JNIEXPORT void JNICALL KITE_JNI(addFont)(
        JNIEnv *env, jclass clazz, jlong handle, jstring name, jbyteArray data) {
    (void) clazz;
    kite_ass *self = (kite_ass *) (intptr_t) handle;
    if (!self || !data) return;
    const char *utf = NULL;
    if (name) {
        utf = (*env)->GetStringUTFChars(env, name, NULL);
        if (!utf) return;
    }
    kite_borrowed borrowed;
    if (kite_borrow(env, data, &borrowed)) {
        kite_ass_add_font(self, utf ? utf : "", (const char *) borrowed.bytes, (int) borrowed.size);
        kite_release(env, &borrowed);
    }
    if (utf) (*env)->ReleaseStringUTFChars(env, name, utf);
}

JNIEXPORT void JNICALL KITE_JNI(setFrame)(
        JNIEnv *env, jclass clazz, jlong handle,
        jint frameWidth, jint frameHeight, jint storageWidth, jint storageHeight,
        jint marginTop, jint marginBottom, jint marginLeft, jint marginRight,
        jdouble fontScale, jdouble linePosition) {
    (void) env; (void) clazz;
    kite_ass_set_frame((kite_ass *) (intptr_t) handle, frameWidth, frameHeight, storageWidth, storageHeight,
                       marginTop, marginBottom, marginLeft, marginRight, fontScale, linePosition);
}

JNIEXPORT jbyteArray JNICALL KITE_JNI(render)(JNIEnv *env, jclass clazz, jlong handle, jlong nowMillis) {
    (void) clazz;
    kite_ass *self = (kite_ass *) (intptr_t) handle;
    if (!self) return NULL;
    const unsigned char *packed = NULL;
    int size = 0;
    int verdict = kite_ass_render(self, (long long) nowMillis, &packed, &size);
    if (verdict == 0) return NULL;
    if (verdict < 0) return (*env)->NewByteArray(env, 0);
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (!result) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) packed);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return result;
}

/* The libass version the adapter was linked against, for the support bundle. */
JNIEXPORT jint JNICALL KITE_JNI(libraryVersion)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return (jint) ass_library_version();
}

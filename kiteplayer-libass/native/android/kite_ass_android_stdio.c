/*
 * Merged into libkiteass.a for the Android native targets only, never into the JNI library.
 *
 * Kotlin/Native links Android binaries against the libc of API 21. The chain is built for API 24,
 * where `stdout` and `stderr` are variables of libc (API 23 added them). The API 21 libc has only the
 * array `__sF`, so this file defines the two variables the way bionic does: they point into it.
 * The NDK headers hide the layout of FILE, but bionic keeps its size fixed for this array:
 * 152 bytes on 64-bit and 84 bytes on 32-bit.
 *
 * KiteFFmpeg's Android klibs carry the same two definitions. Both files define the same pair, so a
 * link that sees both archives takes one of them and never both.
 */
#include <stdio.h>

#undef stdout
#undef stderr

#if defined(__LP64__)
#define KITE_ASS_BIONIC_FILE_SIZE 152
#else
#define KITE_ASS_BIONIC_FILE_SIZE 84
#endif

extern unsigned char kite_ass_bionic_sF[] __asm__("__sF");

FILE *stdout = (FILE *) (kite_ass_bionic_sF + KITE_ASS_BIONIC_FILE_SIZE);
FILE *stderr = (FILE *) (kite_ass_bionic_sF + 2 * KITE_ASS_BIONIC_FILE_SIZE);

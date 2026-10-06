/*
 * An ASS script's colours, matched to the video through its "YCbCr Matrix" header (#499).
 *
 * Typesetters colour a sign to blend into the picture by picking the colour from a frame decoded
 * with some matrix, and the header names that matrix and its range. VSFilter drew a colour by
 * turning it into the video's YCbCr with the header's matrix, and the video was then shown with
 * its own, so a script stays matched only if a player does the same:
 *
 *   screen_rgb = video_to_rgb(rgb_to_header(ass_rgb))
 *
 * A missing header is an old VSFilter script and means BT.601 at studio range, "None" means the
 * colours stand as they are, and a header nobody can read converts nothing, as libass's own notes
 * on the header recommend. The YCbCr values in between are rounded to 8-bit steps, as they were in
 * the video the colour was picked from.
 *
 * A header of its own so a suite can prove it with no renderer present, as libass_pack_limits.h
 * is. The header codes are libass's ASS_YCbCrMatrix values, which kite_ass.h checks at compile
 * time, and the video codes are this driver's own.
 */

#ifndef KITE_ASS_COLOR_H
#define KITE_ASS_COLOR_H

#include <stdint.h>

/* libass's ASS_YCbCrMatrix, which the driver passes in as it is. */
enum {
    KITE_HEADER_DEFAULT = 0,
    KITE_HEADER_UNKNOWN = 1,
    KITE_HEADER_NONE = 2,
    KITE_HEADER_BT601_TV = 3,
    KITE_HEADER_BT601_PC = 4,
    KITE_HEADER_BT709_TV = 5,
    KITE_HEADER_BT709_PC = 6,
    KITE_HEADER_SMPTE240M_TV = 7,
    KITE_HEADER_SMPTE240M_PC = 8,
    KITE_HEADER_FCC_TV = 9,
    KITE_HEADER_FCC_PC = 10,
};

/* The video's matrix. KITE_VIDEO_NONE matches nothing: the engine says so for HDR, RGB and the rest. */
enum {
    KITE_VIDEO_NONE = 0,
    KITE_VIDEO_BT601 = 1,
    KITE_VIDEO_BT709 = 2,
    KITE_VIDEO_FCC = 3,
    KITE_VIDEO_SMPTE240M = 4,
    KITE_VIDEO_BT2020 = 5,
};

/* The red and blue weights of one matrix, and whether its range is full. */
typedef struct {
    double kr, kb;
    int full;
} kite_matrix;

/* The matrix a header names, or 0 when it names none to convert through. */
static inline int kite_header_matrix(int header, kite_matrix *out) {
    switch (header) {
        case KITE_HEADER_DEFAULT:
        case KITE_HEADER_BT601_TV:     *out = (kite_matrix) { 0.299, 0.114, 0 }; return 1;
        case KITE_HEADER_BT601_PC:     *out = (kite_matrix) { 0.299, 0.114, 1 }; return 1;
        case KITE_HEADER_BT709_TV:     *out = (kite_matrix) { 0.2126, 0.0722, 0 }; return 1;
        case KITE_HEADER_BT709_PC:     *out = (kite_matrix) { 0.2126, 0.0722, 1 }; return 1;
        case KITE_HEADER_SMPTE240M_TV: *out = (kite_matrix) { 0.212, 0.087, 0 }; return 1;
        case KITE_HEADER_SMPTE240M_PC: *out = (kite_matrix) { 0.212, 0.087, 1 }; return 1;
        case KITE_HEADER_FCC_TV:       *out = (kite_matrix) { 0.30, 0.11, 0 }; return 1;
        case KITE_HEADER_FCC_PC:       *out = (kite_matrix) { 0.30, 0.11, 1 }; return 1;
        default: return 0;
    }
}

/* The video's matrix at [full] range, or 0 for none. */
static inline int kite_video_matrix(int video, int full, kite_matrix *out) {
    switch (video) {
        case KITE_VIDEO_BT601:     *out = (kite_matrix) { 0.299, 0.114, full != 0 }; return 1;
        case KITE_VIDEO_BT709:     *out = (kite_matrix) { 0.2126, 0.0722, full != 0 }; return 1;
        case KITE_VIDEO_FCC:       *out = (kite_matrix) { 0.30, 0.11, full != 0 }; return 1;
        case KITE_VIDEO_SMPTE240M: *out = (kite_matrix) { 0.212, 0.087, full != 0 }; return 1;
        case KITE_VIDEO_BT2020:    *out = (kite_matrix) { 0.2627, 0.0593, full != 0 }; return 1;
        default: return 0;
    }
}

/* [value] rounded to the nearest whole step and held to 0..255, with no math library. */
static inline int kite_color_step(double value) {
    if (!(value > 0.0)) return 0;
    if (value >= 255.0) return 255;
    return (int) (value + 0.5);
}

/*
 * libass colour [rgba], RRGGBBAA with AA its transparency, matched from [header] to the video's
 * [video] matrix at [full] range. The transparency passes through. A header or video that names no
 * matrix, and a video whose matrix and range are the header's own, leave the colour as it is.
 */
static inline uint32_t kite_ass_match_color(uint32_t rgba, int header, int video, int full) {
    kite_matrix from, to;
    if (!kite_header_matrix(header, &from) || !kite_video_matrix(video, full, &to)) return rgba;
    if (from.kr == to.kr && from.kb == to.kb && from.full == to.full) return rgba;

    double r = (double) ((rgba >> 24) & 0xFF) / 255.0;
    double g = (double) ((rgba >> 16) & 0xFF) / 255.0;
    double b = (double) ((rgba >> 8) & 0xFF) / 255.0;

    /* Into the 8-bit YCbCr the header's matrix and range make of it. */
    double y = from.kr * r + (1.0 - from.kr - from.kb) * g + from.kb * b;
    double cb = (b - y) / (2.0 * (1.0 - from.kb));
    double cr = (r - y) / (2.0 * (1.0 - from.kr));
    double luma_scale = from.full ? 255.0 : 219.0, luma_floor = from.full ? 0.0 : 16.0;
    double chroma_scale = from.full ? 255.0 : 224.0;
    int y8 = kite_color_step(luma_floor + luma_scale * y);
    int cb8 = kite_color_step(128.0 + chroma_scale * cb);
    int cr8 = kite_color_step(128.0 + chroma_scale * cr);

    /* And back out as the video's matrix and range read those same values. */
    luma_scale = to.full ? 255.0 : 219.0;
    luma_floor = to.full ? 0.0 : 16.0;
    chroma_scale = to.full ? 255.0 : 224.0;
    y = ((double) y8 - luma_floor) / luma_scale;
    cb = ((double) cb8 - 128.0) / chroma_scale;
    cr = ((double) cr8 - 128.0) / chroma_scale;
    r = y + 2.0 * (1.0 - to.kr) * cr;
    b = y + 2.0 * (1.0 - to.kb) * cb;
    g = (y - to.kr * r - to.kb * b) / (1.0 - to.kr - to.kb);

    uint32_t red = (uint32_t) kite_color_step(r * 255.0);
    uint32_t green = (uint32_t) kite_color_step(g * 255.0);
    uint32_t blue = (uint32_t) kite_color_step(b * 255.0);
    return (red << 24) | (green << 16) | (blue << 8) | (rgba & 0xFFu);
}

#endif /* KITE_ASS_COLOR_H */

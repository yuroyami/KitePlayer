/*
 * An ASS colour matched to the video through the script's YCbCr Matrix header (#499).
 *
 * The expected colours come from the ffmpeg command line, not from this arithmetic: one RGB pixel
 * converted to yuv444p with the header's matrix and range, then read back as rgb24 with the video's,
 * both with swscale's accurate rounding. That is the path VSFilter's colours took into the video
 * and out to the screen. For example:
 *
 *   ffmpeg -f rawvideo -pixel_format rgb24 -video_size 1x1 -i px.rgb \
 *     -vf scale=out_color_matrix=bt601:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv444p ...
 *   ffmpeg -f rawvideo -pixel_format yuv444p -video_size 1x1 -i yuv.raw \
 *     -vf scale=in_color_matrix=bt709:in_range=tv:flags=accurate_rnd+full_chroma_int,format=rgb24 ...
 *
 * Every row matched to the step on ffmpeg 6.1, so the suite asks for exact equality.
 */

#include "harness.h"
#include "kite_ass_color.h"

#include <stdint.h>

typedef struct {
    const char *name;
    uint32_t rgb;
    int header;
    int video;
    int full;
    uint32_t expected;
} color_row;

static const color_row rows[] = {
    { "no header over BT.709: a mid blue",         0x3080c0, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0x287dc4 },
    { "no header over BT.709: pure red",           0xff0000, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0xff1800 },
    { "no header over BT.709: pure green",         0x00ff00, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0x00d800 },
    { "no header over BT.709: pure blue",          0x0000ff, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0x000fff },
    { "no header over BT.709: grey stays grey",    0x808080, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0x808080 },
    { "no header over BT.709: an orange",          0xc06030, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0xc9662d },
    { "no header over BT.709: a dark navy",        0x102030, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0x0f1f30 },
    { "no header over BT.709: a pale beige",       0xf0e0d0, KITE_HEADER_DEFAULT, KITE_VIDEO_BT709, 0, 0xf1e1d0 },
    { "TV.601 over BT.709 is the same as no header", 0x3080c0, KITE_HEADER_BT601_TV, KITE_VIDEO_BT709, 0, 0x287dc4 },
    { "PC.709 over BT.601 at studio range",        0x3080c0, KITE_HEADER_BT709_PC, KITE_VIDEO_BT601, 0, 0x3087c7 },
    { "TV.240M over BT.709",                       0x3080c0, KITE_HEADER_SMPTE240M_TV, KITE_VIDEO_BT709, 0, 0x3081c0 },
    { "TV.FCC over full range BT.709",             0x3080c0, KITE_HEADER_FCC_TV, KITE_VIDEO_BT709, 1, 0x307ab9 },
    { "TV.601 over BT.2020",                       0x3080c0, KITE_HEADER_BT601_TV, KITE_VIDEO_BT2020, 0, 0x2d82c5 },
    /* The cases that leave a colour as authored. ffmpeg's own round trip of TV.601 through itself
     * moves this blue to 0x3081c0, which is why a matching matrix is skipped rather than converted. */
    { "None converts nothing",                     0x3080c0, KITE_HEADER_NONE, KITE_VIDEO_BT709, 0, 0x3080c0 },
    { "an unreadable header converts nothing",     0x3080c0, KITE_HEADER_UNKNOWN, KITE_VIDEO_BT709, 0, 0x3080c0 },
    { "a header the driver does not know",         0x3080c0, 11, KITE_VIDEO_BT709, 0, 0x3080c0 },
    { "no video colour converts nothing",          0x3080c0, KITE_HEADER_DEFAULT, KITE_VIDEO_NONE, 0, 0x3080c0 },
    { "TV.601 over BT.601 at studio range",        0x3080c0, KITE_HEADER_BT601_TV, KITE_VIDEO_BT601, 0, 0x3080c0 },
    { "PC.709 over full range BT.709",             0x3080c0, KITE_HEADER_BT709_PC, KITE_VIDEO_BT709, 1, 0x3080c0 },
};

int main(void) {
    kt_suite_begin("test_ass_color");
    for (size_t i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) {
        const color_row *row = &rows[i];
        kt_case("%s", row->name);
        /* The low byte is libass's transparency, which must pass through untouched. */
        uint32_t got = kite_ass_match_color((row->rgb << 8) | 0x5Au, row->header, row->video, row->full);
        KT_EQ_I64((int64_t) (got >> 8), (int64_t) row->expected);
        KT_EQ_INT((int) (got & 0xFFu), 0x5A);
        kt_detail("%06x -> %06x", (unsigned) row->rgb, (unsigned) (got >> 8));
    }
    return kt_suite_end();
}

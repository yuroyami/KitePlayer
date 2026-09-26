/*
 * The family name of a font file, read from the font's own name table, in a header so a host test
 * can compile it without libass. The same test then runs on wasm32, where size_t is 32 bits wide,
 * like it is on the 32-bit Android ABIs.
 *
 * The font bytes come from the media file's attachments. Every offset and length in them is
 * compared by subtraction only, so no sum of two file values can wrap.
 */

#ifndef KITE_FONT_NAME_H
#define KITE_FONT_NAME_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

static inline uint32_t kite_be32(const unsigned char *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | (uint32_t) p[3];
}

static inline uint16_t kite_be16(const unsigned char *p) {
    return (uint16_t) (((uint16_t) p[0] << 8) | (uint16_t) p[1]);
}

/* True when length bytes from offset lie inside size bytes. It never computes offset + length. */
static inline int kite_ass_within(size_t offset, size_t length, size_t size) {
    return offset <= size && length <= size - offset;
}

/*
 * The family name (name ID 1) of a TrueType, OpenType or collection font, from its own 'name'
 * table, into out (NUL terminated, ASCII only). Returns 0 when the bytes carry none that this
 * reader understands. Windows/Unicode English first, Macintosh Roman second; other platforms and
 * languages are skipped, and a UTF-16 unit above 0xFF is skipped rather than mangled, because the
 * result is matched against the ASCII family names ASS styles carry. Every read stays inside the
 * file, and every name record and string inside the name table as declared, cut at the file's end.
 */
static inline int kite_ass_family_of(const unsigned char *font, size_t size, char *out, size_t out_size) {
    if (!font || size < 12 || out_size < 2) return 0;
    size_t base = 0;
    if (memcmp(font, "ttcf", 4) == 0) {
        if (size < 16) return 0;
        base = kite_be32(font + 12);
        if (!kite_ass_within(base, 12, size)) return 0;
    }
    uint16_t tables = kite_be16(font + base + 4);
    size_t directory = base + 12;
    size_t name_offset = 0, name_length = 0;
    for (uint16_t i = 0; i < tables; i++) {
        size_t record = (size_t) i * 16;
        if (!kite_ass_within(directory, record + 16, size)) return 0;
        const unsigned char *entry = font + directory + record;
        if (memcmp(entry, "name", 4) == 0) {
            name_offset = kite_be32(entry + 8);
            name_length = kite_be32(entry + 12);
            break;
        }
    }
    if (name_offset == 0 || name_offset > size) return 0;
    if (name_length > size - name_offset) name_length = size - name_offset;
    if (name_length < 6) return 0;
    const unsigned char *name = font + name_offset;
    uint16_t count = kite_be16(name + 2);
    size_t strings = kite_be16(name + 4);
    int best = 0;
    for (uint16_t i = 0; i < count; i++) {
        size_t record = 6 + (size_t) i * 12;
        if (!kite_ass_within(record, 12, name_length)) break;
        uint16_t platform = kite_be16(name + record);
        uint16_t encoding = kite_be16(name + record + 2);
        uint16_t language = kite_be16(name + record + 4);
        uint16_t name_id = kite_be16(name + record + 6);
        uint16_t length = kite_be16(name + record + 8);
        size_t offset = strings + kite_be16(name + record + 10);
        if (name_id != 1 || length == 0 || !kite_ass_within(offset, length, name_length)) continue;
        int rank = 0;
        if (platform == 3 && (encoding == 1 || encoding == 0) && (language & 0xFF) == 0x09) rank = 2;
        else if (platform == 1 && encoding == 0) rank = 1;
        if (rank <= best) continue;
        size_t at = 0;
        const unsigned char *text = name + offset;
        if (platform == 3) {
            for (uint16_t u = 0; u + 1 < length && at + 1 < out_size; u += 2) {
                if (text[u] == 0 && text[u + 1] >= 0x20 && text[u + 1] < 0x7F) out[at++] = (char) text[u + 1];
            }
        } else {
            for (uint16_t u = 0; u < length && at + 1 < out_size; u++) {
                if (text[u] >= 0x20 && text[u] < 0x7F) out[at++] = (char) text[u];
            }
        }
        if (at == 0) continue;
        out[at] = '\0';
        best = rank;
    }
    return best > 0;
}

#endif

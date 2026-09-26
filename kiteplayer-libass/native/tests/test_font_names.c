/*
 * The family name reader in kite_font_name.h, against fonts built in memory.
 *
 * Every font here is copied into a heap block of exactly its own size before it is read, so the
 * asan and wasm32 variants report any read outside it. The wasm32 variant matters most: size_t is
 * 32 bits wide there, as on the 32-bit Android ABIs, and the offsets near 0xFFFFFFFF below must be
 * refused there too.
 *
 * A reader that answers 0 must also leave the output buffer as it was; each case checks that too.
 */

#include "harness.h"
#include "kite_font_name.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define FONT_CAP 512

typedef struct {
    uint16_t platform;
    uint16_t encoding;
    uint16_t language;
    uint16_t name_id;
    const char *text;
} name_record;

typedef struct {
    unsigned char bytes[FONT_CAP];
    size_t size;
    /* Where the table directory and the name table start, for cases that corrupt them. */
    size_t directory_at;
    size_t name_at;
} font;

static void put16(unsigned char *at, uint32_t value) {
    at[0] = (unsigned char) (value >> 8);
    at[1] = (unsigned char) value;
}

static void put32(unsigned char *at, uint32_t value) {
    at[0] = (unsigned char) (value >> 24);
    at[1] = (unsigned char) (value >> 16);
    at[2] = (unsigned char) (value >> 8);
    at[3] = (unsigned char) value;
}

/* A font with one table, 'name', holding the given records. Platform 3 text is written as UTF-16BE,
 * every other platform as single bytes. With collection set, a 'ttcf' header for one font comes
 * first, and the font starts at byte 16. */
static void build(font *f, const name_record *records, int count, int collection) {
    memset(f, 0, sizeof(*f));
    size_t base = 0;
    if (collection) {
        memcpy(f->bytes, "ttcf", 4);
        put32(f->bytes + 4, 0x00010000u);
        put32(f->bytes + 8, 1);
        put32(f->bytes + 12, 16);
        base = 16;
    }
    put32(f->bytes + base, 0x00010000u);
    put16(f->bytes + base + 4, 1);
    f->directory_at = base + 12;
    f->name_at = f->directory_at + 16;
    unsigned char *name = f->bytes + f->name_at;
    size_t storage = 6 + (size_t) count * 12;
    size_t used = 0;
    put16(name, 0);
    put16(name + 2, (uint32_t) count);
    put16(name + 4, (uint32_t) storage);
    for (int i = 0; i < count; i++) {
        const name_record *r = &records[i];
        size_t length = strlen(r->text) * (r->platform == 3 ? 2u : 1u);
        unsigned char *record = name + 6 + (size_t) i * 12;
        put16(record, r->platform);
        put16(record + 2, r->encoding);
        put16(record + 4, r->language);
        put16(record + 6, r->name_id);
        put16(record + 8, (uint32_t) length);
        put16(record + 10, (uint32_t) used);
        unsigned char *text = name + storage + used;
        for (size_t c = 0; r->text[c] != '\0'; c++) {
            if (r->platform == 3) {
                text[c * 2] = 0;
                text[c * 2 + 1] = (unsigned char) r->text[c];
            } else {
                text[c] = (unsigned char) r->text[c];
            }
        }
        used += length;
    }
    size_t name_length = storage + used;
    memcpy(f->bytes + f->directory_at, "name", 4);
    put32(f->bytes + f->directory_at + 8, (uint32_t) f->name_at);
    put32(f->bytes + f->directory_at + 12, (uint32_t) name_length);
    f->size = f->name_at + name_length;
}

/* Reads the family from a heap copy of exactly size bytes. out keeps its old bytes when the answer
 * is 0, and the case fails if it does not. */
static int read_family(const unsigned char *bytes, size_t size, char *out, size_t out_size) {
    unsigned char *copy = (unsigned char *) malloc(size > 0 ? size : 1);
    KT_NOT_NULL(copy);
    memcpy(copy, bytes, size);
    char before[64];
    KT_CHECK(out_size <= sizeof(before));
    memcpy(before, out, out_size);
    int found = kite_ass_family_of(copy, size, out, out_size);
    free(copy);
    if (!found) KT_CHECKF(memcmp(before, out, out_size) == 0, "a reader that found nothing wrote into out");
    return found;
}

static const name_record windows_family = { 3, 1, 0x0409, 1, "Kite Sans" };
static const name_record mac_family = { 1, 0, 0, 1, "Kite Mac" };
static const name_record windows_style = { 3, 1, 0x0409, 2, "Regular" };

static void fill(char *out, size_t size) {
    memset(out, 'x', size);
}

int main(void) {
    font f;
    char family[32];

    kt_suite_begin("test_font_names");

    kt_case("a Windows English family is read");
    {
        name_record records[] = { windows_style, windows_family };
        build(&f, records, 2, 0);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Sans") == 0, "read '%s'", family);
        kt_detail("size_t is %u bits", (unsigned) (sizeof(size_t) * 8));
    }

    kt_case("a Macintosh Roman family is read when there is no Windows one");
    {
        name_record records[] = { mac_family };
        build(&f, records, 1, 0);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Mac") == 0, "read '%s'", family);
    }

    kt_case("the Windows English family wins over the Macintosh one in either order");
    {
        name_record first[] = { mac_family, windows_family };
        name_record second[] = { windows_family, mac_family };
        build(&f, first, 2, 0);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Sans") == 0, "read '%s' with the Macintosh record first", family);
        build(&f, second, 2, 0);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Sans") == 0, "read '%s' with the Windows record first", family);
    }

    kt_case("a family inside a collection is read through the collection header");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 1);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Sans") == 0, "read '%s'", family);
    }

    kt_case("an output buffer of two bytes holds one character");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        char small[2] = { 'x', 'x' };
        KT_EQ_INT(read_family(f.bytes, f.size, small, sizeof(small)), 1);
        KT_CHECKF(small[0] == 'K' && small[1] == '\0', "read '%c' then %d", small[0], small[1]);
    }

    kt_case("a collection whose font offset is near the top of 32 bits yields no family");
    {
        static const uint32_t offsets[] = { 0xFFFFFFF4u, 0xFFFFFFFFu, 0xFFFFFFF0u, 0x80000000u };
        for (size_t i = 0; i < sizeof(offsets) / sizeof(offsets[0]); i++) {
            unsigned char bytes[16] = { 't', 't', 'c', 'f', 0, 0, 0, 0, 0, 0, 0, 0 };
            put32(bytes + 12, offsets[i]);
            fill(family, sizeof(family));
            KT_EQ_INT(read_family(bytes, sizeof(bytes), family, sizeof(family)), 0);
        }
        kt_detail("4 offsets");
    }

    kt_case("a collection whose font header runs past the end yields no family");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 1);
        for (uint32_t cut = 0; cut <= 12; cut++) {
            put32(f.bytes + 12, (uint32_t) f.size - cut);
            fill(family, sizeof(family));
            KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
        }
    }

    kt_case("a table count past the end of the file yields no family");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        put16(f.bytes + 4, 0xFFFF);
        memcpy(f.bytes + f.directory_at, "head", 4);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
    }

    kt_case("a name table offset near the top of 32 bits yields no family");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        static const uint32_t offsets[] = { 0xFFFFFFFAu, 0xFFFFFFFFu, 0xFFFFFFF0u, 0x80000000u };
        for (size_t i = 0; i < sizeof(offsets) / sizeof(offsets[0]); i++) {
            put32(f.bytes + f.directory_at + 8, offsets[i]);
            fill(family, sizeof(family));
            KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
        }
        kt_detail("4 offsets");
    }

    kt_case("a name table that starts too near the end yields no family");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        for (uint32_t cut = 0; cut <= 5; cut++) {
            put32(f.bytes + f.directory_at + 8, (uint32_t) f.size - cut);
            fill(family, sizeof(family));
            KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
        }
    }

    kt_case("a name table length past the end of the file is cut at the end");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        put32(f.bytes + f.directory_at + 12, 0xFFFFFFFFu);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Sans") == 0, "read '%s'", family);
    }

    kt_case("a string that runs past the end of the file is skipped");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        put32(f.bytes + f.directory_at + 12, 0xFFFFFFFFu);
        unsigned char *record = f.bytes + f.name_at + 6;
        put16(record + 8, 0xFFFF);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
    }

    kt_case("a string outside the declared name table is skipped, as FreeType skips it");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        put32(f.bytes + f.directory_at + 12, 6 + 12);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
    }

    kt_case("the largest string offset the table can declare stays out of range");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        put32(f.bytes + f.directory_at + 12, 0xFFFFFFFFu);
        put16(f.bytes + f.name_at + 4, 0xFFFF);
        put16(f.bytes + f.name_at + 6 + 10, 0xFFFF);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 0);
    }

    kt_case("a record count past the name table stops at the end of the table");
    {
        name_record records[] = { windows_family };
        build(&f, records, 1, 0);
        put16(f.bytes + f.name_at + 2, 0xFFFF);
        fill(family, sizeof(family));
        KT_EQ_INT(read_family(f.bytes, f.size, family, sizeof(family)), 1);
        KT_CHECKF(strcmp(family, "Kite Sans") == 0, "read '%s'", family);
    }

    kt_case("bytes that are not a font, and no bytes at all, yield no family");
    {
        const char *junk = "not a font at all, and long enough to be read";
        fill(family, sizeof(family));
        KT_EQ_INT(read_family((const unsigned char *) junk, strlen(junk), family, sizeof(family)), 0);
        KT_EQ_INT(kite_ass_family_of(NULL, 0, family, sizeof(family)), 0);
        for (size_t size = 0; size < 16; size++) {
            unsigned char bytes[16] = { 't', 't', 'c', 'f' };
            KT_EQ_INT(read_family(bytes, size, family, sizeof(family)), 0);
        }
    }

    return kt_suite_end();
}

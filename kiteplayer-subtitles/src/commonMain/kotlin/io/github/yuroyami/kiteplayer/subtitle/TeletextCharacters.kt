package io.github.yuroyami.kiteplayer.subtitle

/**
 * The character sets of EBU teletext (ETSI EN 300 706 §15), as libzvbi reads them (#510).
 *
 * Every set below was compared, code by code, with what FFmpeg's libzvbi decoder prints for a page
 * that holds each character, and they agree except where libzvbi draws a lookalike or a private
 * code: Polish Ż, where it draws Ƶ; the Turkish lira sign, a private code; Croatian Đ and đ, where
 * it draws the Icelandic Ð and ð, which is also why the capital D with a stroke in the second set
 * is Đ; Romanian Ă, Î and ă, where it draws Ǎ, Í and ǎ; and Lithuanian ę, where it draws ȩ. An
 * accent the enhancement packets place composes through Unicode, which agrees with libzvbi on every
 * letter libzvbi knows and goes on where it gives up.
 *
 * Arabic is left out: libzvbi has only private codes for it, so there is nothing to check a table
 * against, and a page that names it reads as English, as any set this table does not know does.
 */
internal object TeletextCharacters {

    /** A first set (G0) and the second set (G2) that goes with it, each from 0x20 to 0x7F. */
    class Charset(private val g0: String, private val g2: String) {
        fun g0(code: Int): String = g0[code - 0x20].toString()
        fun g2(code: Int): String = g2[code - 0x20].toString()
    }

    /** The Latin first set's positions that each national option replaces, in the order of the tables below. */
    private val NATIONAL_POSITIONS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)

    private const val ENGLISH = "£\$@←½→↑#—¼‖¾÷"
    private const val GERMAN = "#\$§ÄÖÜ^_°äöüß"
    private const val SWEDISH_FINNISH_HUNGARIAN = "#¤ÉÄÖÅÜ_éäöåü"
    private const val ITALIAN = "£\$é°ç→↑#ùàòèì"
    private const val FRENCH = "éïàëêùî#èâôûç"
    private const val PORTUGUESE_SPANISH = "ç\$¡áéíóú¿üñèà"
    private const val CZECH_SLOVAK = "#ůčťžýířéáěúš"
    private const val NO_SUBSET = "#¤@[\\]^_`{¦}~"
    private const val POLISH = "#ńąŻŚŁćóężśłź"
    private const val TURKISH = "₺ğİŞÖÇÜĞışöçü"
    private const val SERBIAN_CROATIAN_SLOVENIAN = "#ËČĆŽĐŠëčćžđš"
    private const val ROMANIAN = "#¤ŢÂŞĂÎıţâşăî"
    private const val ESTONIAN = "#õŠÄÖŽÜÕšäöžü"
    private const val LETTISH_LITHUANIAN = "#\$ŠėęŽčūšąųžį"

    private const val CYRILLIC_1 =
        " !\"#\$%&'()*+,-./0123456789:;<=>?ЧАБЦДЕФГХИЈКЛМНОПЌРСТУВЃЉЊЗЋЖЂШЏчабцдефгхијклмнопќрстувѓљњзћжђш■"
    private const val CYRILLIC_2 =
        " !\"#\$%ы'()*+,-./0123456789:;<=>?ЮАБЦДЕФГХИЍКЛМНОПЯРСТУЖВЬЪЗШЭЩЧЫюабцдефгхиѝклмнопярстужвьъзшэщч■"
    private const val CYRILLIC_3 =
        " !\"#\$%ï'()*+,-./0123456789:;<=>?ЮАБЦДЕФГХИЍКЛМНОПЯРСТУЖВЬІЗШЄЩЧЇюабцдефгхиѝклмнопярстужвьізшєщч■"
    private const val GREEK =
        " !\"#\$%&'()*+,-./0123456789:;«=»?ΐΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡʹΣΤΥΦΧΨΩΪΫάέήίΰαβγδεζηθικλμνξοπρςστυφχψωϊϋόύώ■"
    private const val HEBREW =
        " !\"#\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ←½→↑#אבגדהוזחטיךכלםמןנסעףפץצקרשת₪‖¾÷■"

    private const val LATIN_G2 =
        " ¡¢£\$¥#§¤‘“«←↑→↓°±²³×µ¶·÷’”»¼½¾¿ ˋˊˆ˜ˉ˘˙¨.˚ˏˍ˝˛ˇ—¹®©™♪₠‰ɑ   ⅛⅜⅝⅞ΩÆĐªĦ ĲĿŁØŒºÞŦŊŉĸæđðħıĳŀłøœßþŧŋ■"
    private const val CYRILLIC_G2 =
        " ¡¢£ ¥#§ ‘“«←↑→↓°±²³×µ¶·÷’”»¼½¾¿ ˋˊˆ˜ˉ˘˙¨.˚ˏˍ˝˛ˇ—¹®©™♪₠‰ɑŁłß⅛⅜⅝⅞DEFGIJKLNQRSUVWZdefgijklnqrsuvwz"
    private const val GREEK_G2 =
        " ab£ehi§:‘“k←↑→↓°±²³xmnp÷’”t¼½¾x ˋˊˆ˜ˉ˘˙¨.˚ˏˍ˝˛ˇ?¹®©™♪₠‰ɑΊΎΏ⅛⅜⅝⅞CDFGJLQRSUVWYZΆΉcdfgjlqrsuvwyzΈ■"

    /** The Latin letters of the Arabic second set; its Arabic letters and digits are left blank. */
    private const val ARABIC_G2 =
        "                                àABCDEFGHIJKLMNOPQRSTUVWXYZëêùî éabcdefghijklmnopqrstuvwxyzâôûç "

    /** The Latin first set with no national option, which the enhancement packets place letters from. */
    val LATIN_NO_SUBSET: Charset = Charset(latin(NO_SUBSET), LATIN_G2)

    private val byCode = arrayOfNulls<Charset>(0x80)

    /**
     * The sets that designation [code] and the page header's [national] option select, as libzvbi
     * selects them: the region of [code] with [national] if that names a set, else [code] itself,
     * else English.
     */
    fun select(code: Int, national: Int): Charset =
        charset((code and 0x78) or (national and 7)) ?: charset(code and 0x7F) ?: charset(0)!!

    /**
     * The region a page in [language] most likely means when its broadcaster names none, as a
     * designation code whose national option is zero. Western Europe for a language that names no
     * other region, or for none.
     */
    fun region(language: String?): Int = when (language?.trim()?.lowercase()?.substringBefore('-')) {
        "pol", "pl" -> 0x08
        "tur", "tr" -> 0x10
        "hrv", "hr", "slv", "sl", "bos", "bs", "ron", "rum", "ro" -> 0x18
        "est", "et", "lav", "lv", "lit", "lt", "rus", "ru", "bul", "bg", "ukr", "uk", "bel", "be", "mkd", "mac", "mk" -> 0x20
        "ell", "gre", "el" -> 0x30
        "heb", "he", "iw" -> 0x50
        else -> 0x00
    }

    /**
     * [base] with diacritical mark [accent], 1 to 15 in the order an enhancement packet numbers
     * them, as one letter where Unicode has one and as the letter and a combining mark where it
     * does not. Marks 9 and 12 are unassigned and leave [base] alone.
     */
    fun compose(base: String, accent: Int): String {
        val pairs = COMPOSED.getOrNull(accent) ?: return base
        if (pairs.isEmpty()) return base
        var index = 0
        while (index < pairs.length) {
            if (pairs[index].toString() == base) return pairs[index + 1].toString()
            index += 2
        }
        return base + MARKS[accent]
    }

    private fun charset(code: Int): Charset? {
        byCode[code]?.let { return it }
        val g0 = when (code) {
            0x00, 0x10, 0x40 -> latin(ENGLISH)
            0x01, 0x09, 0x11, 0x21 -> latin(GERMAN)
            0x02, 0x0A, 0x12 -> latin(SWEDISH_FINNISH_HUNGARIAN)
            0x03, 0x0B, 0x13 -> latin(ITALIAN)
            0x04, 0x0C, 0x14, 0x44 -> latin(FRENCH)
            0x05, 0x15 -> latin(PORTUGUESE_SPANISH)
            0x06, 0x0E, 0x26 -> latin(CZECH_SLOVAK)
            0x07, 0x0D, 0x0F, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1E -> latin(NO_SUBSET)
            0x08 -> latin(POLISH)
            0x16, 0x36 -> latin(TURKISH)
            0x1D -> latin(SERBIAN_CROATIAN_SLOVENIAN)
            0x1F -> latin(ROMANIAN)
            0x22 -> latin(ESTONIAN)
            0x23 -> latin(LETTISH_LITHUANIAN)
            0x20 -> CYRILLIC_1
            0x24 -> CYRILLIC_2
            0x25 -> CYRILLIC_3
            0x37 -> GREEK
            0x55 -> HEBREW
            else -> return null
        }
        val g2 = when (code) {
            0x20, 0x24, 0x25 -> CYRILLIC_G2
            0x37 -> GREEK_G2
            0x40, 0x44, 0x55 -> ARABIC_G2
            else -> LATIN_G2
        }
        return Charset(g0, g2).also { byCode[code] = it }
    }

    /** The Latin first set, ASCII with a block at 0x7F, with [national] in its thirteen national positions. */
    private fun latin(national: String): String {
        val table = CharArray(0x60) { if (it == 0x5F) '■' else (it + 0x20).toChar() }
        for (index in NATIONAL_POSITIONS.indices) table[NATIONAL_POSITIONS[index] - 0x20] = national[index]
        return table.concatToString()
    }

    /** The combining mark of each diacritical mark, by its number; 9 and 12 are unassigned. */
    private val MARKS = arrayOf(
        "", "̀", "́", "̂", "̃", "̄", "̆", "̇", "̈",
        "", "̊", "̧", "", "̋", "̨", "̌",
    )

    /** Each letter with each mark that Unicode composes, as pairs of the letter and the result. */
    private val COMPOSED = arrayOf(
        "",
        "AÀEÈIÌNǸOÒUÙWẀYỲaàeèiìnǹoòuùwẁyỳ",
        "AÁCĆEÉGǴIÍKḰLĹMḾNŃOÓPṔRŔSŚUÚWẂYÝZŹaácćeégǵiíkḱlĺmḿnńoópṕrŕsśuúwẃyýzź",
        "AÂCĈEÊGĜHĤIÎJĴOÔSŜUÛWŴYŶZẐaâcĉeêgĝhĥiîjĵoôsŝuûwŵyŷzẑ",
        "AÃEẼIĨNÑOÕUŨVṼYỸaãeẽiĩnñoõuũvṽyỹ",
        "AĀEĒGḠIĪOŌUŪYȲaāeēgḡiīoōuūyȳ",
        "AĂEĔGĞIĬOŎUŬaăeĕgğiĭoŏuŭ",
        "AȦBḂCĊDḊEĖFḞGĠHḢIİMṀNṄOȮPṖRṘSṠTṪWẆXẊYẎZŻaȧbḃcċdḋeėfḟgġhḣmṁnṅoȯpṗrṙsṡtṫwẇxẋyẏzż",
        "AÄEËHḦIÏOÖUÜWẄXẌYŸaäeëhḧiïoötẗuüwẅxẍyÿ",
        "",
        "AÅUŮaåuůwẘyẙ",
        "CÇDḐEȨGĢHḨKĶLĻNŅRŖSŞTŢcçdḑeȩgģhḩkķlļnņrŗsştţ",
        "",
        "OŐUŰoőuű",
        "AĄEĘIĮOǪUŲaąeęiįoǫuų",
        "AǍCČDĎEĚGǦHȞIǏKǨLĽNŇOǑRŘSŠTŤUǓZŽaǎcčdďeěgǧhȟiǐjǰkǩlľnňoǒrřsštťuǔzž",
    )
}

package rocks.gorjan.winboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmojiData] parsing and search, checked without a device.
 *
 * A malformed line here does not crash the keyboard - [EmojiData.parse] drops what it cannot
 * read - which means a mistake in it fails silently: an emoji quietly missing from its
 * category, or landing in the wrong one, and nothing in a screenshot to notice it by. These
 * are what would catch that, and they run as plain JUnit because [EmojiData.parse] and
 * [EmojiData.search] were written to need nothing an Android device provides - see the class
 * comment on [EmojiData] for why.
 */
class EmojiDataTest {

    private val fixture = listOf(
        "😀\t0\tgrinning face",
        "😃\t0\tgrinning face with big eyes",
        "🤖\t6\trobot",
        "🍕\t3\tpizza",
        "🍽️\t3\tfork and knife with plate",
        "🇵🇷\t8\tflag: puerto rico"
    ).joinToString("\n")

    /**
     * A fixture shaped like the real asset around the words this feature was asked for.
     *
     * The names and the skin-tone spellings are copied from `assets/keyboard/emoji.txt`
     * verbatim, in its own order, because the ordering rule is *about* that order - an
     * invented fixture would let the ranking pass here and be wrong on the phone.
     */
    private val named = listOf(
        "😘\t0\tface blowing a kiss",
        "💋\t0\tkiss mark",
        "😗\t0\tkissing face",
        "💏\t1\tkiss",
        "💏🏻\t1\tkiss: light skin tone",
        "🫅\t1\tperson with crown",
        "🫅🏻\t1\tperson with crown: light skin tone",
        "👑\t6\tcrown",
        "😀\t0\tgrinning face with big eyes",
        "🧔\t1\tman: red hair",
        "👨‍🦲\t1\tman: bald"
    ).joinToString("\n")

    /**
     * A fixture shaped like the real asset around the skin tones.
     *
     * Copied from `assets/keyboard/emoji.txt` verbatim and in its own order, because every
     * rule being checked here is a rule about how Unicode spells these names - the shade as a
     * qualifier after a colon, hair as another one beside it, and the two `person`s that the
     * mixed-shade kiss carries and nothing else does.
     */
    private val shades = listOf(
        "\uD83D\uDC4B\t1\twaving hand",
        "\uD83D\uDC4B\uD83C\uDFFB\t1\twaving hand: light skin tone",
        "\uD83D\uDC4B\uD83C\uDFFF\t1\twaving hand: dark skin tone",
        "\uD83D\uDC68\u200D\uD83E\uDDB0\t1\tman: red hair",
        "\uD83D\uDC68\uD83C\uDFFB\u200D\uD83E\uDDB0\t1\tman: light skin tone, red hair",
        "\uD83D\uDC68\u200D\uD83E\uDDB2\t1\tman: bald",
        "\uD83D\uDC8F\t1\tkiss",
        "\uD83D\uDC8F\uD83C\uDFFB\t1\tkiss: light skin tone",
        "\uD83E\uDDD1\uD83C\uDFFB\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83E\uDDD1\uD83C\uDFFC" +
            "\t1\tkiss: person, person, light skin tone, medium-light skin tone",
        "\uD83E\uDD16\t6\trobot"
    ).joinToString("\n")

    // ---------------------------------------------------------------- families

    /**
     * The grid draws one cell per family, and the shades are not in it.
     *
     * The whole point of the grouping: laid out flat, half the picker is the same hand five
     * times over before the next hand. Hair is not a shade and stays its own cell - `man:
     * bald` is a different person, not a different skin.
     */
    @Test
    fun theGridDrawsOneCellPerFamily() {
        val data = EmojiData.parse(shades)
        assertEquals(
            listOf("waving hand", "man: red hair", "man: bald", "kiss"),
            data.bases[EmojiCategories.PEOPLE_BODY].map { it.name }
        )
        assertEquals(listOf("robot"), data.bases[EmojiCategories.OBJECTS].map { it.name })
    }

    /**
     * Holding any member of a family opens all of it, base first.
     *
     * Any member, not only the base, because the recents hold whatever was actually picked:
     * a hold that gave the dark wave once puts *that* in the recents, and holding it there
     * has to open the same three.
     */
    @Test
    fun holdingAnyShadeOpensTheWholeFamily() {
        val data = EmojiData.parse(shades)
        val family = listOf("\uD83D\uDC4B", "\uD83D\uDC4B\uD83C\uDFFB", "\uD83D\uDC4B\uD83C\uDFFF")
        assertEquals(family, data.variantsOf(family[0]).map { it.glyph })
        assertEquals(family, data.variantsOf(family[2]).map { it.glyph })
    }

    /** Hair is a different emoji, so it heads a family of its own rather than joining one. */
    @Test
    fun hairIsNotAShade() {
        val data = EmojiData.parse(shades)
        assertEquals(
            listOf("man: red hair", "man: light skin tone, red hair"),
            data.variantsOf("\uD83D\uDC68\u200D\uD83E\uDDB0").map { it.name }
        )
        assertTrue(
            "bald has no shades in this fixture and should offer none",
            data.variantsOf("\uD83D\uDC68\u200D\uD83E\uDDB2").isEmpty()
        )
    }

    /**
     * The mixed-shade kiss is filed under the kiss, not under a name nothing has.
     *
     * Unicode spells it `kiss: person, person, light skin tone, medium-light skin tone` - the
     * two `person`s say whose shades those are - so taking out only the shades leaves
     * `kiss: person, person`, which names nothing. Twenty entries in the real asset, and
     * twenty more under `couple with heart`, would otherwise each become a cell of their own.
     */
    @Test
    fun aMixedPairIsFiledUnderItsBase() {
        val data = EmojiData.parse(shades)
        assertEquals(
            listOf("kiss", "kiss: light skin tone",
                "kiss: person, person, light skin tone, medium-light skin tone"),
            data.variantsOf("\uD83D\uDC8F").map { it.name }
        )
    }

    /** An emoji that comes in one shade has none to open, and wears no corner mark. */
    @Test
    fun anEmojiWithOneShadeOffersNothing() {
        val data = EmojiData.parse(shades)
        assertTrue(data.variantsOf("\uD83E\uDD16").isEmpty())
        assertTrue(!data.hasVariants("\uD83E\uDD16"))
        assertTrue(data.hasVariants("\uD83D\uDC4B"))
    }

    /** The search box answers with heads too, for the same reason the grid shows them. */
    @Test
    fun searchAnswersWithHeadsOnly() {
        val data = EmojiData.parse(shades)
        assertEquals(listOf("waving hand"), data.search("waving").map { it.name })
        // And the shades are still there to be found by glyph, which is what recents need.
        assertEquals("waving hand: dark skin tone",
            data.find("\uD83D\uDC4B\uD83C\uDFFF")?.name)
    }

    /**
     * A family whose base this phone cannot draw is headed by the first shade that survived.
     *
     * [EmojiData.filtered] drops whatever the device's font has no glyph for, and it has no
     * idea it is dropping the head of a family. Promoting the next one keeps the other four
     * reachable; the alternative is a family that vanishes because of its first member.
     */
    @Test
    fun aFamilyOutlivesABaseTheFontCannotDraw() {
        val data = EmojiData.filtered(shades) { it != "\uD83D\uDC4B" }
        assertEquals(
            listOf("waving hand: light skin tone", "waving hand: dark skin tone"),
            data.variantsOf("\uD83D\uDC4B\uD83C\uDFFF").map { it.name }
        )
        assertEquals("waving hand: light skin tone",
            data.bases[EmojiCategories.PEOPLE_BODY].first().name)
    }

    // ---------------------------------------------------------------- headed

    /** The two the feature was asked for, both answered by the emoji that *is* the term. */
    @Test
    fun aTermFindsTheEmojiItNames() {
        val data = EmojiData.parse(named)
        assertEquals("👑", data.headed("crown", 3).first().glyph)
        assertEquals("💏", data.headed("kiss", 3).first().glyph)
    }

    /**
     * The emoji a term names outright comes before one that merely mentions it.
     *
     * `kiss` before `kiss mark` before `face blowing a kiss`. This is the whole of the
     * ranking and it is the part that decides what somebody actually sees, because the bar
     * shows two.
     */
    @Test
    fun theShortestNameWins() {
        val data = EmojiData.parse(named)
        assertEquals(
            listOf("💏", "💋", "😘"),
            data.headed("kiss", 5).map { it.glyph }
        )
    }

    /**
     * A word inside a name is not a match - only its first or last word is.
     *
     * The rule that makes this usable at all rather than noise. `with` is in 273 of the real
     * asset's names and `and` in 43, so matching anywhere would fire under ordinary typing
     * and offer three arbitrary emoji for an English preposition.
     */
    @Test
    fun aWordInTheMiddleOfANameIsNotAMatch() {
        val data = EmojiData.parse(named)
        assertTrue("`with` should name nothing", data.headed("with", 5).isEmpty())
        assertTrue("`big` should name nothing", data.headed("big", 5).isEmpty())
        // And the last word still is one, which is what keeps `red heart` findable by `heart`.
        assertEquals("🫅", data.headed("crown", 5).last().glyph)
    }

    /** A name's own first word matches even when the emoji is not called that outright. */
    @Test
    fun theFirstWordOfANameIsAMatch() {
        val data = EmojiData.parse(named)
        assertTrue(data.headed("kiss", 5).any { it.name == "kiss mark" })
    }

    /**
     * Skin tone and hair variants are left out.
     *
     * They are half the real asset - 1875 entries of 3773 - and they are derived forms rather
     * than different emoji. Two slots on the bar spent on two shades of the same couple is two
     * slots wasted.
     */
    @Test
    fun variantsAreNotOffered() {
        val data = EmojiData.parse(named)
        val found = data.headed("kiss", 10) + data.headed("crown", 10) +
            data.headed("man", 10) + data.headed("hair", 10) + data.headed("bald", 10)
        assertTrue(
            "a derived variant was offered: ${found.map { it.name }}",
            found.none { it.name.contains("skin tone") || it.name.contains("hair") ||
                it.name.endsWith("bald") }
        )
    }

    /** Substring matches are the search box's business, not the bar's. */
    @Test
    fun aPartialWordNamesNothing() {
        val data = EmojiData.parse(named)
        assertTrue("`kis` is not a term", data.headed("kis", 5).isEmpty())
        assertTrue("`crow` is not a term", data.headed("crow", 5).isEmpty())
        // The search box still finds them, which is the difference between the two.
        assertTrue(data.search("kis").isNotEmpty())
    }

    /** Nothing shorter than three letters, and nothing at all for blank. */
    @Test
    fun shortTermsNameNothing() {
        val data = EmojiData.parse(named)
        assertTrue(data.headed("ki", 5).isEmpty())
        assertTrue(data.headed("", 5).isEmpty())
        assertTrue(data.headed("   ", 5).isEmpty())
    }

    /** The bar asks for two; asking for two must not cost the work of sorting on every key. */
    @Test
    fun theLimitIsHonoured() {
        val data = EmojiData.parse(named)
        assertEquals(2, data.headed("kiss", 2).size)
        assertEquals(1, data.headed("kiss", 1).size)
        assertTrue(data.headed("kiss", 0).isEmpty())
    }

    /** Case and stray spaces are the caller's typing, not a different term. */
    @Test
    fun caseAndSpacingDoNotMatter() {
        val data = EmojiData.parse(named)
        assertEquals("👑", data.headed("Crown", 3).first().glyph)
        assertEquals("👑", data.headed(" CROWN ", 3).first().glyph)
    }

    // ---------------------------------------------------------------- parse

    @Test
    fun everyEntryLandsInItsOwnCategoryBucket() {
        val data = EmojiData.parse(fixture)
        assertEquals(listOf("grinning face", "grinning face with big eyes"),
            data.categories[EmojiCategories.SMILEYS_EMOTION].map { it.name })
        assertEquals(listOf("pizza", "fork and knife with plate"),
            data.categories[EmojiCategories.FOOD_DRINK].map { it.name })
        assertEquals(listOf("robot"), data.categories[EmojiCategories.OBJECTS].map { it.name })
        assertEquals(listOf("flag: puerto rico"), data.categories[EmojiCategories.FLAGS].map { it.name })
    }

    /** Nine buckets always come back, even the ones nothing in the fixture landed in. */
    @Test
    fun thereAreAlwaysNineBuckets() {
        val data = EmojiData.parse(fixture)
        assertEquals(EmojiCategories.COUNT, data.categories.size)
        assertTrue("People & Body should be empty in this fixture",
            data.categories[EmojiCategories.PEOPLE_BODY].isEmpty())
    }

    /** A category keeps the order its lines were written in - it is the picker's own order. */
    @Test
    fun aCategoryKeepsAssetOrder() {
        val data = EmojiData.parse(fixture)
        val smileys = data.categories[EmojiCategories.SMILEYS_EMOTION]
        assertEquals("grinning face", smileys[0].name)
        assertEquals("grinning face with big eyes", smileys[1].name)
    }

    @Test
    fun theGlyphIsEverythingBeforeTheFirstTab() {
        val data = EmojiData.parse(fixture)
        assertEquals("😀", data.categories[EmojiCategories.SMILEYS_EMOTION][0].glyph)
    }

    /**
     * A line that does not parse costs one emoji, not the whole file.
     *
     * Covers the ways a generator or a hand edit could break a line: no tabs at all, only
     * one, a category that is not a number, and a category number outside the nine that
     * exist - which a future tenth group, added to the asset but not to [EmojiCategories],
     * would produce.
     */
    @Test
    fun malformedLinesAreDroppedNotThrown() {
        val broken = listOf(
            "no tabs in this line at all",
            "😀\tonly one tab",
            "😀\tnotanumber\tsomething",
            "😀\t9\toutside the nine categories",
            "😀\t-1\tnegative category",
            "",
            "😃\t0\tgrinning face with big eyes"
        ).joinToString("\n")

        val data = EmojiData.parse(broken)
        val all = data.categories.flatten()
        assertEquals("only the one well-formed line should have survived", 1, all.size)
        assertEquals("grinning face with big eyes", all.single().name)
    }

    // ---------------------------------------------------------------- search

    @Test
    fun searchMatchesAnywhereInTheNameCaseInsensitively() {
        val data = EmojiData.parse(fixture)
        assertEquals(listOf("grinning face", "grinning face with big eyes"),
            data.search("FACE").map { it.name })
        assertEquals(listOf("flag: puerto rico"), data.search("Rico").map { it.name })
    }

    @Test
    fun blankQueryFindsNothing() {
        val data = EmojiData.parse(fixture)
        assertTrue(data.search("").isEmpty())
        assertTrue(data.search("   ").isEmpty())
    }

    @Test
    fun aQueryNothingMatchesFindsNothing() {
        val data = EmojiData.parse(fixture)
        assertTrue(data.search("xyzzy").isEmpty())
    }

    @Test
    fun searchReachesAcrossEveryCategoryAtOnce() {
        val data = EmojiData.parse(fixture)
        // "pizza" and "robot" are worlds apart in category, "and" only shares the substring.
        assertEquals(listOf("fork and knife with plate"), data.search("and").map { it.name })
    }

    // ---------------------------------------------------------------- filtered

    /** Only what [canDraw] rejects disappears; everything else, and its order, is untouched. */
    @Test
    fun filteredDropsOnlyWhatCannotBeDrawn() {
        val rejected = setOf("🤖", "🇵🇷")
        val data = EmojiData.filtered(fixture) { it !in rejected }

        assertTrue("robot should have been filtered out",
            data.categories[EmojiCategories.OBJECTS].isEmpty())
        assertTrue("the flag should have been filtered out",
            data.categories[EmojiCategories.FLAGS].isEmpty())
        assertEquals(listOf("grinning face", "grinning face with big eyes"),
            data.categories[EmojiCategories.SMILEYS_EMOTION].map { it.name })
    }

    /** A glyph the filter removed cannot be found by search either - it is one grid, one list. */
    @Test
    fun aFilteredOutGlyphCannotBeFoundBySearch() {
        val data = EmojiData.filtered(fixture) { it != "🤖" }
        assertTrue(data.search("robot").isEmpty())
    }

    @Test
    fun keepingEverythingFilteredIsTheSameAsParse() {
        val filtered = EmojiData.filtered(fixture) { true }
        val parsed = EmojiData.parse(fixture)
        assertEquals(parsed.categories, filtered.categories)
    }

    // ---------------------------------------------------------------- find

    /** What recents are rebuilt from - see [EmojiData.find]. */
    @Test
    fun findLooksUpAnEmojiByItsGlyph() {
        val data = EmojiData.parse(fixture)
        assertEquals("pizza", data.find("🍕")?.name)
        assertEquals(EmojiCategories.FLAGS, data.find("🇵🇷")?.category)
    }

    @Test
    fun findAnswersNullForAGlyphThatWasFilteredOut() {
        val data = EmojiData.filtered(fixture) { it != "🍕" }
        assertEquals(null, data.find("🍕"))
    }
}

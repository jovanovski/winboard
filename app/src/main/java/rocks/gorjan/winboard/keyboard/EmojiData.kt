package rocks.gorjan.winboard.keyboard

import android.content.Context
import android.graphics.Paint
import androidx.core.graphics.PaintCompat

/** One emoji: the glyph itself, which group it belongs to, and the words search finds it by. */
data class Emoji(
    val glyph: String,
    val category: Int,
    val name: String
)

/**
 * The nine groups the picker sorts emoji into.
 *
 * These are Unicode's own `emoji-test.txt` groups, in the file's CLDR order, with
 * `Component` left out - it holds bare skin tones and hair, in isolation, which are not
 * emoji anyone picks on their own. See `tools/emojibuild/build_emoji.py`, which built the
 * asset this reads and whose `CATEGORIES` list this must keep matching: an emoji's
 * `categoryIndex` in the asset is a plain index into that list, and if the two ever
 * disagreed about the order every emoji generated before the disagreement would land in the
 * wrong bucket rather than fail to load.
 */
object EmojiCategories {
    const val SMILEYS_EMOTION = 0
    const val PEOPLE_BODY = 1
    const val ANIMALS_NATURE = 2
    const val FOOD_DRINK = 3
    const val TRAVEL_PLACES = 4
    const val ACTIVITIES = 5
    const val OBJECTS = 6
    const val SYMBOLS = 7
    const val FLAGS = 8

    /** Section-header text, in category order. Index it with an emoji's own `category`. */
    val LABELS = listOf(
        "Smileys & Emotion",
        "People & Body",
        "Animals & Nature",
        "Food & Drink",
        "Travel & Places",
        "Activities",
        "Objects",
        "Symbols",
        "Flags"
    )

    const val COUNT = 9
}

/**
 * The emoji asset, parsed - and, once loaded for real, filtered to what this phone can draw.
 *
 * Split from [EmojiPanel] the way [rocks.gorjan.winboard.keyboard.text.Suggester] is split
 * from the keyboard that calls it. [parse] is arithmetic over a string: tab-separated fields
 * in, buckets and a flat list out, nothing it touches comes from Android. That is deliberate
 * and is what lets [EmojiDataTest] build one of these in a plain JUnit test with a few lines
 * of fixture text and no Context, no AssetManager and no device to run on. Reading the real
 * asset off disk, and deciding which of its 3700-odd entries this particular phone's font can
 * actually render, are both genuine I/O and both live in [load] instead, where nothing has to
 * pretend they are pure.
 */
class EmojiData private constructor(
    /** Every kept emoji, bucketed by [EmojiCategories] index - `categories[8]` is Flags. */
    val categories: List<List<Emoji>>
) {

    /** The buckets flattened once, in asset order, for [search] and [find] to scan. */
    private val all: List<Emoji> by lazy { categories.flatten() }

    private val byGlyph: Map<String, Emoji> by lazy { all.associateBy { it.glyph } }

    /**
     * The kept emoji drawn as [glyph], or null.
     *
     * What recents are rebuilt from: the store on disk keeps only the glyph string a user
     * typed, and turning that back into a full [Emoji] - for its category, or simply to
     * confirm it is still one of the ones this device can draw - means looking it up here
     * rather than carrying a second, richer record of "what was recently typed".
     */
    fun find(glyph: String): Emoji? = byGlyph[glyph]

    // ---------------------------------------------------------------- families

    /**
     * A base emoji and its skin-tone variants, grouped once.
     *
     * Half the asset is skin tones - 1875 entries of 3773 - and laid out flat they are what
     * the picker mostly is: six pages of the same hand in five shades before the next hand.
     * So the grid draws one cell per family and a hold opens the rest, the way a hold on a
     * letter key opens its accents. [bases] is what the grid walks and [variantsOf] is what
     * the hold asks for.
     *
     * Both come out of a single pass, in one object, because they are two views of the same
     * grouping and two `by lazy` fields would be the same three and a half thousand names
     * split twice.
     */
    private class Families(
        /** Base first, then the variants in the asset's order. Keyed by *every* glyph in it. */
        val byGlyph: Map<String, List<Emoji>>,
        /** The glyph of every emoji that heads a family, families of one included. */
        val heads: Set<String>,
        /** [categories] with the variants taken out. */
        val bases: List<List<Emoji>>
    )

    private val families: Families by lazy { group() }

    /** [categories], with each family collapsed to the emoji that heads it. */
    val bases: List<List<Emoji>> get() = families.bases

    /**
     * Every emoji drawn the same as [glyph] but for its skin tone, base first, or empty.
     *
     * Asked with *any* glyph of the family rather than only with the base, because the
     * recents hold whatever was actually picked - a hold that gave `\uD83D\uDC4B\uD83C\uDFFD`
     * once puts that in the recents, and holding it there has to open the same six.
     */
    fun variantsOf(glyph: String): List<Emoji> = families.byGlyph[glyph].orEmpty()

    /** Whether [glyph] belongs to a family with more than one member. See [variantsOf]. */
    fun hasVariants(glyph: String): Boolean = families.byGlyph.containsKey(glyph)

    /**
     * Files every emoji under the name of the one it is a variant of.
     *
     * The asset lists a base immediately before its own variants, so the first emoji filed
     * under a key is that key's base and no separate lookup is needed to find it - and if a
     * base was dropped because this phone's font cannot draw it, the first variant that
     * survived takes its place rather than the family disappearing with it.
     */
    private fun group(): Families {
        val known = HashSet<String>(all.size * 2)
        for (emoji in all) known.add(emoji.name)

        val groups = LinkedHashMap<String, MutableList<Emoji>>(2048)
        for (emoji in all) groups.getOrPut(baseNameOf(emoji.name, known)) { mutableListOf() }
            .add(emoji)

        val heads = HashSet<String>(groups.size * 2)
        val byGlyph = HashMap<String, List<Emoji>>(all.size * 2)
        for (family in groups.values) {
            heads.add(family[0].glyph)
            if (family.size < 2) continue
            val members = family.toList()
            for (member in members) byGlyph[member.glyph] = members
        }
        return Families(byGlyph, heads, categories.map { it.filter { e -> e.glyph in heads } })
    }

    /**
     * Every kept emoji whose name contains [query], in the asset's own order.
     *
     * A substring match on purpose, not a prefix match: the asset's names are Unicode's own
     * short descriptions - "grinning face with big eyes", "flag: Puerto Rico" - and a search
     * for "face" or for "rico" is exactly the kind of search a prefix match would refuse to
     * answer. Blank answers empty rather than everything, which is what lets [EmojiPanel]
     * decide "nothing typed yet" and "typed something nothing matches" without asking twice.
     */
    fun search(query: String): List<Emoji> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        // Heads only, the same as the browsing grid: a search for `hand` that answered with
        // the same wave six times over would push everything else off the first screenful,
        // and the five it left out are a hold away on the one it kept.
        return all.filter { it.name.contains(needle) && it.glyph in families.heads }
    }

    /**
     * Emoji whose name is *headed* by a word, ready to answer [headed].
     *
     * Built once, because the alternative is scanning three and a half thousand names on
     * every keystroke - which is what [search] does, and is fine for a search box somebody
     * has deliberately opened and wrong for the suggestion bar, where it would run under
     * every letter of every word typed.
     */
    private val byHead: Map<String, List<Emoji>> by lazy {
        val index = HashMap<String, MutableList<Emoji>>(2048)
        for (emoji in all) {
            if (isVariant(emoji.name)) continue
            val words = nameWords(emoji.name)
            if (words.isEmpty()) continue
            index.getOrPut(words.first()) { mutableListOf() }.add(emoji)
            // Both ends, and only the ends. See [headed] for why the middle is left out.
            if (words.last() != words.first()) {
                index.getOrPut(words.last()) { mutableListOf() }.add(emoji)
            }
        }
        // Sorted once, here, so a lookup is a hash and a `take`.
        //
        // By how many words the name has, which is the whole ranking and needs no second
        // clause: a bucket is keyed by a word that begins or ends every name in it, so a
        // one-word name in it *is* the emoji that term names outright - `crown` before
        // `person with crown`, `kiss` before `kiss mark`. The sort is stable, so the asset's
        // own order - Unicode's, which puts the ordinary before the specialised - is what
        // breaks the ties.
        //
        // The count is worked out once per emoji and carried, rather than being read off
        // `nameWords` inside the comparison. `sortedBy` builds a comparator that calls its
        // selector on *both* sides of *every* comparison, so writing the obvious thing here
        // re-split several thousand names tens of thousands of times, allocating a list and
        // a builder each time - and all of it on whichever thread first asked this map a
        // question, which used to be the main thread under a keystroke.
        index.mapValues { (_, found) ->
            if (found.size < 2) {
                found
            } else {
                found.map { it to nameWords(it.name).size }
                    .sortedBy { (_, words) -> words }
                    .map { (emoji, _) -> emoji }
            }
        }
    }

    /**
     * Emoji the whole word [term] names, best first, or empty.
     *
     * **The term has to be the first or the last word of the name**, and that rule is the
     * whole of making this usable rather than noise. Matching anywhere in the name is what
     * [search] does and is right for a search box; on the suggestion bar it fires under
     * ordinary typing, because the names are English sentences and English sentences are full
     * of small words - `with` is in 273 of them, `and` in 43. First or last is a cheap stand-in
     * for "the term is what this emoji *is*, not a preposition inside its description", and it
     * costs almost nothing real: `red heart` is still found by `heart`, `kiss mark` by `kiss`,
     * and `grinning face with big eyes` is no longer found by `with`.
     *
     * Skin tone and hair variants are left out. They are half the asset - 1875 of 3773 - and
     * they are derived forms rather than different emoji: a bar offering five shades of the
     * same couple kissing is a bar with nothing else on it. The picker still has them all.
     *
     * Ordered by how completely the term accounts for the name - an emoji *called* `crown`
     * before one called `person with crown` - and then by the asset's own order, which is
     * Unicode's, which puts the ordinary ones before the specialised.
     */
    /**
     * Builds the indices now, on whatever thread is calling, so nothing has to build them later.
     *
     * Every lookup here reads through a `by lazy` - [all], [byGlyph], [byHead], [families] -
     * and a lazy is
     * built by whoever asks first. For [byHead] that first asker was the suggestion bar, which
     * runs on the main thread under a keystroke, and what it triggered was a walk over the
     * whole asset: three and a half thousand names split into words and filed into a map of
     * two thousand buckets. Once, but once at the worst possible moment - the third letter of
     * the first word of a typing session, which is precisely when somebody is deciding whether
     * this keyboard keeps up.
     *
     * So the reader that has time asks first. See `WinBoardService.warmEmojiNames`, which
     * calls this on the thread the dictionaries are read on, while nobody is typing yet.
     */
    fun warm() {
        byGlyph
        byHead
        families
    }

    fun headed(term: String, limit: Int): List<Emoji> {
        val needle = term.trim().lowercase()
        if (needle.length < MIN_TERM) return emptyList()
        val found = byHead[needle] ?: return emptyList()
        return if (found.size <= limit) found else found.subList(0, limit)
    }

    companion object {

        /**
         * The shortest term that may name an emoji.
         *
         * Three. At two the bar starts answering `ox` and `pi` under the second letter of
         * every word typed, which is both wrong most of the time and wrong at the moment the
         * user has given the keyboard the least to go on.
         */
        const val MIN_TERM = 3

        /**
         * A name's words, lowercased, with the punctuation dropped.
         *
         * `flag: Puerto Rico` is three words and `man's shoe` is two - the apostrophe splits
         * `man's`, which costs an `s` in the index that nothing will ever look up and saves
         * having to decide what counts as part of a word.
         */
        fun nameWords(name: String): List<String> {
            val words = ArrayList<String>(4)
            val word = StringBuilder()
            for (ch in name) {
                if (ch.isLetterOrDigit()) {
                    word.append(ch.lowercaseChar())
                } else if (word.isNotEmpty()) {
                    words.add(word.toString())
                    word.clear()
                }
            }
            if (word.isNotEmpty()) words.add(word.toString())
            return words
        }

        /**
         * Whether a name is a derived form rather than an emoji of its own.
         *
         * Skin tones and hair colours, which the asset spells as a suffix after a colon -
         * `kiss: light skin tone`, `man: red hair`, `woman: dark skin tone, bald`.
         */
        fun isVariant(name: String): Boolean =
            name.contains("skin tone") || name.endsWith(" hair") || name.endsWith(": bald")

        /**
         * The five shades Unicode writes as a qualifier after the colon.
         *
         * Matched as whole qualifiers rather than by searching the name for `skin tone`,
         * because what is being taken out has to leave a name that another entry in the
         * asset actually has - `man: light skin tone, red hair` has to come out as
         * `man: red hair`, which exists, and not as `man`, which is a different emoji.
         */
        private val SKIN_TONES = setOf(
            "light skin tone",
            "medium-light skin tone",
            "medium skin tone",
            "medium-dark skin tone",
            "dark skin tone"
        )

        /**
         * [name] with its skin-tone qualifiers removed, or null if it had none.
         *
         * The asset's names are a head, a colon and a comma-separated list of qualifiers -
         * `waving hand: light skin tone`, `man: dark skin tone, bald`, `people holding
         * hands: light skin tone, dark skin tone`. Dropping the shades from that list and
         * putting the rest back is the whole of it, and it leaves the *other* qualifiers
         * alone: hair and gender say which emoji this is, not which shade of it, and a
         * picker that folded `man: bald` into `man` would be hiding a different person
         * rather than a different skin.
         *
         * [alsoPerson] additionally drops the `person` qualifiers, and exists for two
         * families. Unicode spells the mixed-shade forms of the gender-neutral kiss and
         * couple as `kiss: person, person, light skin tone, medium-light skin tone` - the
         * two `person`s are there to say whose shades those are - so taking out only the
         * shades leaves `kiss: person, person`, which is not the name of anything. See
         * [baseNameOf], which reaches for this only when the plain answer names nothing.
         */
        fun withoutSkinTone(name: String, alsoPerson: Boolean = false): String? {
            val colon = name.indexOf(": ")
            if (colon < 0) return null
            val head = name.substring(0, colon)
            var dropped = false
            val kept = ArrayList<String>(4)
            for (part in name.substring(colon + 2).split(",")) {
                val qualifier = part.trim()
                when {
                    qualifier in SKIN_TONES -> dropped = true
                    alsoPerson && qualifier == "person" -> Unit
                    else -> kept.add(qualifier)
                }
            }
            if (!dropped) return null
            return if (kept.isEmpty()) head else head + ": " + kept.joinToString(", ")
        }

        /**
         * What [name] should be filed under: the name of the emoji it is a shade of, or its
         * own when it is not a shade of anything.
         *
         * [known] is every name in the asset, and is what decides between the two readings
         * of a name rather than a rule about which is right. Taking the shades out of
         * `kiss: person, person, light skin tone, medium-light skin tone` gives a name
         * nothing has, and the answer is then the one that does - see [withoutSkinTone].
         * Falling back on the *absence* of a name, rather than on a list of the two families
         * this happens to, means a later Unicode release spelling some third family the same
         * way is grouped correctly without anything here being changed.
         */
        fun baseNameOf(name: String, known: Set<String>): String {
            val base = withoutSkinTone(name) ?: return name
            if (base in known) return base
            val withoutPerson = withoutSkinTone(name, alsoPerson = true)
            return if (withoutPerson != null && withoutPerson in known) withoutPerson else base
        }

        /**
         * Parses the asset's own tab-separated format: `glyph<TAB>categoryIndex<TAB>name`.
         *
         * Split by hand rather than with `String.split('\t')` because a handful of the
         * flag glyphs late in the file are themselves built from tag characters that some
         * naive splitters have been known to trip over; indexing the two separators
         * explicitly and slicing between them makes no assumption about what can appear in
         * a glyph beyond "not a tab".
         *
         * Anything that does not parse - the wrong number of fields, a category that is not
         * a number, a category number outside the nine that exist - is dropped rather than
         * thrown on. A single bad line in a 3700-line generated file should cost one emoji,
         * not the whole keyboard's emoji key.
         */
        fun parse(text: String): EmojiData {
            val buckets = Array(EmojiCategories.COUNT) { mutableListOf<Emoji>() }
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val tab1 = line.indexOf('\t')
                if (tab1 < 0) continue
                val tab2 = line.indexOf('\t', tab1 + 1)
                if (tab2 < 0) continue
                val category = line.substring(tab1 + 1, tab2).toIntOrNull() ?: continue
                if (category !in buckets.indices) continue
                buckets[category].add(
                    Emoji(
                        glyph = line.substring(0, tab1),
                        category = category,
                        name = line.substring(tab2 + 1)
                    )
                )
            }
            return EmojiData(buckets.map { it.toList() })
        }

        /**
         * [parse], with every entry [canDraw] rejects removed from every category.
         *
         * A parameter rather than a hard-coded call to
         * [androidx.core.graphics.PaintCompat.hasGlyph] for the same reason [parse] takes no
         * Context: a test can hand this a fake that rejects a chosen few glyphs and check
         * that exactly those, and nothing else, are gone - which is a question about this
         * file's filtering, not about what a real device's font happens to support today.
         */
        fun filtered(text: String, canDraw: (String) -> Boolean): EmojiData {
            val full = parse(text)
            return EmojiData(full.categories.map { bucket -> bucket.filter { canDraw(it.glyph) } })
        }

        /** Where the generated asset lives. See `tools/emojibuild/build_emoji.py`. */
        private const val ASSET_PATH = "keyboard/emoji.txt"

        /**
         * Kept across every [EmojiPanel] the process builds, filtered once.
         *
         * The filtering pass in [load] runs [androidx.core.graphics.PaintCompat.hasGlyph]
         * against something like 3700 glyphs, which is cheap once and is exactly the sort of
         * thing that must not happen again on every tap of the emoji key. Nothing here ever
         * invalidates it: the asset ships inside the APK and a device's installed font does
         * not change under a process that is still running, so a second answer would never
         * differ from the first.
         */
        @Volatile
        private var cached: EmojiData? = null

        /**
         * Reads `assets/keyboard/emoji.txt`, drops what this phone cannot draw, and answers.
         *
         * The reason there is a filter at all: on API 29-30 the system emoji font is part of
         * the OS image and cannot be updated the way it can from Android 13 on, so an entry
         * from a Unicode release newer than the device would draw as a plain tofu box - a
         * rectangle with a hex code in it - if it were offered at all. [PaintCompat.hasGlyph]
         * is asked once per glyph rather than trusted to some Unicode-version cutoff, because
         * "cannot render" is a fact about *this device's font*, which varies by manufacturer
         * and OS update far more than it varies by the Unicode version the AOSP emoji font
         * happened to ship with.
         */
        @Synchronized
        fun load(context: Context): EmojiData {
            cached?.let { return it }
            val text = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val paint = Paint()
            val data = filtered(text) { glyph -> PaintCompat.hasGlyph(paint, glyph) }
            cached = data
            return data
        }
    }
}

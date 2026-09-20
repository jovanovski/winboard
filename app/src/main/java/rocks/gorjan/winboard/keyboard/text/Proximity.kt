package rocks.gorjan.winboard.keyboard.text

import rocks.gorjan.winboard.keyboard.KeyboardLayout

/**
 * How near two keys are to each other, as a substitution cost.
 *
 * The single change that makes autocorrect feel like it understands a thumb rather than a
 * dictionary. Typing `s` where `a` was meant is a finger that landed a few millimetres left;
 * typing `p` where `a` was meant is a different word. A speller that charges the same for both
 * has to be told, by the frequency table alone, which of `sad` and `pad` you meant - and it
 * will get it wrong about half the time, because both are ordinary words.
 *
 * Built from the layout's own geometry, so it is correct for whichever keyboard is up: the
 * neighbours of `а` on the Macedonian layout are nothing like the neighbours of `a`, and
 * nothing here needs to know that.
 *
 * Distances are in key widths, which is what [KeyboardLayout.keyCentres] reports, so the
 * thresholds below mean what they say regardless of screen size: 1.0 is the next key along.
 *
 * ## Asked in two halves, and why
 *
 * This is the innermost thing in the keyboard. The edit-distance walk asks it once for every
 * position in the typed word against every edge it considers, which is hundreds of thousands
 * of times per keystroke - so the answer has to cost about as little as an array read, and
 * the lookup that finds a character's place in the table must not happen inside that loop.
 *
 * Hence [slotOf] and [cost] rather than one call taking two characters. A character's slot is
 * found once - once per keystroke for the typed text, once per trie edge for the candidate -
 * and the loop itself is then two array reads. It was a `HashMap<Char, Int>` consulted twice
 * per comparison, which is a hash, a boxed `Character` and a boxed `Int` in the hottest loop
 * in the app.
 */
internal class Proximity(layout: KeyboardLayout) {

    /**
     * Which slot a character occupies, indexed by its code unit, or [UNKNOWN].
     *
     * A plain array rather than a map: every alphabet this keyboard ships lives low in the
     * Basic Multilingual Plane - Cyrillic ends at U+045F - so the table is a few kilobytes
     * and a lookup is a bounds check and a load.
     */
    private val slots: IntArray

    /** Costs for every pair of slots, flattened to `a * count + b`. */
    private val costs: IntArray
    private val count: Int

    init {
        val centres = layout.keyCentres
        val order = centres.keys.toList()
        count = order.size

        val widest = order.maxOfOrNull { it.code } ?: -1
        slots = IntArray(widest + 1) { UNKNOWN }
        for ((slot, ch) in order.withIndex()) slots[ch.code] = slot

        costs = IntArray(count * count)
        for ((ai, a) in order.withIndex()) {
            val (ax, ay) = centres.getValue(a)
            for ((bi, b) in order.withIndex()) {
                if (a == b) {
                    costs[ai * count + bi] = 0
                    continue
                }
                val (bx, by) = centres.getValue(b)
                val dx = ax - bx
                val dy = ay - by
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                costs[ai * count + bi] = when {
                    distance <= ADJACENT -> SUBSTITUTE_NEAR
                    distance <= NEARBY -> SUBSTITUTE_MID
                    else -> SUBSTITUTE_FAR
                }
            }
        }
    }

    /**
     * Where [ch] sits in the table, or [UNKNOWN].
     *
     * Case is folded, because shift is not a typo: a capital `S` and a lowercase `s` are the
     * same key and must be the same slot.
     */
    fun slotOf(ch: Char): Int {
        val code = ch.lowercaseChar().code
        return if (code < slots.size) slots[code] else UNKNOWN
    }

    /**
     * What it costs to have typed the key at [typed] where the one at [intended] belonged.
     *
     * A character the layout has never heard of - punctuation, a letter from another
     * alphabet - is charged the far cost rather than being refused outright, so a stray one
     * degrades a candidate instead of eliminating it. Two *identical* characters are the
     * caller's business: it has both to hand and comparing them there is cheaper than
     * arriving here, which is why nothing in this method special-cases them.
     */
    fun cost(typed: Int, intended: Int): Int {
        if (typed == UNKNOWN || intended == UNKNOWN) return SUBSTITUTE_FAR
        return costs[typed * count + intended]
    }

    companion object {

        /** A character the layout does not have a key for. */
        const val UNKNOWN = -1

        /**
         * The two distance bands, in key widths.
         *
         * A key's immediate neighbours - left, right, and the two rows either side, which sit
         * a little over one width away on a staggered layout - fall inside [ADJACENT]. The
         * ring beyond that is [NEARBY]: reachable by a badly aimed thumb, but not a near miss.
         */
        private const val ADJACENT = 1.25
        private const val NEARBY = 2.1

        private const val SUBSTITUTE_NEAR = 55
        private const val SUBSTITUTE_MID = 90
        private const val SUBSTITUTE_FAR = 130
    }
}

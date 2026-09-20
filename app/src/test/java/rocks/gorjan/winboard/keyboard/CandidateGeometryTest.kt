package rocks.gorjan.winboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the suggestions land on the bar, and which one a tap gets.
 *
 * Checked as arithmetic for the same reason the alternates row is. The failure this is really
 * about is silent: a bar that hands back the wrong index inserts a word the user can see they
 * did not tap, once in a while, at a scroll position nobody can reproduce on purpose. The
 * numbers are the real ones - a 1344 pixel screen at ten columns, which is the phone this was
 * built on.
 */
class CandidateGeometryTest {

    /** Every slot is wide enough to hit, however short the word in it. */
    @Test
    fun shortWordsStillGetATarget() {
        val slots = candidateSlots(
            widths = listOf(ink("a"), ink("an"), ink("the"), ink("absolutely")),
            padding = PADDING,
            minimum = MINIMUM
        )
        for (slot in slots) {
            assertTrue(
                "slot ${slot.index} is ${slot.width} wide, under the floor of $MINIMUM",
                slot.width >= MINIMUM
            )
        }
    }

    /** And a long word gets more room than a short one, which is the point of the change. */
    @Test
    fun longWordsGetMoreRoomThanShortOnes() {
        val slots = candidateSlots(
            widths = listOf(ink("a"), ink("absolutely")),
            padding = PADDING,
            minimum = MINIMUM
        )
        assertTrue(
            "a long word should not be given the same room as a single letter",
            slots[1].width > slots[0].width
        )
    }

    /** The slots are laid end to end, in order, with nothing between them and no overlap. */
    @Test
    fun theSlotsTileTheRow() {
        val slots = candidateSlots(
            widths = WORDS.map { ink(it) },
            padding = PADDING,
            minimum = MINIMUM
        )
        assertEquals(WORDS.size, slots.size)
        assertEquals(0f, slots.first().left, 0.001f)
        for (i in 1 until slots.size) {
            assertEquals(
                "slot $i does not start where slot ${i - 1} ends",
                slots[i - 1].right, slots[i].left, 0.001f
            )
            assertEquals(i, slots[i].index)
        }
    }

    /**
     * A tap lands on the word it looks like it landed on, at every scroll position.
     *
     * The whole reason this file exists. Walked across the bar in single pixels at each of a
     * spread of scroll offsets, asking the hit test and asking the drawing arithmetic
     * separately and requiring them to agree - because they are two expressions of the same
     * geometry written in two places, and the bug is the day they stop matching.
     */
    @Test
    fun aTapGetsTheWordUnderIt() {
        val slots = candidateSlots(
            widths = WORDS.map { ink(it) },
            padding = PADDING,
            minimum = MINIMUM
        )
        val range = candidateScrollRange(slots, VIEWPORT)
        for (step in 0..10) {
            val scroll = range * step / 10f
            var x = CONTENT_LEFT
            while (x < WIDTH) {
                val hit = candidateAt(x, CONTENT_LEFT, scroll, slots)
                // What is painted at this pixel, from the drawing side of the same numbers.
                val painted = slots.firstOrNull {
                    val left = CONTENT_LEFT - scroll + it.left
                    x >= left && x < left + it.width
                }?.index ?: -1
                assertEquals(
                    "at scroll $scroll, x $x: the tap and the paint disagree",
                    painted, hit
                )
                x += 1f
            }
        }
    }

    /** Nothing before the words is a word - that is where the glyphs live. */
    @Test
    fun theGlyphEndIsNotASuggestion() {
        val slots = candidateSlots(
            widths = WORDS.map { ink(it) },
            padding = PADDING,
            minimum = MINIMUM
        )
        var x = 0f
        while (x < CONTENT_LEFT) {
            assertEquals(-1, candidateAt(x, CONTENT_LEFT, 0f, slots))
            x += 1f
        }
    }

    /**
     * A word longer than the bar keeps its own width, and stays reachable.
     *
     * The cap that used to be here was removed on purpose: a suggestion drawn short of the
     * word it would insert is a suggestion that lies about itself. What has to hold instead is
     * that the row can be scrolled far enough to read the whole of it.
     */
    @Test
    fun aWordWiderThanTheBarIsStillReachable() {
        val monster = "supercalifragilisticexpialidocious.example.com/and/then/some"
        val slots = candidateSlots(
            widths = listOf(ink(monster), ink("the")),
            padding = PADDING,
            minimum = MINIMUM
        )
        assertTrue(
            "the long word should keep its own width, not the bar's",
            slots[0].width > VIEWPORT
        )
        val range = candidateScrollRange(slots, VIEWPORT)
        assertTrue("a row this wide must scroll", range > 0f)
        // Scrolled to the end, the last word is on screen - so nothing is unreachable.
        assertEquals(
            slots.last().index,
            candidateAt(WIDTH - 1f, CONTENT_LEFT, range, slots)
        )
    }

    /**
     * A row that fits does not scroll.
     *
     * The one that would go unnoticed: a negative range lets the first word be dragged off
     * the near edge and never brought back, on a bar that had no business moving at all.
     */
    @Test
    fun aRowThatFitsDoesNotScroll() {
        val slots = candidateSlots(
            widths = listOf(ink("hi"), ink("his")),
            padding = PADDING,
            minimum = MINIMUM
        )
        assertEquals(0f, candidateScrollRange(slots, VIEWPORT), 0.001f)
        assertEquals(0f, candidateScrollRange(emptyList(), VIEWPORT), 0.001f)
    }

    /** Ten ordinary words do not fit, which is why the bar scrolls in the first place. */
    @Test
    fun aFullBarScrolls() {
        val slots = candidateSlots(
            widths = WORDS.map { ink(it) },
            padding = PADDING,
            minimum = MINIMUM
        )
        assertTrue(
            "ten suggestions ought to run past the end of the bar",
            candidateScrollRange(slots, VIEWPORT) > 0f
        )
    }

    /** Scrolled to the end, the last word's right edge is at the end of the strip. */
    @Test
    fun theEndOfTheScrollIsTheEndOfTheWords() {
        val slots = candidateSlots(
            widths = WORDS.map { ink(it) },
            padding = PADDING,
            minimum = MINIMUM
        )
        val range = candidateScrollRange(slots, VIEWPORT)
        val lastRight = CONTENT_LEFT - range + slots.last().right
        assertEquals(
            "the last word should finish flush with the end of the bar",
            WIDTH, lastRight, 0.5f
        )
        // And the last word is reachable there, which is the user-visible half of the same
        // claim: a range that stopped short would leave a suggestion nobody could tap.
        assertEquals(
            slots.last().index,
            candidateAt(WIDTH - 1f, CONTENT_LEFT, range, slots)
        )
    }

    private companion object {

        /** A 1344 pixel screen at ten columns. See `KeyboardView.unitWidth`. */
        const val KEY_W = 1344f / (10f * (1f + 0.107f))

        val PADDING = KEY_W * CandidateBar.WORD_PADDING
        val MINIMUM = KEY_W * CandidateBar.MIN_SLOT

        const val WIDTH = 1344f

        /** Past the microphone and the clipboard, which do not scroll with the words. */
        val CONTENT_LEFT = KEY_W * CandidateBar.GLYPH_SLOT * CandidateBar.GLYPHS
        val VIEWPORT = WIDTH - CONTENT_LEFT

        /** A full bar of ordinary English, longest and shortest together. */
        val WORDS = listOf(
            "a", "an", "and", "the", "there",
            "keyboard", "absolutely", "i", "of", "something"
        )

        /**
         * Roughly what a word measures at the bar's text size.
         *
         * A stand-in for `Paint.measureText`, which needs a font and therefore a device. The
         * arithmetic under test does not care what the numbers are - only that a longer word
         * gives a bigger one - so a fixed width per character is a fair model of it and keeps
         * this a plain unit test.
         */
        fun ink(word: String): Float = word.length * KEY_W * CandidateBar.TEXT * 0.55f
    }
}

package rocks.gorjan.winboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the language list lands, and what the finger picks out of it.
 *
 * The one that matters is [aHoldThatGoesNowhereChangesNothing]. Everything else here is a
 * list in the wrong place, which is a nuisance; that one is a keyboard that quietly went
 * into Greek because a thumb rested on the space bar, which is the kind of thing somebody
 * has to find the settings page to undo.
 *
 * The numbers are the real ones - a 1344 pixel screen at ten columns, which is the phone this
 * was built on - and the key is the space bar on the bottom row: 3.9 columns wide, and the
 * row itself three quarters of a key tall.
 */
class LanguagesGeometryTest {

    /** Open the list, let go, and the keyboard is in the language it was already in. */
    @Test
    fun aHoldThatGoesNowhereChangesNothing() {
        for (current in 0 until 4) {
            val box = boxFor(count = 4)
            // The finger where it pressed: the middle of the space bar, which is below the
            // list by the gutter the list is held off the key by.
            val x = SPACE_LEFT + SPACE_W / 2f
            val y = FACE_TOP + KEY_H * 0.4f
            assertEquals(
                "with $current in use, a hold that went nowhere picked something else",
                current, languageAt(x, y, box, 4, CELL_H, GAP, current)
            )
        }
    }

    /** And sliding back down onto the key calls off a choice already under way. */
    @Test
    fun slidingBackOntoTheKeyComesBackToTheLanguageInUse() {
        val box = boxFor(count = 4)
        val x = SPACE_LEFT + SPACE_W / 2f
        assertEquals("up on the list", 0, languageAt(x, box.top + CELL_H / 2f, box, 4, CELL_H, GAP, 2))
        assertEquals("back on the key", 2, languageAt(x, FACE_TOP, box, 4, CELL_H, GAP, 2))
    }

    /** Moving up the list walks it, one name per cell, and stops at the top. */
    @Test
    fun movingUpTheListWalksIt() {
        val box = boxFor(count = 5)
        val x = SPACE_LEFT + SPACE_W / 2f
        for (i in 0 until 5) {
            val y = box.top + i * (CELL_H + GAP) + CELL_H / 2f
            assertEquals("cell $i", i, languageAt(x, y, box, 5, CELL_H, GAP, 4))
        }
        assertEquals(
            "far above the list",
            0, languageAt(x, box.top - 500f, box, 5, CELL_H, GAP, 4)
        )
    }

    /** The list is above the key, never under the thumb holding it open. */
    @Test
    fun theListSitsAboveTheSpaceBar() {
        val box = boxFor(count = 6)
        assertTrue("list bottom ${box.bottom} is not above the key top $FACE_TOP", box.bottom <= FACE_TOP)
        // And well above the keyboard itself, which is the whole reason it is a window.
        assertTrue("a list of six should leave the keyboard", box.top < 0f)
    }

    /** It stays on the screen sideways, however long the names are. */
    @Test
    fun theListStaysOnScreen() {
        val wide = languagesBox(
            faceLeft = SPACE_LEFT, faceTop = FACE_TOP, faceRight = SPACE_LEFT + SPACE_W,
            count = 3, cellW = WIDTH * 2f, cellH = CELL_H, gap = GAP,
            viewWidth = WIDTH, headroom = HEADROOM
        )
        assertTrue("starts off screen at ${wide.left}", wide.left >= GAP / 2f - 0.5f)
        assertTrue("ends off screen at ${wide.right}", wide.right <= WIDTH - GAP / 2f + 0.5f)
    }

    /**
     * A list too tall for the screen wraps into columns rather than climbing off the top.
     *
     * Twenty-two languages ship and all of them can be on at once, which in portrait is
     * already more than a phone has room for above the space bar and in landscape is far
     * more. A language that cannot be reached is a language that is not really offered.
     */
    @Test
    fun aListTallerThanTheScreenWraps() {
        val box = boxFor(count = 22)
        assertTrue("22 languages should have wrapped", box.columns > 1)
        assertTrue("the list still climbs off the screen", box.height <= HEADROOM + 0.5f)
        assertTrue("the columns do not hold all 22", box.rows * box.columns >= 22)
    }

    /** Every language in a wrapped list can be landed on. */
    @Test
    fun everyLanguageInAWrappedListIsReachable() {
        val count = 22
        val box = boxFor(count = count)
        val found = (0 until box.columns).flatMap { column ->
            (0 until box.rows).map { row ->
                val x = box.left + column * (box.cellWidth + GAP) + box.cellWidth / 2f
                val y = box.top + row * (CELL_H + GAP) + CELL_H / 2f
                languageAt(x, y, box, count, CELL_H, GAP, 0)
            }
        }.toSet()
        assertEquals("some language cannot be reached", (0 until count).toSet(), found)
    }

    /** One column is the ordinary answer: a phone in portrait has room for a dozen names. */
    @Test
    fun anOrdinaryListIsOneColumn() {
        assertEquals(1, boxFor(count = 2).columns)
        assertEquals(1, boxFor(count = 5).columns)
        assertEquals(5, boxFor(count = 5).rows)
    }

    private fun boxFor(count: Int) = languagesBox(
        faceLeft = SPACE_LEFT,
        faceTop = FACE_TOP,
        faceRight = SPACE_LEFT + SPACE_W,
        count = count,
        cellW = CELL_W,
        cellH = CELL_H,
        gap = GAP,
        viewWidth = WIDTH,
        headroom = HEADROOM
    )

    private companion object {
        const val WIDTH = 1344f
        const val KEY_W = WIDTH / (10f * 1.107f)
        const val GAP = KEY_W * 0.107f
        const val KEY_H = KEY_W * 1.56f

        /** The space bar: what the bottom row has left over, which is 3.9 columns. */
        const val SPACE_W = 3.9f * (KEY_W + GAP) - GAP
        const val SPACE_LEFT = GAP / 2f + 2.55f * (KEY_W + GAP)

        /** Its painted top, on a bottom row three quarters of a key tall in a four-row grid. */
        const val FACE_TOP = GAP / 2f + 3f * (KEY_H + GAP)

        const val CELL_H = KEY_H * 0.72f

        /** A cell wide enough for `Македонски` at that height, near enough. */
        const val CELL_W = KEY_W * 4f

        /** A 2400 pixel screen with the keyboard along the bottom of it. */
        const val HEADROOM = 2400f - 900f
    }
}

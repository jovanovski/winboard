package rocks.gorjan.winboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the block of skin tones lands, and which shade each of its cells holds.
 *
 * Checked as arithmetic for the reason [AlternatesGeometryTest] gives: every way this goes
 * wrong is a block that still looks like a block - a few pixels off the screen, or one whose
 * first cell is not the emoji the finger is on, which turns a hold into a lucky dip. The
 * numbers are the real ones: a 1344 pixel panel at eight emoji across, which is the phone
 * this was built on, and the two family sizes the asset actually has - six, which nearly
 * every family is, and twenty-six, which the thirteen two-person emoji are.
 */
class VariantsGeometryTest {

    /**
     * The emoji the grid drew is the one under the finger, from every column.
     *
     * The rule the placement exists to serve: a hold followed by a straight lift has to give
     * back what a tap would have. Holding a cell near the right-hand edge opens a block that
     * cannot run rightwards, and the block is turned around rather than shoved - shoving it
     * would leave the finger somewhere in the middle, pointing at whichever shade happened
     * to be there.
     */
    @Test
    fun theHeldEmojiIsUnderTheFinger() {
        for (column in 0 until COLUMNS) {
            for (count in listOf(2, 6, 26)) {
                val left = column * CELL
                val box = boxFor(left, count)
                val fingerX = left + CELL / 2f
                // Below the block, on the cell that opened it - which is where the finger is.
                val fingerY = box.bottom + CELL
                val drawn = variantCellAt(fingerX, fingerY, box, CELL, GAP)
                assertEquals(
                    "column $column of $count: the finger is not on the first variant",
                    0, variantAt(count, drawn, box)
                )
            }
        }
    }

    /** Sliding away from the held cell walks the shades in order, whichever way it turned. */
    @Test
    fun slidingAwayFromTheCellWalksTheShades() {
        // A block that has to turn around: the rightmost column with a full family of six.
        val left = (COLUMNS - 1) * CELL
        val box = boxFor(left, 6)
        assertEquals("a block against the edge runs leftwards", 5, box.primary)

        val y = box.top + CELL / 2f
        val walked = (0 until 6).map { step ->
            variantAt(6, variantCellAt(left + CELL / 2f - step * (CELL + GAP), y, box, CELL, GAP), box)
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), walked)
    }

    /** A family of six is one row, which is what a hold looks like everywhere else. */
    @Test
    fun theOrdinaryFamilyIsOneRow() {
        val box = boxFor(2 * CELL, 6)
        assertEquals(1, box.rows)
        assertEquals(6, box.columns)
    }

    /**
     * The twenty-six wrap, and they wrap upward.
     *
     * Eight cells of the grid fit across the panel exactly; a block's cells are a gutter
     * apart, so seven of those fit and the twenty-six come out as four rows. Which way the
     * rows are counted is the part worth pinning down: the first shades belong in the row
     * nearest the finger, so row zero is the bottom one and the block grows away upward.
     */
    @Test
    fun aLargeFamilyWrapsUpwardFromTheFinger() {
        val box = boxFor(0f, 26)
        assertEquals(7, box.columns)
        assertEquals(4, box.rows)

        val bottom = variantCellAt(box.left + CELL / 2f, box.bottom - CELL / 2f, box, CELL, GAP)
        val top = variantCellAt(box.left + CELL / 2f, box.top + CELL / 2f, box, CELL, GAP)
        assertEquals("the bottom row holds the first shades", 0, variantAt(26, bottom, box))
        assertEquals("the top row holds the last", 21, variantAt(26, top, box))
    }

    /** The cells a short row leaves over hold nothing, rather than wrapping round. */
    @Test
    fun theLeftoverCellsAreEmpty() {
        val box = boxFor(0f, 26)
        val cells = (0 until box.rows * box.columns).map { variantAt(26, it, box) }
        assertEquals("every variant is reachable exactly once", (0 until 26).toList(),
            cells.filter { it >= 0 }.sorted())
        assertEquals("the rest are empty", 2, cells.count { it < 0 })
    }

    /**
     * A cell in the recents may be showing a shade rather than the plain emoji, and the
     * block has to open on that shade.
     *
     * [variantCellOf] is [variantAt] read backwards, and the two have to agree from every
     * column - including the ones where the block turned around, which is where a mapping
     * inverted by hand stops agreeing with itself.
     */
    @Test
    fun theCellHoldingAShadeIsFoundBack() {
        for (column in 0 until COLUMNS) {
            for (count in listOf(2, 6, 26)) {
                val box = boxFor(column * CELL, count)
                for (index in 0 until count) {
                    assertEquals(
                        "column $column of $count, variant $index",
                        index, variantAt(count, variantCellOf(index, box), box)
                    )
                }
            }
        }
    }

    /** Nothing lands off the panel, from any column, at either family size. */
    @Test
    fun theBlockStaysOnScreen() {
        for (column in 0 until COLUMNS) {
            for (count in listOf(2, 6, 26)) {
                val box = boxFor(column * CELL, count)
                assertTrue("column $column of $count starts off screen", box.left >= 0f)
                assertTrue("column $column of $count runs off screen", box.right <= WIDTH)
            }
        }
    }

    /** The block sits clear above the cell that opened it, never over the finger. */
    @Test
    fun theBlockIsAboveTheHeldCell() {
        val top = 3 * CELL
        val box = boxFor(0f, 6, cellTop = top)
        assertEquals(top - GAP, box.bottom, 0.01f)
    }

    private fun boxFor(cellLeft: Float, count: Int, cellTop: Float = 2 * CELL): VariantsBox =
        variantsBox(
            cellLeft = cellLeft,
            cellTop = cellTop,
            count = count,
            cell = CELL,
            gap = GAP,
            viewWidth = WIDTH
        )

    private companion object {
        const val WIDTH = 1344f
        const val COLUMNS = 8
        const val CELL = WIDTH / COLUMNS
        const val GAP = 9f
    }
}

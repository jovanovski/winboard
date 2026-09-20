package rocks.gorjan.winboard.keyboard

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the block of skin-tone variants goes, worked out with no Android in it.
 *
 * Pulled out of the view for the same reason [alternatesBox] is, and it inherits that file's
 * one rule that matters: **the emoji drawn in the grid must be the one under the finger the
 * moment the block opens.** A cell showing a plain yellow wave has to give a plain yellow
 * wave when held and released, or holding becomes a way to pick something at random. That is
 * what [VariantsBox.primary] is for.
 *
 * What is new here, and the reason this is not simply [alternatesBox] with a longer argument
 * list, is the second dimension. An accent row is a row: a key has eight alternates at most
 * and they fit across the keyboard. A family of emoji can have twenty-six - every pairing of
 * five shades for the two people in a handshake, plus the plain one - which is four rows of
 * seven, and rows need an order. They are counted **from the bottom**, so that the first
 * variant is the cell nearest the finger and the block grows upward away from it.
 */
internal data class VariantsBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,

    /** How many cells across. The last row is the short one when the count does not divide. */
    val columns: Int,

    /** How many rows deep, counted from the bottom - see the class comment. */
    val rows: Int,

    /**
     * Which column of the block sits over the held cell, and therefore holds the first
     * variant of each row - in the bottom row, the emoji the grid itself was drawing.
     *
     * Zero for a block running rightwards from its cell, the last column for one running
     * leftwards, and something in between for one too wide to do either. Derived from where
     * the block actually ended up rather than assumed. See [variantAt].
     */
    val primary: Int
) {

    val width get() = right - left
    val height get() = bottom - top
}

/**
 * @param cellLeft/[cellTop] the held grid cell's own box. Unlike a key, an emoji cell has no
 *   gutter cut out of it - the grid divides its width exactly - so this is the box as drawn.
 * @param count how many variants the block holds, the base emoji included.
 * @param cell the grid's cell pitch, which is also the size of a cell of this block: what is
 *   being offered is the same picture in another shade, and it should be the same size as the
 *   one it is offered from.
 */
internal fun variantsBox(
    cellLeft: Float,
    cellTop: Float,
    count: Int,
    cell: Float,
    gap: Float,
    viewWidth: Float
): VariantsBox {
    val pitch = cell + gap
    val edge = gap / 2f

    // As wide as the panel allows, then as deep as that leaves it. Wide-then-deep rather
    // than some fixed number of columns: the alternative is a block of a shape nobody chose,
    // and the six that nearly every family has have to come out as one row - that is what a
    // hold looks like everywhere else on this keyboard.
    val columns = count.coerceAtMost(floor(viewWidth / pitch).toInt().coerceAtLeast(1))
    val rows = ceil(count / columns.toFloat()).toInt()
    val width = columns * cell + (columns - 1) * gap
    val height = rows * cell + (rows - 1) * gap

    // Rightwards from the cell, which is the ordinary case and reads in the natural order.
    val rightwards = cellLeft
    val fitsRight = rightwards + width <= viewWidth - edge

    // Otherwise turned around: the block ends where the cell ends and runs away to the left.
    val leftwards = cellLeft + cell - width
    val fitsLeft = leftwards >= edge

    val unclamped = if (fitsRight) rightwards else if (fitsLeft) leftwards else rightwards

    // A block that fits neither way - the twenty-six, held on a middle column - is pulled
    // inside the panel. The lower bound is applied last so it starts at the edge rather than
    // at a negative coordinate, which is what a single clamp between crossed bounds gives.
    val left = unclamped.coerceAtMost(viewWidth - edge - width).coerceAtLeast(edge)

    // Above the held cell, including when "above" is off the top of the keyboard: the block
    // is shown in a window of its own precisely so it can be, and one that appeared below
    // would be a block under the thumb that opened it.
    val top = cellTop - gap - height

    val primary = ((cellLeft + cell / 2f - left) / pitch).toInt().coerceIn(0, columns - 1)

    return VariantsBox(left, top, left + width, top + height, columns, rows, primary)
}

/**
 * Which drawn cell of the block a finger at [x], [y] is over, as `row * columns + column`
 * with rows counted from the bottom.
 *
 * Both coordinates are clamped into the block rather than answered with "outside", and the
 * vertical clamp is what makes a one-row block behave exactly like the keyboard's accent
 * row: the finger is *below* it, on the cell it came from, and sliding sideways from there
 * has to walk the shades without the finger having to climb onto them first.
 */
internal fun variantCellAt(x: Float, y: Float, box: VariantsBox, cell: Float, gap: Float): Int {
    val pitch = cell + gap
    val column = ((x - box.left) / pitch).toInt().coerceIn(0, box.columns - 1)
    val fromTop = ((y - box.top) / pitch).toInt().coerceIn(0, box.rows - 1)
    return (box.rows - 1 - fromTop) * box.columns + column
}

/**
 * Which variant a drawn cell holds, or -1 for one of the empty cells a short row leaves.
 *
 * The same outward spread [alternateAt] does, applied per row: cells from [VariantsBox.primary]
 * rightwards take the variants in order, so sliding away from the held cell walks the shades,
 * and the cells to its left take what is left over, furthest first, so a block pinned against
 * the right-hand edge reads as the list reversed and still walks in order as the finger moves
 * left. A block running rightwards has a `primary` of zero and comes out in plain reading
 * order, which is what nearly every family gets.
 */
internal fun variantAt(count: Int, drawn: Int, box: VariantsBox): Int {
    if (count <= 0 || drawn < 0) return -1
    val row = drawn / box.columns
    val column = drawn % box.columns
    val within =
        if (column >= box.primary) column - box.primary else box.columns - 1 - column
    val index = row * box.columns + within
    return if (index < count) index else -1
}

/**
 * Which drawn cell holds variant [index] - [variantAt] read backwards.
 *
 * Needed because the emoji a cell was drawing is not always the first of its family. The
 * recents hold whatever was actually picked, so a cell there may be showing the dark wave
 * rather than the plain one, and the block has to open with *that* cell chosen - otherwise a
 * hold and a straight lift would quietly swap the shade somebody had already chosen once.
 */
internal fun variantCellOf(index: Int, box: VariantsBox): Int {
    val row = index / box.columns
    val within = index % box.columns
    val column =
        if (within <= box.columns - 1 - box.primary) within + box.primary
        else box.columns - 1 - within
    return row * box.columns + column
}

package rocks.gorjan.winboard.keyboard

import kotlin.math.floor

/**
 * Where the language list a hold on the space bar opens goes, worked out with no Android in it.
 *
 * [AlternatesGeometry]'s argument, for the other thing that floats over a key: this is a
 * handful of rules that interact, each of which comes out slightly wrong in a way that still
 * looks like a list, and arithmetic can be asked questions where a popup has to be held open
 * and squinted at.
 *
 * The rule that matters most here is not the alternates' one. A hold on the space bar opens
 * a list of the languages, and **a hold followed by a straight lift must change nothing** -
 * the space bar is the key somebody rests a thumb on, and a gesture that starts there and
 * goes nowhere cannot be allowed to put the keyboard into Greek. So the list is placed above
 * the key rather than under the finger, the language already in use is what is chosen until
 * the finger actually moves onto the list, and dropping back onto the space bar comes back to
 * it. See [languageAt].
 *
 * The second rule is that every language has to be reachable. Twenty-two of them ship and
 * somebody may well have turned on a dozen, which in landscape is a list taller than the
 * screen - so the list wraps into columns rather than running off the top. See [languageGrid].
 */
internal data class LanguageBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,

    /** How many cells tall the list is. */
    val rows: Int,

    /** How many cells across: one, unless there was not the height for one column. */
    val columns: Int,

    /**
     * How wide a cell came out, which is not always what was asked for.
     *
     * The caller sizes a cell to the longest language name it has to hold, and a list of
     * columns of that width can be wider than the keyboard. What comes back is what fits,
     * and what the names are then drawn to.
     */
    val cellWidth: Float
) {

    val width get() = right - left
    val height get() = bottom - top
}

/**
 * How the list folds itself into the room above the key.
 *
 * Down the first column, then the next, so the order reads the way a list does whether or not
 * it had to wrap. One column is the ordinary answer and the only one most people will see: a
 * phone in portrait has most of a screen above the space bar, which is a dozen or more names.
 *
 * @param headroom how much room there is above the key on the *screen*, not in the keyboard's
 *   own window - the list is a window of its own precisely so it can leave the keyboard, and
 *   what it must not leave is the display.
 */
internal fun languageGrid(count: Int, cellH: Float, gap: Float, headroom: Float): Pair<Int, Int> {
    val pitch = cellH + gap
    if (count <= 1 || pitch <= 0f) return 1 to 1
    // The gutter below the bottom cell is the one the list is held off the key by, and it is
    // already spent - so the room left over holds one more cell than a plain division says.
    val fit = floor((headroom + gap) / pitch).toInt().coerceIn(1, count)
    val columns = ceilDiv(count, fit)
    // Shared out rather than filled to the brim: nine languages in a list seven tall is two
    // columns of five and four, not one of seven and one of two.
    return ceilDiv(count, columns) to columns
}

/**
 * @param faceLeft/[faceTop]/[faceRight] the space bar *as painted*, which is inset from its
 *   view's bounds by half a gutter and, on this one key, by its overhang as well.
 * @param cellW how wide a cell wants to be: the longest name and its padding.
 */
internal fun languagesBox(
    faceLeft: Float,
    faceTop: Float,
    faceRight: Float,
    count: Int,
    cellW: Float,
    cellH: Float,
    gap: Float,
    viewWidth: Float,
    headroom: Float
): LanguageBox {
    val (rows, columns) = languageGrid(count, cellH, gap, headroom)
    val edge = gap / 2f

    // As wide as the names need, and never wider than the keyboard: a list that ran off the
    // side would hide the very names it is there to show.
    val room = (viewWidth - 2f * edge - (columns - 1) * gap) / columns
    val cell = cellW.coerceAtMost(room)

    val width = columns * cell + (columns - 1) * gap
    val height = rows * cellH + (rows - 1) * gap

    // Centred on the space bar, which is the key the finger is on and the middle of the
    // keyboard besides, then held inside the edges the same way the alternates row is.
    val centred = (faceLeft + faceRight) / 2f - width / 2f
    val left = centred.coerceAtMost(viewWidth - edge - width).coerceAtLeast(edge)

    // Above the key, like everything else that opens on a hold, and for the same reason: a
    // list below the space bar is a list under the thumb that opened it.
    val bottom = faceTop - gap

    return LanguageBox(left, bottom - height, left + width, bottom, rows, columns, cell)
}

/**
 * Which language a finger at [x], [y] - in the keyboard's own coordinates - is over.
 *
 * [current] below the list, and that is the whole of the safety in this gesture rather than a
 * detail of the hit testing. The finger starts on the space bar, which is below the list and
 * stays below it; until it is moved up onto a name, the language chosen is the one already in
 * use, so opening the list and letting go leaves the keyboard exactly as it was. Sliding back
 * down onto the key is how a gesture already under way is called off.
 *
 * Everywhere above that, the nearest cell: a finger reaching up a list drifts sideways without
 * meaning to, and one reaching past the top has clearly asked for the top.
 */
internal fun languageAt(
    x: Float,
    y: Float,
    box: LanguageBox,
    count: Int,
    cellH: Float,
    gap: Float,
    current: Int
): Int {
    if (count <= 0) return current
    if (y >= box.bottom) return current
    val column = ((x - box.left) / (box.cellWidth + gap)).toInt().coerceIn(0, box.columns - 1)
    val row = ((y - box.top) / (cellH + gap)).toInt().coerceIn(0, box.rows - 1)
    // A short last column - nine names in two columns of five - has cells that hold nothing.
    // The name above is what such a cell answers with, which is what the finger is nearest to.
    return (column * box.rows + row).coerceIn(0, count - 1)
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

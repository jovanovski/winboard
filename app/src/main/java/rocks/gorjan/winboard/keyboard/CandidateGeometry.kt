package rocks.gorjan.winboard.keyboard

/**
 * Where each suggestion sits along the bar, worked out with no Android in it.
 *
 * Pulled out of the view for the same reason [alternatesBox] was. A row of words that are each
 * as wide as their own text, laid on a strip narrower than the row, scrolled under a finger,
 * is four rules that interact - and every way it goes wrong is a way that still *looks* like a
 * row of suggestions. A word tapped and a different word inserted is the failure this guards
 * against, and it is not a failure anybody spots by reading the code or by squinting at a
 * screenshot: it needs the hit test asked, at a scroll position, and told what it should have
 * said.
 *
 * Everything here is in the row's own coordinates - measured from the first word's left edge,
 * with the glyphs at the near end of the bar and the scroll offset both left to the caller.
 * That is what keeps it arithmetic: it knows about words and widths and nothing about where
 * the bar happens to be on the screen.
 */
/**
 * One suggestion's place in the row.
 *
 * The width is carried rather than derived from the two edges, which is not fussiness: the
 * edges are an accumulation, and `right - left` a long way along one of those comes back a
 * hair under the width that was put in - enough that a slot sized exactly to the floor
 * measures a fraction below it. Everything that asks "is this wide enough to hit" deserves the
 * number that was decided rather than the number that survived the arithmetic.
 */
internal class CandidateSlot(val index: Int, val left: Float, val width: Float) {
    val right get() = left + width
}

/**
 * Lays [widths] out left to right, each in a slot of its own.
 *
 * @param widths the measured ink of each word, in order.
 * @param padding the air on each side of a word inside its slot.
 * @param minimum the narrowest a slot may be, whatever is in it. This is the rule that makes
 *   variable widths safe to use at all: `a` and `an` are among the commonest things the bar
 *   will ever offer, and sized to their ink they are a four-millimetre target. The floor is
 *   set so the *narrower* dimension of every slot clears Android's minimum, not merely its
 *   area - a tall thin strip is still a thin strip to aim at.
 *
 * There is no ceiling to go with that floor, and there was one for a while. The case it was
 * aimed at is real - the first suggestion is always the literal text typed, and text typed can
 * be a forty-character URL with no space in it - but capping it meant a suggestion drawn short
 * of the word it would insert, which is the one thing a suggestion must never be. The row
 * scrolls, so a long word is a word you have to flick to see the end of rather than a word
 * that has been lost, and that is the better of the two.
 */
internal fun candidateSlots(
    widths: List<Float>,
    padding: Float,
    minimum: Float
): List<CandidateSlot> {
    val slots = ArrayList<CandidateSlot>(widths.size)
    var x = 0f
    for (i in widths.indices) {
        val slotW = maxOf(minimum, widths[i] + padding * 2f)
        slots.add(CandidateSlot(i, x, slotW))
        x += slotW
    }
    return slots
}

/**
 * How far the row may be dragged: everything there is, less what fits on screen at once.
 *
 * Never negative. A row shorter than the strip it is on does not scroll at all, and the
 * difference between "does not scroll" and "scrolls backwards by 40 pixels" is a bar whose
 * first word can be dragged out of sight and not brought back.
 */
internal fun candidateScrollRange(slots: List<CandidateSlot>, viewport: Float): Float =
    ((slots.lastOrNull()?.right ?: 0f) - viewport).coerceAtLeast(0f)

/**
 * Which suggestion is at [x], or -1 for none.
 *
 * @param x where the finger is, in the bar's coordinates.
 * @param contentLeft where the words begin - past the glyphs, which do not scroll with them.
 * @param scroll how far the row has been dragged.
 */
internal fun candidateAt(
    x: Float,
    contentLeft: Float,
    scroll: Float,
    slots: List<CandidateSlot>
): Int {
    if (x < contentLeft) return -1
    val along = x - contentLeft + scroll
    for (slot in slots) if (along >= slot.left && along < slot.right) return slot.index
    return -1
}

package rocks.gorjan.winboard.keyboard

import kotlin.math.abs

/**
 * How fast a pushed joystick walks the caret, worked out with no Android in it.
 *
 * Pulled out of [JoystickView] for the same reason [alternatesBox] is pulled out of the popup
 * that uses it. This is the *feel* of the control - what a small push does, what a big one
 * does, what happens the instant it engages - and feel is exactly the kind of thing that comes
 * out slightly wrong and stays wrong, because a caret that runs a bit too fast still looks
 * like a caret running. As plain arithmetic it can be asked questions instead of being tested
 * by holding a thumb on a phone and squinting.
 *
 * It carries state, and the state is the point. A rate is steps per second and a frame is
 * about sixteen milliseconds, so most frames owe the caret a third of a step; rounding each
 * frame's share on its own would floor every one of them to zero and the caret would never
 * move at all. The remainder has to be carried, and where it is *dropped* - on engaging, on
 * returning to the middle, on the caret hitting the end of the text - is what stops a stick
 * paying out movement nobody asked for.
 *
 * It also decides which of the two things a push is asking for, because the stick has two
 * axes and the caret has two ways of moving: along the line a character at a time, and from
 * one line to the next. Nobody pushes exactly sideways, so something has to say that a
 * gesture a few degrees off horizontal is still a horizontal one - see [pick].
 */
internal class CaretRate {

    /** Fractions of a step owed to the caret, carried between frames. */
    private var carry = 0f

    /**
     * Whether the stick is being read sideways rather than up and down.
     *
     * Only one of the two at a time, deliberately. A caret that ran diagonally would be two
     * controls fighting over one finger: the small vertical drift in a sideways push is not
     * a request to change line, it is a thumb pivoting about a knuckle, and paying it out as
     * movement would mean nobody could walk along a line without leaving it.
     */
    private var across = true

    /** Which way the stick was pushed last along that axis, and zero when it is at rest. */
    private var way = 0

    /** How far it was pushed when it was last read, for [fine] to judge the pace by. */
    private var push = 0f

    /** Whether the push just read was an up-and-down one rather than a sideways one. */
    val vertical: Boolean get() = !across

    /** Whether the stick is currently pushed far enough to be asking for anything. */
    val engaged: Boolean get() = way != 0

    /**
     * How many steps the caret should take for [seconds] at the current deflection.
     *
     * @param dx how far the finger is from where it went down, sideways: negative is leftward.
     * @param dy the same downward: negative is up the lines.
     * @return steps to take along whichever axis [vertical] then names, negative for left or
     *   up, and zero for most frames of a slow push.
     */
    fun steps(dx: Float, dy: Float, seconds: Float): Int {
        val was = across
        val deflection = pick(dx, dy)
        push = abs(deflection)

        if (push < DEAD_DP) {
            // Back at rest. The remainder is dropped rather than kept, so a stick pushed,
            // returned, and pushed again does not pay out what it was owed from last time.
            way = 0
            carry = 0f
            return 0
        }

        val to = if (deflection < 0f) -1 else 1

        // The first step of a push, given at once and not waited for.
        //
        // Without this a nudge is worth nothing: at the bottom of the range the caret takes
        // half a second to move one character, so pushing and letting go - which is how
        // anybody asks for exactly one - would do nothing at all about half the time. It is
        // the same shape as a held key, which types once and only then begins to repeat, and
        // it is what makes one step and many steps the same gesture at two lengths.
        //
        // A turn onto the other axis counts as a new push for the same reason a reversal
        // does: what was owed was owed in characters, and it cannot be spent in lines.
        if (way != to || across != was) {
            way = to
            carry = 0f
            return to
        }

        carry += rate(push, across) * seconds * to
        val whole = carry.toInt()
        if (whole != 0) carry -= whole
        return whole
    }

    /**
     * Which axis the stick is being read on, and how far it is pushed along it.
     *
     * At rest it is simply whichever way the finger has actually gone. Once it is engaged the
     * axis in use keeps the gesture unless the other one clearly beats it, and the margin is
     * the whole of the point: without one, a push held a hair off forty-five degrees would
     * cross and re-cross the line every few frames, and each crossing starts a fresh push -
     * so the caret would sit there alternating one character and one line for as long as the
     * thumb was still. With one, changing your mind means meaning it.
     */
    private fun pick(dx: Float, dy: Float): Float {
        if (way == 0) {
            across = abs(dx) >= abs(dy)
        } else {
            val holding = if (across) abs(dx) else abs(dy)
            val other = if (across) abs(dy) else abs(dx)
            if (other > holding * SWITCH) across = !across
        }
        return if (across) dx else dy
    }

    /**
     * The caret did not go where it was sent: it is against the end of the text.
     *
     * The remainder goes with it. A stick held against a wall would otherwise bank a second of
     * movement and spend it the instant the caret had somewhere to go again.
     */
    fun stalled() {
        carry = 0f
    }

    /** The finger has come off. */
    fun release() {
        carry = 0f
        way = 0
        push = 0f
    }

    /**
     * How fast the caret runs at a push of [dp], in steps a second: characters along a line
     * when [sideways], lines when not.
     *
     * Squared rather than straight, which is the whole feel of the control. A linear ramp
     * spends most of its travel in the middle of the range, so the slow end - where you are
     * placing the caret between two particular letters - is a sliver, and everything past
     * halfway is indistinguishable haste. Squaring gives most of the travel to the slow end
     * and puts the speed at the extreme, where the finger is deliberately far out.
     *
     * Lines are counted far slower than characters, and the reason is not caution but size:
     * one line is a whole row of text, so a stick pushed as far as it goes covers about as
     * much ground either way. A vertical range that matched the horizontal one would clear
     * the screen in a third of a second and land nowhere anybody meant.
     */
    fun rate(dp: Float, sideways: Boolean): Float {
        val t = ((abs(dp) - DEAD_DP) / (FULL_DP - DEAD_DP)).coerceIn(0f, 1f)
        val slowest = if (sideways) MIN_RATE else MIN_LINES
        val fastest = if (sideways) MAX_RATE else MAX_LINES
        return slowest + (fastest - slowest) * t * t
    }

    companion object {

        /**
         * How far the stick has to be pushed before it is pushed at all.
         *
         * A thumb resting on a control is not asking for anything, and a caret that crept
         * whenever a hand was still would be unusable. Small, because everything past it is
         * the useful travel and there is not much of that on a phone.
         */
        const val DEAD_DP = 8f

        /**
         * And how far out is as far as it goes.
         *
         * About a thumb's comfortable reach from where it landed. Past this the rate stops
         * climbing rather than running away, so shoving the finger to the edge of the screen
         * is the same as pushing it far - which is what somebody in a hurry will do, and it
         * should not become a different, wilder control when they do.
         */
        const val FULL_DP = 72f

        /**
         * How much further the other axis has to be pushed before the gesture turns onto it.
         *
         * Half as far again. Enough that a sideways push wandering up the screen stays a
         * sideways push, and little enough that a deliberate turn - a thumb going up after
         * the line it means to fix - is answered without having to come back to the middle
         * first.
         */
        const val SWITCH = 1.5f

        /**
         * The slowest and the fastest the caret runs along a line, in characters a second.
         *
         * Two at the bottom, which is a character every half second - deliberately slow enough
         * to stop on the one you meant. Thirty at the top, which crosses a line of text in a
         * little over a second and is about as fast as a caret can move while still being
         * watched rather than merely waited for.
         */
        const val MIN_RATE = 2f
        const val MAX_RATE = 30f

        /**
         * And across the lines, in lines a second.
         *
         * Two again at the bottom, because the reason for it is the same: one line, asked for
         * on purpose, and time to stop on it. Ten at the top, which is a screenful of a
         * message in about a second.
         */
        const val MIN_LINES = 2f
        const val MAX_LINES = 10f
    }
}

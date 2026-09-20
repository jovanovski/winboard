package rocks.gorjan.winboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * How the joystick walks the caret.
 *
 * This is a *feel* being tested, which is unusual and is the reason it is worth doing. Every
 * way this goes wrong looks fine on screen - a caret that runs a little too fast, a nudge that
 * does nothing about half the time, a stick that banks movement while it is against the end of
 * the text and then lurches - and none of those can be found by reading the code or by holding
 * a thumb on a phone and guessing. Asked as arithmetic they are all one assertion each.
 *
 * A frame is stepped explicitly rather than waited for, so these run in microseconds and the
 * one property that actually matters at sixty frames a second - that the caret's speed does
 * not depend on how often it is asked - can be checked by asking it at two different rates.
 */
class CaretRateTest {

    /** A thumb resting on the dot asks for nothing. */
    @Test
    fun aRestingFingerMovesNothing() {
        val pace = CaretRate()
        var total = 0
        repeat(FRAMES_PER_SECOND) { total += pace.steps(CaretRate.DEAD_DP - 1f, 0f, FRAME) }
        assertEquals("a push inside the dead zone should move the caret nowhere", 0, total)
    }

    /**
     * A nudge is worth exactly one character, at once.
     *
     * The case the rate alone gets wrong. At the bottom of the range the caret takes half a
     * second to move one character, so pushing and letting go - which is how anybody asks for
     * exactly one - would do nothing at all about half the time.
     */
    @Test
    fun aNudgeIsWorthOneCharacter() {
        val pace = CaretRate()
        assertEquals(1, pace.steps(CaretRate.DEAD_DP + 1f, 0f, FRAME))
        assertEquals(-1, CaretRate().steps(-(CaretRate.DEAD_DP + 1f), 0f, FRAME))
    }

    /** And only one: holding it just past the dead zone crawls rather than repeating at once. */
    @Test
    fun aSmallPushCrawls() {
        val pace = CaretRate()
        val push = CaretRate.DEAD_DP + 1f
        var total = 0
        repeat(FRAMES_PER_SECOND) { total += pace.steps(push, 0f, FRAME) }
        // One for engaging, plus a second of the slowest rate.
        val expected = 1 + CaretRate.MIN_RATE
        assertTrue(
            "a second at the slowest push gave $total characters, wanted about $expected",
            abs(total - expected) <= 1f
        )
    }

    /** Pushed all the way, it runs at the top of the range. */
    @Test
    fun aFullPushRuns() {
        val pace = CaretRate()
        val push = CaretRate.FULL_DP + 40f  // past the end: the rate stops climbing
        var total = 0
        repeat(FRAMES_PER_SECOND) { total += pace.steps(push, 0f, FRAME) }
        val expected = 1 + CaretRate.MAX_RATE
        assertTrue(
            "a second at full deflection gave $total characters, wanted about $expected",
            abs(total - expected) <= 2f
        )
    }

    /**
     * The same push for the same length of time moves the same distance, however often it
     * is asked.
     *
     * A loop that counted frames rather than seconds would run at half speed on a phone
     * dropping to thirty, which is the phone somebody is most likely to be using it on.
     */
    @Test
    fun theSpeedDoesNotDependOnTheFrameRate() {
        val push = 40f
        val fast = CaretRate()
        var atSixty = 0
        repeat(60) { atSixty += fast.steps(push, 0f, 1f / 60f) }

        val slow = CaretRate()
        var atThirty = 0
        repeat(30) { atThirty += slow.steps(push, 0f, 1f / 30f) }

        assertTrue(
            "sixty frames gave $atSixty and thirty gave $atThirty for the same second",
            abs(atSixty - atThirty) <= 1
        )
    }

    /** Further out is faster, all the way along - no flat stretch, no step. */
    @Test
    fun fartherIsAlwaysFaster() {
        for (sideways in listOf(true, false)) {
            var last = -1f
            var dp = CaretRate.DEAD_DP
            while (dp <= CaretRate.FULL_DP) {
                val rate = CaretRate().rate(dp, sideways)
                assertTrue("the rate went backwards at ${dp}dp", rate > last)
                last = rate
                dp += 1f
            }
        }
        assertEquals(CaretRate.MIN_RATE, CaretRate().rate(CaretRate.DEAD_DP, true), 0.001f)
        assertEquals(CaretRate.MAX_RATE, CaretRate().rate(CaretRate.FULL_DP, true), 0.001f)
        assertEquals(CaretRate.MIN_LINES, CaretRate().rate(CaretRate.DEAD_DP, false), 0.001f)
        assertEquals(CaretRate.MAX_LINES, CaretRate().rate(CaretRate.FULL_DP, false), 0.001f)
    }

    /**
     * Most of the travel belongs to the slow end.
     *
     * The point of squaring the ramp: placing a caret between two particular letters is what
     * the control is *for*, and a linear ramp gives that job a sliver of the travel. Half the
     * travel spent below a quarter of the speed is exactly what the square buys.
     */
    @Test
    fun mostOfTheTravelIsForFineWork() {
        val half = CaretRate.DEAD_DP + (CaretRate.FULL_DP - CaretRate.DEAD_DP) / 2f
        val span = CaretRate.MAX_RATE - CaretRate.MIN_RATE
        val atHalf = CaretRate().rate(half, true) - CaretRate.MIN_RATE
        assertTrue(
            "half the travel reaches $atHalf of $span, which is not a slow end worth having",
            atHalf <= span * 0.3f
        )
    }

    /** Lines are a bigger step than characters, so they are walked more slowly. */
    @Test
    fun linesRunSlowerThanCharacters() {
        var dp = CaretRate.DEAD_DP
        while (dp <= CaretRate.FULL_DP) {
            assertTrue(
                "lines ran as fast as characters at ${dp}dp",
                CaretRate().rate(dp, false) <= CaretRate().rate(dp, true)
            )
            dp += 1f
        }
    }

    /** Whichever way the finger actually went is the axis it is read on. */
    @Test
    fun theLongerPushPicksTheAxis() {
        val sideways = CaretRate()
        sideways.steps(40f, 10f, FRAME)
        assertTrue("a mostly sideways push should move along the line", !sideways.vertical)

        val downward = CaretRate()
        downward.steps(10f, 40f, FRAME)
        assertTrue("a mostly downward push should change line", downward.vertical)
    }

    /**
     * And it keeps that axis while the other one is merely close.
     *
     * Without the margin a push held near the diagonal crosses and re-crosses the line every
     * few frames, and every crossing is a fresh push worth one step - so the caret sits there
     * alternating one character and one line for as long as the thumb is still.
     */
    @Test
    fun aNearlyDiagonalPushDoesNotFlap() {
        val pace = CaretRate()
        pace.steps(40f, 0f, FRAME)
        var turns = 0
        repeat(FRAMES_PER_SECOND) {
            pace.steps(40f, 39f, FRAME)
            if (pace.vertical) turns++
        }
        assertEquals("a push a hair off the diagonal changed axis $turns times", 0, turns)
    }

    /** Turning onto the other axis is a fresh push: one step at once, and nothing banked. */
    @Test
    fun turningOntoTheOtherAxisStartsAgain() {
        val pace = CaretRate()
        repeat(20) { pace.steps(60f, 0f, FRAME) }
        assertEquals("turning down should move one line at once", 1, pace.steps(0f, 60f, FRAME))
        assertTrue(pace.vertical)
        assertEquals("and not pay out what the sideways push was owed", 0, pace.steps(0f, 60f, FRAME))
    }

    /** Reversing is immediate, not a wait for the momentum the other way to run out. */
    @Test
    fun reversingTurnsAtOnce() {
        val pace = CaretRate()
        repeat(20) { pace.steps(60f, 0f, FRAME) }
        assertEquals(
            "pushing the other way should turn the caret at once",
            -1, pace.steps(-60f, 0f, FRAME)
        )
    }

    /**
     * A stick held against the end of the text banks nothing.
     *
     * Otherwise a second spent pushing at a wall is a second of movement paid out in one jump
     * the moment the caret has somewhere to go.
     */
    @Test
    fun pushingAtAWallBanksNothing() {
        val pace = CaretRate()
        pace.steps(60f, 0f, FRAME)
        repeat(FRAMES_PER_SECOND) {
            pace.steps(60f, 0f, FRAME)
            pace.stalled()
        }
        assertEquals(
            "a stalled stick paid out banked movement on the next frame",
            0, pace.steps(60f, 0f, FRAME)
        )
    }

    /** Coming back to the middle and pushing again is a fresh push, not a resumed one. */
    @Test
    fun comingBackToTheMiddleForgetsTheRemainder() {
        val pace = CaretRate()
        pace.steps(60f, 0f, FRAME)
        repeat(5) { pace.steps(60f, 0f, FRAME) }
        pace.steps(0f, 0f, FRAME)
        // A fresh push is worth its one engaging character and nothing banked on top.
        assertEquals(1, pace.steps(60f, 0f, FRAME))
        assertEquals(0, pace.steps(60f, 0f, FRAME))
    }

    private companion object {
        const val FRAMES_PER_SECOND = 60
        const val FRAME = 1f / 60f
    }
}

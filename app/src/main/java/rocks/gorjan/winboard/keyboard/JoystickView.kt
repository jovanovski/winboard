package rocks.gorjan.winboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import rocks.gorjan.winboard.KeyboardPalette
import kotlin.math.hypot

/**
 * The caret joystick: a dot in the gutter that drags the cursor.
 *
 * Putting the caret where you want it on a phone is genuinely hard - the target is a gap
 * between two letters, under a fingertip that covers eight of them - and there are two answers
 * to it. One is the space bar, which this keyboard already has: drag the widest key on the
 * board and the caret follows. The other is this, and the difference between them is not the
 * gesture but what it costs.
 *
 * The space bar's version is free in screen but not in confidence. Every space typed is a
 * gesture that *might* have been a slide, so the slop that tells them apart has to be
 * generous, and a thumb that rolls sideways off the key still occasionally moves the caret
 * when it meant to type a space. A control that does nothing else has no such problem: there
 * is nothing to disambiguate it from, so it answers the first pixel of movement and it answers
 * every time. That is why turning this on turns the space bar's slide *off* rather than
 * leaving both - two ways to do one thing is not twice as good, it is one of them still
 * costing you the occasional wrong space for a gesture you no longer use.
 *
 * It is also why it moves in four directions where the space bar could only ever move in two.
 * A key can only report the one axis it is not already using; a control of its own can report
 * both, so up and down are here as well, and they do what up and down have always done to a
 * caret - the line above, the line below, at the column it was already in. Not that the two
 * are the same gesture at right angles: characters and lines are wildly different sizes, so
 * they run at wildly different rates and only one of them can be asked for at a time. See
 * [CaretRate].
 *
 * It sits on the crossing of two gutters - the one above the bottom row, and the one after
 * that row's first key - because that is the one place on a full keyboard where nothing is,
 * and where a thumb resting between rows is already close to it.
 *
 * The halo is the part that makes it work. A dot painted *on* the keys would be a mark on a
 * key, which reads as decoration; a dot with a ring of bare keyboard around it has visibly had
 * room made for it, and that ring is also the promise about where the touch target is.
 */
@SuppressLint("ViewConstructor")
internal class JoystickView(
    context: Context,
    private var palette: KeyboardPalette
) : View(context) {

    /**
     * The caret should move this many characters along the line, negative for left.
     *
     * A delta rather than a position, for the same reason the space bar's slide reports one:
     * where the caret ends up is the field's business - a line ending, a chip, an emoji that
     * is two code units wide - and the keyboard says "one to the left" and lets the field work
     * out what that means.
     *
     * @return how many it actually moved, which is fewer once the caret is against the end of
     *   the text. That is what stops the dot ticking away against a wall.
     */
    var onDrag: ((Int) -> Int)? = null

    /**
     * And this many lines, negative for up.
     *
     * Separate from [onDrag] rather than a second argument to it because the two are answered
     * by quite different work at the far end - one names a position a character away, the
     * other has to find where the line above starts before it can name anything at all - and
     * because only one of them is ever being asked for.
     *
     * @return how many lines it actually moved: zero at the top and the bottom of the text,
     *   and zero in a field that will not say what its own lines are. See
     *   `WinBoardService.onCursorLines`.
     */
    var onDragLines: ((Int) -> Int)? = null

    /** Set by the grid on every measure pass, so this is sized in the same units as the keys. */
    private var keyW = 0f

    private var held = false

    /** Where the finger started, in screen coordinates - it leaves this view immediately. */
    private var fromX = 0f
    private var fromY = 0f

    /**
     * How far the finger is from where it went down, which is the stick's deflection.
     *
     * From where it *went down* rather than from the middle of the dot, so that grabbing the
     * dot near its edge is not already a push. The control is a stick that springs back to
     * wherever you took hold of it.
     */
    private var offsetX = 0f
    private var offsetY = 0f

    /** How a deflection becomes steps, which way they count, and how they become whole ones. */
    private val pace = CaretRate()

    private var lastFrame = 0L

    /**
     * The loop, which runs for as long as the finger is down.
     *
     * This is the whole difference between a joystick and what the space bar does. The space
     * bar maps travel to position: move the finger one step's width, the caret moves one
     * character, and a finger held still moves nothing. A joystick maps *deflection to rate*:
     * how far it is pushed says how fast the caret runs, and a finger held still off-centre
     * keeps it running. So there has to be something ticking while nothing is happening, and
     * this is it.
     */
    private val run = object : Runnable {
        override fun run() {
            if (!held) return
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = (now - lastFrame).coerceIn(0L, MAX_FRAME_MS)
            lastFrame = now
            advance(elapsed / 1000f)
            postOnAnimation(this)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        invalidate()
    }

    /**
     * What the keyboard is made of. See [KeyboardTheme].
     *
     * Only the disc this sits in cares. The face and the dot are already worked out from the
     * palette and come out in the theme's own colours without being told anything; the room
     * made for them is the one thing that has to match a ground it cannot derive.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun setMetrics(keyW: Float) {
        if (keyW == this.keyW) return
        this.keyW = keyW
        invalidate()
    }

    /** The square this wants to be: the touch circle, which is wider than the drawing. */
    fun side(): Int = (keyW * TOUCH * 2f).toInt()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Round, not square. The view has to be a rectangle and the control is a dot,
                // so the corners of that rectangle sit over `&123` and the comma - two keys
                // pressed constantly. Refusing the touch there lets it fall through to the key
                // underneath, which is what the ring of bare keyboard promised.
                if (!within(event)) return false
                held = true
                fromX = event.rawX
                fromY = event.rawY
                offsetX = 0f
                offsetY = 0f
                pace.release()
                lastFrame = android.os.SystemClock.uptimeMillis()
                // The gesture belongs to this view for as long as the finger is down. It
                // leaves the dot within a few millimetres and has to keep running.
                parent?.requestDisallowInterceptTouchEvent(true)
                KeyboardHaptics.tap(this)
                postOnAnimation(run)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!held) return false
                // The finger only says how far the stick is pushed. Nothing moves the caret
                // here - [run] does that, at whatever rate this deflection asks for, whether
                // or not the finger is still moving.
                offsetX = event.rawX - fromX
                offsetY = event.rawY - fromY
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!held) return false
                release()
                return true
            }
        }
        return false
    }

    private fun release() {
        held = false
        offsetX = 0f
        offsetY = 0f
        pace.release()
        removeCallbacks(run)
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A loop that reposts itself outlives the keyboard it was moving a caret in.
        removeCallbacks(run)
        held = false
    }

    /** Moves the caret by whatever [seconds] of the current deflection is worth. */
    private fun advance(seconds: Float) {
        val density = resources.displayMetrics.density
        val steps = pace.steps(offsetX / density, offsetY / density, seconds)
        if (steps == 0) return

        val moved = (if (pace.vertical) onDragLines else onDrag)?.invoke(steps) ?: 0
        if (moved == 0) {
            // The caret is against the end of the text - or the field will not say where its
            // lines are, which comes to the same thing here. Nothing is happening, so nothing
            // is owed and nothing is felt: a dot still ticking there would say it was moving.
            pace.stalled()
            return
        }
        // A tick for every step the caret actually takes, at any speed and on either axis.
        //
        // The rate this fires at is the rate the caret is moving at, so a stick pushed to the
        // end of its travel buzzes rather than ticks - and that is the point of it. What is
        // being reported is not each character on its own, which nobody could count at thirty
        // a second anyway; it is that the caret is *moving*, and how fast. A control that goes
        // quiet as soon as it speeds up feels like a control that has come off its mounting at
        // exactly the moment there is most to be uncertain about, and the one thing this dot
        // cannot show is the caret itself - the finger is nowhere near it.
        KeyboardHaptics.key(this)
    }

    private fun within(event: MotionEvent): Boolean =
        hypot(event.x - width / 2f, event.y - height / 2f) <= keyW * TOUCH

    override fun onDraw(canvas: Canvas) {
        if (keyW <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        // The keyboard's own ground, painted over whatever keys the ring overlaps. This is the
        // room being made: four key corners are covered by it, and what is left of them is the
        // shape that says the dot is not part of any of them.
        //
        // Which means it has to be whatever the ground actually is, and under a theme that
        // draws its own keys that is the shell's dialog colour - see
        // `KeyboardView.groundColour`. A disc of some other grey here would not read as room
        // made, it would read as a plate laid over the keys.
        paint.color =
            if (theme.drawsKeys) palette.background
            else ColorUtils.blendARGB(palette.background, palette.foreground, GROUND_ALPHA)
        canvas.drawCircle(cx, cy, keyW * HALO, paint)

        // The face, in a key's own grey, sinking a step further while it is held.
        //
        // Deliberately not the accent that everything else on this keyboard goes when a thumb
        // lands on it - see `KeyView.fillFor`. The dot riding on this face *is* the accent,
        // and a control whose handle and whose face were the same colour would be a control
        // with no visible handle at the one moment somebody is looking to see how far they
        // have pushed it.
        paint.color = ColorUtils.blendARGB(
            palette.background,
            palette.foreground,
            if (held) FACE_HELD_ALPHA else KeyView.LETTER_FILL_ALPHA
        )
        canvas.drawCircle(cx, cy, keyW * FACE, paint)

        // The dot rides toward the finger, clamped inside the ring. It is what makes the
        // control legible: a stick you can see is pushed, and pushed *that* far, rather than
        // a caret running for reasons the screen does not show.
        //
        // Along the axis the gesture is on, though, and not simply where the finger is. Only
        // one axis is ever being answered - see [CaretRate.pick] - and a dot sitting at the
        // corner of a diagonal push would be claiming both. Drawn this way the dot is the
        // one thing on screen that says which of the two you are actually doing, and a thumb
        // that is drifting off the line it meant can see it happening.
        val room = keyW * (HALO - DOT_HELD)
        val throw_ = keyW * FULL_THROW
        var slideX = offsetX / throw_
        var slideY = offsetY / throw_
        if (pace.engaged) {
            if (pace.vertical) slideX = 0f else slideY = 0f
        }
        val out = hypot(slideX, slideY)
        if (out > 1f) {
            slideX /= out
            slideY /= out
        }
        paint.color = palette.accent
        canvas.drawCircle(
            cx + slideX * room,
            cy + slideY * room,
            keyW * (if (held) DOT_HELD else DOT),
            paint
        )
    }

    private companion object {

        /**
         * The three circles, as fractions of one key's width, measured off the reference.
         *
         * A 720 pixel screenshot at ten columns puts a key at 65 pixels: the ring of bare
         * keyboard comes out at 24.5 of those, the grey face at 15, and the accent dot at 6.5.
         */
        const val HALO = 0.377f
        const val FACE = 0.231f
        const val DOT = 0.100f

        /** The dot swells a little under a thumb, since the face beneath it is sinking. */
        const val DOT_HELD = 0.130f

        /**
         * How far out from the centre a touch is taken.
         *
         * Larger than the drawing and smaller than a key. It would be tidier to give this
         * Android's 48dp minimum, and that is the wrong trade here: this dot is a handle in a
         * gutter, grabbed with a thumb and dragged rather than tapped precisely, and every
         * millimetre it claims comes off the corner of `&123`, the comma, shift or `z` - keys
         * that are pressed constantly and where a miss is a real cost. Half a key's width puts
         * the target a third wider than the ring that advertises it, which is enough to find
         * without looking and little enough to leave its neighbours alone.
         */
        const val TOUCH = 0.5f

        /** `#1A1A1A` on Dark - the keyboard's own ground. See `KeyboardView.groundColour`. */
        const val GROUND_ALPHA = 0.102f

        /**
         * The face with a thumb on it, as an alpha over the page like the fills it sits among.
         *
         * Between the keyboard's ground and the paler of the two key fills - 0.102 and 0.200 -
         * so the dot's face is darker than the keys around it while it is held and still
         * lighter than the gutter it sits in. Going past the ground would read as a hole where
         * the dot was; landing on it exactly would merge the dot into the board.
         */
        const val FACE_HELD_ALPHA = 0.14f

        /**
         * How far the finger travels for the dot to show full deflection, in key widths.
         *
         * A *drawing* number and nothing else: it says how far the finger goes before the dot
         * is against the edge of its ring, not what the caret does. Deliberately shorter than
         * [CaretRate.FULL_DP] - the dot reaches the edge before the caret reaches full speed -
         * because a stick that is still visibly travelling when it is already at its limit
         * reads as a control that has stopped responding.
         */
        const val FULL_THROW = 1.2f

        /**
         * The longest a frame is allowed to count for.
         *
         * The loop runs off the clock so that the caret's speed is the same whether the
         * keyboard is getting sixty frames a second or thirty. That is right up to a point,
         * and the point is a stall: a frame that took half a second - the field doing
         * something slow, the app being resumed - would pay out fifteen characters at once,
         * as one jump, from a stick nobody moved. Capping it makes a stall cost a little
         * travel rather than a lurch.
         */
        const val MAX_FRAME_MS = 64L
    }
}

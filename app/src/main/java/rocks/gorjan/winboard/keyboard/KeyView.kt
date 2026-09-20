package rocks.gorjan.winboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.winboard.R
import rocks.gorjan.winboard.KeyboardPalette

/**
 * One key.
 *
 * Built like the calculator's, with one deliberate difference: the label, the hint and the
 * glyph are painted straight onto the canvas rather than being child views. The calculator
 * has twenty-four keys and can afford a TextView apiece; a keyboard has forty and would want
 * two apiece for the top row, and eighty views that never change and are laid out on every
 * measure pass is a cost with nothing to show for it. Everything else here - the touch
 * target half a gap larger than the painted key, the fill driven off the raw touch stream,
 * the press as one more step along the same colour line - is the calculator's and is
 * deliberately unchanged.
 *
 * The view is *bigger than the key it draws*. The gutters between keys belong to the keys
 * either side of them, split down the middle, so that a finger landing in the gap presses
 * the key it was aimed at rather than nothing at all. Nothing about where keys appear
 * changes; only how far out from each one the touch reaches.
 */
@SuppressLint("ViewConstructor")
class KeyView(
    context: Context,
    var key: Key,
    private var palette: KeyboardPalette
) : View(context) {

    /**
     * Who to tell. A keyboard's keys do not act on their own - what a letter means depends
     * on the shift state, what backspace means depends on what is behind the cursor, and
     * both live above this.
     */
    interface Listener {

        /**
         * The finger has gone down on a key.
         *
         * Only the keys that change which keys there are act on this rather than on the tap,
         * and they have to. Typing quickly means the next finger lands before the last one
         * lifts - that overlap *is* fast typing, not an accident - so a page that changes on
         * release is still showing the old keys when the following press arrives, and `&123`
         * followed quickly by a symbol produces the letter that was in its place.
         */
        fun onKeyPress(view: KeyView)

        /** A tap: pressed and released without the hold ever firing. */
        fun onKeyUp(view: KeyView)

        /** The hold has been recognised. Whatever it opens, opens now. */
        fun onKeyLongPress(view: KeyView)

        /**
         * The finger has moved while a hold is in progress, in this key's own coordinates.
         *
         * Which is how one picks from the row a hold opens: the gesture belongs to the key
         * that was pressed - Android delivers the whole of it there - so the key has to pass
         * the movement on rather than the popup receiving its own touches.
         */
        fun onKeyDrag(view: KeyView, x: Float, y: Float)

        /**
         * The finger has come off a key whose hold had already fired.
         *
         * Separate from [onKeyUp] because the two mean opposite things: a tap types the key,
         * where releasing a hold takes whatever the hold was offering - and must not also
         * type the key underneath it.
         */
        fun onKeyRelease(view: KeyView)

        /** Fired repeatedly while a key that repeats is held. Backspace, and nothing else. */
        fun onKeyRepeat(view: KeyView)

        /**
         * A finger is dragging the caret: along the space bar, or on the joystick.
         *
         * A [View] rather than a [KeyView] because the two controls that do this are not both
         * keys - see [JoystickView].
         *
         * @param steps characters to move, negative for left. A delta rather than a position,
         *   because the caret is the field's and where it ends up is the field's business -
         *   a line ending, a chip, an emoji that is two code units wide. The keyboard says
         *   "one to the left" and lets the field work out what that means.
         * @return how many characters the caret **actually** moved, which is not always what
         *   was asked for: the field runs out of text. The caller wants this because it is the
         *   one that decides about feedback, and both of them decide the same way - a tick per
         *   character that moves, and nothing at all against the end of the text, where a
         *   keyboard still ticking would be saying the caret was moving when it was not. It is
         *   still returned rather than acted on here, because what a control does about it is
         *   the control's own business.
         */
        fun onCursorSlide(view: View, steps: Int): Int

        /**
         * And the same finger pushing the joystick up or down, so the caret changes line.
         *
         * Only the joystick: a space bar reports one axis because it is already using the
         * other one to be a key.
         *
         * @param lines lines to move, negative for up. The column the caret lands in is the
         *   one it came from, which is the far end's business rather than this one's - it is
         *   the only side that can see the text.
         * @return how many lines it **actually** moved, on the same terms as [onCursorSlide]
         *   and for the same reason. Zero at the top and the bottom of the text, and zero in
         *   a field that will not say where its own lines are, which is a state the caller
         *   cannot tell from a wall and does not need to.
         */
        fun onCursorLines(view: View, lines: Int): Int
    }

    var listener: Listener? = null

    /**
     * Told whenever this key starts or stops looking pressed.
     *
     * The grid's, not the service's, and separate from [Listener] because it is a different
     * kind of fact: [Listener] is about what a key *means* - a letter typed, a hold opened -
     * where this is only about what it looks like. The preview flag hangs off the pressed
     * fill and must hang off exactly that, because "lit" is the one state that is already
     * correct in every case the touch handling has: a finger that slides off the key, a
     * gesture cancelled under it, a grid hidden mid-press. Hooking the flag to [Listener]
     * instead left a letter floating over the keyboard whenever a finger wandered off a key
     * before lifting, which fires no callback at all.
     */
    var onPressedChanged: ((KeyView, Boolean) -> Unit)? = null

    /** Set by the grid on every measure pass; everything sizes itself off these. */
    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f

    /**
     * How long this key must be held before it offers what is behind it.
     *
     * A setting rather than a constant, because the right value is a property of the hand
     * and not of the keyboard: too short and reaching for a letter produces its symbol, too
     * long and the symbol feels withheld.
     */
    var holdMillis: Long = DEFAULT_HOLD_MS

    /**
     * Whether a drag along the space bar moves the caret.
     *
     * Off when the joystick is on, which is the whole of that setting's second half. Two
     * controls for one job is not twice as good: the space bar's version costs a slop test on
     * every space typed, and a thumb rolling off the key still occasionally moves the caret
     * when it meant to type one. Once there is a control that does nothing else, that cost
     * buys nothing and is simply dropped. See [JoystickView].
     */
    var caretSlide: Boolean = true

    /**
     * Whether a hold on the space bar offers the other languages.
     *
     * Pushed down from the grid, and false whenever there is only one language turned on:
     * this is what arms the hold at all, so a keyboard that speaks one language never times
     * one on the space bar and the key is exactly what it always was. See [LanguagesPopup].
     *
     * It shares the key with the caret slide, and the two do not get in each other's way.
     * A slide is a sideways movement that begins within a few dp - well before a hold could
     * fire - and starting one cancels the hold; a hold is the finger *not* moving for a
     * quarter of a second, and once it has fired the slide is not offered again. Which is
     * the right way round: a thumb resting on the space bar is a thumb asking for neither,
     * and the one that moves first is the one that was meant.
     */
    var languageSwitch: Boolean = false

    /**
     * Whether a hold on this key opens a page the finger then slides across.
     *
     * The setting, pushed down from the grid; [slides] is what decides whether *this* key is
     * one of the two it applies to. It has to reach this far down because it changes when a
     * hold is armed at all: shift already waited for one to lock itself, but `&123` did not
     * wait for anything, and a key whose hold is never timed can never start a gesture.
     */
    var slideSelect: Boolean = false

    /**
     * Whether a hold on this key starts a slide across the keyboard.
     *
     * The two keys that bring up a different set of keys, and only those. A slide is worth
     * having wherever the key you actually want is one the keyboard is not currently showing,
     * which is exactly what these two are for and is not true of any other key.
     */
    fun slides(): Boolean =
        slideSelect && (key.action == Action.SHIFT || key.action == Action.SYMBOLS)

    /** Whether a hold on this key opens the language list. See [languageSwitch]. */
    fun opensLanguages(): Boolean = languageSwitch && key.action == Action.SPACE

    /**
     * The Windows flag, for the one key that wears it. See [wearsFlag].
     *
     * Not [glyph], and the difference is the whole reason it is here: a glyph on this
     * keyboard is a single-colour mark that is tinted to whatever the key's ink happens to
     * be - white on an accent, black on the beige - and the flag is four colours that mean
     * nothing repainted in one. So it is drawn separately, untinted, and the label it stands
     * in for is suppressed rather than drawn underneath it.
     *
     * A vector, so it is drawn at whatever the key happens to measure rather than scaled to
     * it. It was a sixty-five pixel crop off the Start button for a while and had to be drawn
     * with filtering off, because at that size there was no detail for a smooth scale to
     * keep - only edges for it to blur. That trade is gone.
     *
     * Read the first time it is asked for rather than in the grid with the other glyphs,
     * because exactly one key in a layout can ever want it and the grid would be building it
     * for all thirty. `getDrawable` goes through the resource cache, so the parse happens
     * once for the process however many keyboards are built.
     */
    private val flag: Drawable? by lazy { context.getDrawable(R.drawable.win98_flag) }

    /**
     * Whether this key is the `&123` key on a Windows 98 keyboard, and so wears the flag.
     *
     * Three conditions and each is doing work. The theme, because only Windows 98 has a mark
     * of its own here - see [KeyboardTheme.flag]. The action, because the
     * second `&123` on the symbol pages is the one that flips between them rather than the
     * one that brings them up - the flag is the way *in*, the way a Start button was. And
     * the label, because the same key says `abc` once you are on a symbol page, and a flag
     * there would be a picture of Windows meaning "back to the letters".
     */
    private fun wearsFlag(): Boolean =
        theme.flag &&
            key.action == Action.SYMBOLS &&
            key.label == Layouts.SYMBOLS_LABEL

    /** The glyph, for the keys that have one. Parsed once - see the note in the grid. */
    var glyph: Drawable? = null
        set(value) {
            field = value
            tintGlyph()
            invalidate()
        }

    /**
     * A small drawing in the corner, where a hint character would otherwise go.
     *
     * The comma's smiley. Writing it as a character - `☺` - looked right in the source and
     * came out as a full-colour emoji on the key, because that is what the system font does
     * with it: a yellow face among a keyboard of thin white marks. A corner mark has to be
     * drawn from the same icon set as every other glyph here, in the same colour, at the same
     * weight, or it is not a corner mark but a picture stuck on a key.
     */
    var hintGlyph: Drawable? = null
        set(value) {
            field = value
            value?.setTint(hintColour())
            invalidate()
        }

    /**
     * Whether shift is on, which changes what a letter key shows.
     *
     * Held here rather than asked for at paint time so that a shift press repaints
     * thirty keys with one field write each instead of thirty lookups.
     */
    private var shifted = false

    /** Painted its pressed fill. Kept rather than read back off the view - see [light]. */
    private var lit = false

    /**
     * Whether to draw a bar under the glyph. Caps lock, and nothing else.
     *
     * A keyboard whose shift is on for one letter and one whose shift is on until told
     * otherwise are two different keyboards, and the difference has to be visible or every
     * accidental double tap turns into A SENTENCE LIKE THIS. The phone marked it under the
     * arrow rather than by swapping the arrow, so the key does not appear to change shape
     * as it changes state - which is also why this is drawn by the key rather than being a
     * second glyph: one arrow, with or without a line beneath it.
     */
    var underline = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * What the keys are made of. See [KeyboardTheme].
     *
     * Pushed down from the grid with the rest of the settings rather than arriving with the
     * palette, because that is what it is: a keyboard setting, written in this process and
     * applied the same way the key height and the hold are. The colours it also changes do
     * come through the palette - see [KeyboardAppearance.keyboardPalette] - and the two meet
     * here, which is why this repaints rather than only remembering.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            face.color = fillFor(lit)
            tintGlyph()
            invalidate()
        }

    private val face = Paint()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

    private val bounds = Rect()

    private val labelFont = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
    private val hintFont = ResourcesCompat.getFont(context, R.font.segoeui_regular)

    /**
     * Tahoma, for the two themes set in it. See [KeyboardTheme.tahoma].
     *
     * Windows 98's own face was MS Sans Serif, which is a bitmap font and has no business on
     * a screen at this density - it exists at eight sizes and nothing in between. Tahoma is
     * what Microsoft replaced it with, and is the face anybody picturing that shell is
     * actually picturing.
     */
    private val classicFont = ResourcesCompat.getFont(context, R.font.tahoma)

    /** Cleared on release, so a hold that has already fired does not also commit a letter. */
    private var longPressFired = false
    private var repeatCount = 0

    private val holdCheck = Runnable { fireLongPress() }
    private val repeat = object : Runnable {
        override fun run() {
            // Marked as having fired for the same reason a hold is: a key that has already
            // acted must not act once more when the finger comes off it. Without this a
            // backspace held long enough to clear a word eats one further character on
            // release, which reads as the key overshooting rather than as a second press.
            longPressFired = true
            // Once, as the run begins. A tick under every character would be a rattle.
            if (repeatCount == 0) KeyboardHaptics.tap(this@KeyView)
            listener?.onKeyRepeat(this@KeyView)
            repeatCount++
            postDelayed(this, repeatDelay())
        }
    }

    init {
        isClickable = true
        face.color = fillFor(pressed = false)
    }

    /**
     * Points this view at a different key.
     *
     * What lets the grid change layout without being torn down and built again. A key view
     * holds nothing that belongs to one particular key except the key itself: the paints, the
     * fonts and the touch handling are the same whether the face says `q` or `#`.
     *
     * The transient touch state is cleared with it, and that is not tidiness. This runs while
     * a finger is still on the way up from `&123` - which is the very view most likely to be
     * reused as the first symbol key - and a `longPressFired` or a half-finished slide left
     * over from the press that caused the switch would be read as belonging to the new key.
     */
    fun bind(next: Key) {
        cancelHold()
        longPressFired = false
        repeatCount = 0
        sliding = false
        if (lit) {
            lit = false
            onPressedChanged?.invoke(this, false)
        }
        key = next
        face.color = fillFor(false)
        tintGlyph()
        invalidate()
    }

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        face.color = fillFor(lit)
        tintGlyph()
        invalidate()
    }

    fun setMetrics(keyW: Float, keyH: Float, gap: Float) {
        this.keyW = keyW
        this.keyH = keyH
        this.gap = gap
    }

    fun setShifted(on: Boolean) {
        if (on == shifted) return
        shifted = on
        // Only the keys whose face actually changes need repainting. A function key's does
        // not, and a symbol key's does not either because its shifted form is itself.
        if (key.action == null) invalidate()
    }

    /** What this key would commit right now, shift included. */
    fun output(): String =
        if (shifted) key.shifted ?: key.output.uppercase() else key.output

    /**
     * What a hold on this key offers, or empty if nothing.
     *
     * The hint comes first because on the top row the number is what the hold is *for* - the
     * accents behind `e` are a bonus, the `3` above it is the reason the row has hints at all.
     *
     * A key that does something rather than typing something has nothing to offer, whatever
     * is written in its corner.
     */
    fun alternates(): String = when {
        key.action != null -> ""
        // A key whose hold does something has no characters behind it, whatever is drawn in
        // its corner: the comma's smiley is a picture of what the hold opens, and the full
        // stop's ellipsis is a promise that something is behind the key. Neither is a
        // character to type - without this line, holding the full stop types an ellipsis.
        key.holdAction != null -> ""
        else -> (key.hint ?: "") + key.alternates
    }

    // ---------------------------------------------------------------- touch

    /**
     * Sliding along the space bar to move the caret.
     *
     * Putting the caret where you want it on a phone is genuinely hard - the target is a gap
     * between two letters, under a fingertip that covers eight of them - and this is the
     * answer every keyboard has settled on: the widest key on the board, which you are
     * already resting a thumb on, doubles as the control. Drag it and the caret follows.
     *
     * Two things make it feel right rather than annoying. It takes a deliberate sideways
     * movement to start ([SLIDE_SLOP_DP]), so an ordinary tap that wanders two pixels still
     * types a space. And once it has started, the space is *not* typed on release - the
     * gesture was a caret movement and ending it must not also leave a space behind, which is
     * the single most irritating way to get this wrong.
     */
    private var slideFrom = 0f
    private var slideFromY = 0f

    /** How many characters the caret has already been moved this gesture. */
    private var slideSteps = 0

    private var sliding = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                light(true)
                longPressFired = false
                repeatCount = 0
                slideFrom = event.rawX
                slideFromY = event.rawY
                slideSteps = 0
                sliding = false
                // The gesture is this key's for as long as the finger is down, wherever it
                // goes. A slide along the space bar leaves the key almost immediately and
                // must keep running; so must a hold that reaches up into the row of
                // alternates it opened. Neither is a scroll somebody else should be claiming.
                parent?.requestDisallowInterceptTouchEvent(true)
                KeyboardHaptics.key(this)
                KeyboardSounds.key(key.action)
                listener?.onKeyPress(this)
                if (key.action == Action.BACKSPACE) {
                    postDelayed(repeat, FIRST_REPEAT_MS)
                } else if (alternates().isNotEmpty() || key.holdAction != null ||
                    key.action == Action.SHIFT || slides() || opensLanguages()
                ) {
                    postDelayed(holdCheck, holdMillis)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Not once the hold has fired: the finger is steering the language list by
                // then, and the same movement read as a caret slide would walk the caret
                // across the text behind it - and take the space bar's light out from under
                // the very list it is holding open.
                if (!longPressFired && key.action == Action.SPACE && caretSlide && slide(event)) {
                    return true
                }
                if (longPressFired) {
                    // The hold is running, so the finger is allowed to leave the key - that
                    // is how you reach along the row it opened. The key stays lit as the
                    // thing the row belongs to, and the movement is passed on rather than
                    // being read as the finger giving up.
                    listener?.onKeyDrag(this, event.x, event.y)
                    return true
                }
                val inside = within(event)
                if (!inside) cancelHold()
                light(inside)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val inside = within(event)
                cancelHold()
                light(false)
                // A gesture, not a key. Typing a space at the end of it would undo the very
                // placing the gesture was for.
                if (sliding) {
                    sliding = false
                    return true
                }
                // A tap and the end of a hold are different events, and a hold must not also
                // type the letter that was being held.
                if (longPressFired) listener?.onKeyRelease(this)
                else if (inside) listener?.onKeyUp(this)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                light(false)
                sliding = false
                if (longPressFired) listener?.onKeyRelease(this)
                return true
            }

        }
        return super.onTouchEvent(event)
    }

    /**
     * The space bar has moved under the finger.
     *
     * Measured in raw screen coordinates rather than the view's own, because the view's are
     * relative to a key that is 3.9 units wide and the gesture routinely runs off the end of
     * it - and a finger that leaves the key should keep moving the caret, not stop dead at
     * the edge of a key nobody is looking at.
     *
     * Counted from where the finger started rather than from the last event, so the caret and
     * the finger never drift apart: the total travel decides the total movement, and rounding
     * cannot accumulate. Sliding back the way you came walks the caret back, which is what
     * makes overshooting recoverable.
     *
     * @return true when this is a caret slide and the ordinary key handling should be skipped.
     */
    private fun slide(event: MotionEvent): Boolean {
        val travel = event.rawX - slideFrom
        val density = resources.displayMetrics.density
        if (!sliding) {
            if (kotlin.math.abs(travel) < SLIDE_SLOP_DP * density) return false
            // And it has to be a sideways gesture, not a thumb rolling off the key. A press
            // that comes off at an angle covers ground horizontally without anybody meaning
            // it to, and distance alone cannot tell that from a deliberate slide - direction
            // can. Asked only at the moment the gesture starts: once it is a slide it stays
            // one, because a finger tracking along a row drifts vertically without meaning
            // that either.
            if (kotlin.math.abs(travel) < kotlin.math.abs(event.rawY - slideFromY)) return false
            sliding = true
            cancelHold()
            // Nothing is being typed any more, so nothing should look pressed either.
            light(false)
        }
        val wanted = (travel / (SLIDE_STEP_DP * density)).toInt()
        if (wanted != slideSteps) {
            // One tick per character the caret actually passes, which is what makes the slide
            // feel like it is moving over something - and none at all once it has run off the
            // end of the text, which is what the returned count is for.
            val moved = listener?.onCursorSlide(this, wanted - slideSteps) ?: 0
            if (moved != 0) KeyboardHaptics.key(this)
            slideSteps = wanted
        }
        return true
    }

    /**
     * A hold has been recognised.
     *
     * Answered with the heavier of the two ticks. A hold is a different event from a tap -
     * it produces a different character, or locks shift - and it happens at a moment when
     * the finger has stopped moving and there is nothing else to say so. It is the same
     * waveform the framework fires by itself when a view claims a long press, which is what
     * makes holding a key here feel like holding anything else on the phone.
     */
    private fun fireLongPress() {
        longPressFired = true
        KeyboardHaptics.tap(this)
        listener?.onKeyLongPress(this)
    }

    private fun cancelHold() {
        removeCallbacks(holdCheck)
        removeCallbacks(repeat)
    }

    /**
     * How fast a held backspace runs.
     *
     * It accelerates, because a hold on backspace is almost always aimed at a whole word or
     * a whole line rather than at some particular number of characters, and a constant rate
     * fast enough to clear a line is too fast to stop on a word.
     */
    private fun repeatDelay(): Long =
        if (repeatCount < REPEAT_RAMP) SLOW_REPEAT_MS else FAST_REPEAT_MS

    /**
     * Lit by the grid rather than by a finger of its own.
     *
     * For the key a slide is currently over. The finger is on shift or `&123` - Android
     * delivers the whole gesture there, wherever it travels - so the key being pointed at is
     * one that has never been touched and would otherwise have no way of showing it. It is
     * the ordinary pressed fill and not a mark of its own, because the key *is* pressed as
     * far as the person doing it is concerned; the only thing that is unusual is which view
     * the touch is arriving at.
     */
    fun highlight(on: Boolean) = light(on)

    private fun light(on: Boolean) {
        if (on == lit) return
        lit = on
        onPressedChanged?.invoke(this, on)
        face.color = fillFor(on)
        // The glyph is a Drawable and carries its own colour, so unlike the text it has to
        // be told; [onFace] has just changed answer underneath it.
        tintGlyph()
        invalidate()
    }

    private fun within(event: MotionEvent): Boolean =
        event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelHold()
    }

    // ---------------------------------------------------------------- paint

    /**
     * What the key is painted.
     *
     * Both greys are the foreground colour at a low alpha over the background rather than
     * two fixed values, which is what lets one keyboard answer for both the Dark and the
     * Light setting: on Dark they come out the `#333333` and `#4D4D4D` the phone used, and
     * on Light they come out the matching pair the other way up.
     */
    @ColorInt
    private fun fillFor(pressed: Boolean): Int {
        // A key the theme draws for itself is not filled from here at all: what it is made
        // of, pressed or not, is that shell's business and is answered in [KeyChrome.key].
        // See [drawFace], which hands the whole rectangle over rather than colouring it in.
        if (theme.drawsKeys) return palette.background
        // A pressed key lights up in the accent, which is what the phone did and what
        // everything else on this keyboard was already doing: the suggestion under a finger,
        // the emoji under a finger and the chosen cell of the alternates row are all painted
        // [KeyboardPalette.accent] at the moment they are touched. A key that answered a thumb
        // with a grey instead was the one pressable surface here speaking a different
        // language, and it is the surface people touch most.
        //
        // It is loud - a saturated colour flashing under every letter at typing speed is the
        // brightest thing on the screen, over and over - and that is a fair description of
        // the phone this keyboard is of. Quiet was tried and is not what was asked for.
        if (pressed) return pressedFill()
        return when (key.style) {
            Style.ACCENT -> palette.accent
            Style.LETTER -> blend(LETTER_FILL_ALPHA)
            Style.FUNCTION -> blend(FUNCTION_FILL_ALPHA)
        }
    }

    /**
     * The accent, except on a key that is already wearing it.
     *
     * Shift while shift is on is painted the accent standing still - see
     * `KeyboardView.applyShift` - so pressing it in the same colour it already is would be a
     * key that does not answer at all, and shift is precisely the key somebody presses when
     * they are unsure whether it took. It sinks toward the page's own background instead:
     * still plainly the accent, visibly pushed in, and it moves the right way on both the
     * Dark setting and the Light one without either being spelled out.
     */
    @ColorInt
    private fun pressedFill(): Int =
        if (key.style == Style.ACCENT) {
            ColorUtils.blendARGB(palette.accent, palette.background, PRESSED_ACCENT_SINK)
        } else {
            palette.accent
        }

    @ColorInt
    private fun blend(alpha: Float): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    /**
     * What colour the letter, glyph or hint on this key is.
     *
     * White on the accent, and the page's own foreground on a grey. Which of the two a key is
     * wearing is not a property of the key but of the moment: an accent-styled key is on the
     * accent always, an ordinary one only while a thumb is on it, and both want the same
     * white when they are.
     */
    @ColorInt
    private fun onFace(): Int {
        // The shell's own text colour, pressed or not. None of these three answers a press by
        // filling the key with a colour that would need white on it: 98 turns its bevel
        // inside out, XP dips into the ground's blue, and Vista's blue is pale enough to read
        // black on - which is what Vista itself wrote on it.
        //
        // The chrome's ink rather than the palette's, because the key is not the ground. See
        // [KeyChrome.ink]: under Luna the ground is the taskbar's blue with white on it and
        // the keys are white with black on them, and one value cannot be both.
        theme.chrome?.let { return it.ink }
        return if (lit || key.style == Style.ACCENT) palette.onAccent() else palette.foreground
    }

    private fun tintGlyph() {
        glyph?.setTint(onFace())
    }

    /**
     * How large a square this key's glyph is fitted into, as a fraction of one key's width.
     *
     * One number will not do. Every icon is fitted inside a square and centred, so what you
     * actually see is the square's size times however much of its own viewport the drawing
     * fills - and that differs a lot between them: the backspace tag runs nearly wall to
     * wall in its box where the shift arrow uses barely a third of the width of its own.
     * Fitting them all into the same square would draw the arrow half the size of the tag,
     * which is not what the phone did. These are the sizes that make the *drawn* glyphs come
     * out at the sizes measured off the phone.
     */
    private fun glyphScale(): Float = glyphFit ?: when (key.action) {
        Action.SHIFT -> SHIFT_GLYPH
        Action.BACKSPACE -> BACKSPACE_GLYPH
        Action.ENTER -> ENTER_GLYPH
        else -> ROUND_GLYPH
    }

    /**
     * The square to fit the glyph into, when the action's own answer is the wrong one.
     *
     * [glyphScale] decides by action because ordinarily the action decides the drawing. There
     * is one key where it does not: enter, on a field that asked for a search, wears the
     * magnifier - which is one of the appbar icons and fills its viewport like the rest of
     * them, not like the return arrow it stands in for. Null for every other key on the
     * board, which is the point: this is an exception and reads as one.
     */
    var glyphFit: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * What everything drawn on the key is sized against.
     *
     * A key's width, ordinarily - the whole keyboard is proportioned that way. But that only
     * holds while keys are taller than they are wide, which they are on every layout with ten
     * or twelve columns. The number pad has four, so its keys are half again wider than they
     * are tall, and sizing their digits off the width gave characters the size of a thumb.
     *
     * So a key that is wider than it is tall is measured more modestly. The trigger is the key
     * itself rather than which layout it belongs to, which means a wide key gets sensible text
     * wherever one turns up.
     */
    /**
     * What everything drawn on the key is sized against.
     *
     * A key's width, normally, because that is the unit the whole keyboard is written in. But
     * a key can be wider than it is tall - the keypads' keys are, and in landscape *every*
     * key is - and there the width says nothing about how much room there is to draw in. The
     * height is the constraint then, so the smaller of the two decides, and a fixed fraction
     * of the width is used for keys that are only slightly wide.
     */
    private fun textUnit(): Float = if (keyW > keyH) minOf(keyW * WIDE_KEY_SCALE, keyH) else keyW

    /**
     * Where the key's character sits.
     *
     * Centred on its own ink, which is what makes a quote and a comma both look middled on a
     * key despite living at opposite ends of the line - see the long note at the call site.
     *
     * Except for the characters in [ON_THE_LINE], where that rule destroys the character. An
     * underscore is *defined* by sitting below the baseline; centre its ink in the key and it
     * becomes a dash with delusions - which on the symbol page is a real problem, because the
     * hyphen is two keys away and looked exactly the same. Those are placed on the line they
     * would sit on in a word instead, which is the only place an underscore means anything.
     */
    private fun labelBaseline(label: String): Float {
        if (label in ON_THE_LINE) {
            val metrics = ink.fontMetrics
            return faceCentreY - (metrics.ascent + metrics.descent) / 2f
        }
        return faceCentreY - (bounds.top + bounds.bottom) / 2f + labelDrop()
    }

    /**
     * How far below the middle a key's character sits.
     *
     * Optically rather than geometrically centred. A key with a mark in its top corner is not
     * a blank rectangle, and a letter placed at the true centre of one reads as sitting high,
     * because the eye takes the corner mark as part of the top of the key and centres what is
     * left. A few pixels down is what makes it look centred.
     *
     * Which is the whole of the reason, so a key with no corner mark does not get it. `abc` on
     * the emoji panel is the case that showed this up - a short, wide key with nothing in its
     * corner, where five pixels of a correction for something that is not there is simply five
     * pixels low. Geometry is already right when there is nothing to correct for.
     */
    private fun labelDrop(): Float =
        if (key.hint == null && hintGlyph == null) 0f
        else LABEL_DROP_DP * resources.displayMetrics.density

    /** What a corner mark is painted, whether it is a character or a drawing. */
    @ColorInt
    private fun hintColour(): Int =
        // A solid shade rather than a faded black under the Windows themes. Every one of
        // those shells dimmed a label with `COLOR_GRAYTEXT` - a colour of its own, not its
        // ink at half strength - and the corner mark being exactly that is what makes it read
        // as part of the button rather than as ink that has not printed properly.
        theme.chrome?.subtleInk
            ?: ColorUtils.setAlphaComponent(onFace(), (255 * HINT_ALPHA).toInt())

    /** How far the painted key sits inside the touchable bounds: half of the gutter. */
    private val inset get() = gap / 2f

    /**
     * Touchable area above the face that is deliberately not painted. See [Key.overhangTop].
     *
     * The space bar reaches up into the row above so that a thumb aiming at it and landing on
     * the `n` still gets a space. Everything drawn on the key measures from [faceTop] rather
     * than from the view's own top, so the key looks exactly where it always did while being
     * bigger than it looks.
     */
    var overhang = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The top of the painted key, which is not the top of the view. */
    private val faceTop get() = inset + overhang

    /** The middle of the painted key, which is what everything on it is centred on. */
    private val faceCentreY get() = (faceTop + (height - inset)) / 2f

    /**
     * The key itself, before anything is written on it.
     *
     * Flat under the phone's theme, which is one rectangle and the whole of what a Windows
     * Phone key ever was. Under any of the others it is a *button*, and what a button is made
     * of belongs to the shell it came from rather than to this view - so the rectangle is
     * handed over whole and [KeyChrome.key] answers for all of it, the fill included.
     *
     * A key wearing [Style.ACCENT] - shift, while shift is on - goes over as the dialog's
     * default button rather than as an accent fill, because that is how every one of these
     * shells said "this is the one that is armed".
     */
    private fun drawFace(canvas: Canvas) {
        val left = inset
        val top = faceTop
        val right = width - inset
        val bottom = height - inset
        val chrome = theme.chrome
        if (chrome == null) {
            canvas.drawRect(left, top, right, bottom, face)
            return
        }
        chrome.key(
            canvas, left, top, right, bottom,
            resources.displayMetrics.density,
            pressed = lit,
            default = key.style == Style.ACCENT
        )
    }

    override fun onDraw(canvas: Canvas) {
        drawFace(canvas)
        if (keyW <= 0f) return

        // Windows 98's button moved what was written on it a pixel down and to the right,
        // along with the light - the label went into the screen with the face it is written
        // on, and a bevel that inverted while the letter stayed put looked like a hole with
        // something floating over it. Everything below is drawn through this, so the glyph,
        // the label, the caps-lock bar and the corner mark all move together. The other
        // themes answer a press with colour and hand back zero; see [KeyChrome.shove].
        val shove =
            if (lit) theme.chrome?.shove(resources.displayMetrics.density) ?: 0f else 0f
        if (shove != 0f) {
            canvas.save()
            canvas.translate(shove, shove)
        }

        glyph?.let {
            val side = (textUnit() * glyphScale()).toInt()
            val left = (width - side) / 2
            val top = (faceCentreY - side / 2f).toInt()
            it.setBounds(left, top, left + side, top + side)
            it.draw(canvas)
        }

        if (wearsFlag()) flag?.let {
            // Sized by height and widened to its own proportions, unlike every other mark
            // here, which is fitted into a square. The flag is wider than it is tall - the
            // trail behind it sees to that - and squaring it would be the one way to make it
            // look like a different logo.
            val tall = textUnit() * FLAG_GLYPH
            val ratio =
                if (it.intrinsicHeight > 0) it.intrinsicWidth.toFloat() / it.intrinsicHeight
                else 1f
            val wide = tall * ratio
            val left = (width / 2f - wide / 2f).toInt()
            val top = (faceCentreY - tall / 2f).toInt()
            it.setBounds(left, top, left + wide.toInt(), top + tall.toInt())
            it.draw(canvas)
        }

        if (underline) {
            ink.color = onFace()
            val half = textUnit() * UNDERLINE_W / 2f
            val y = faceCentreY + textUnit() * UNDERLINE_DROP
            canvas.drawRect(
                width / 2f - half, y,
                width / 2f + half, y + textUnit() * UNDERLINE_H,
                ink
            )
        }

        // Empty under the flag: it is standing in for these very words. See [wearsFlag].
        val label = if (wearsFlag()) "" else faceLabel()
        if (label.isNotEmpty()) {
            // The space bar says which language you are typing in, and says it the way the
            // corner marks do - the smaller face, the lighter grey - because it is the same
            // kind of statement. It is a label on the keyboard rather than a character the
            // key produces, and a key whose face reads `English` in the weight of a letter
            // looks like a key that types the word.
            val quiet = key.action == Action.SPACE
            ink.typeface = if (quiet) quietFace() else labelFace()
            ink.textSize = textUnit() * when {
                quiet -> HINT_TEXT
                key.action == null -> LABEL_TEXT
                else -> FUNCTION_LABEL_TEXT
            }
            ink.color = if (quiet) hintColour() else onFace()
            ink.textAlign = Paint.Align.CENTER
            // Centred on the ink the glyph actually puts on the key, not on the font's line
            // box, which carries a lot of empty space above a Segoe lowercase and would sit
            // every letter low.
            //
            // Both edges of that ink have to be used, not just its height. The bounds come
            // back relative to the baseline, so a quote - which lives entirely above the
            // baseline - has a box that is 12 tall but sits 30 above it, and centring by
            // height alone puts it near the top of the key. A comma, which hangs below,
            // comes out equally far low. Halving the sum of the two edges is what centres
            // the box itself, and it gives the same answer as the naive version for the
            // letters that do sit on the baseline.
            ink.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, width / 2f, labelBaseline(label), ink)
        }

        hintGlyph?.let { mark ->
            val side = (textUnit() * HINT_GLYPH).toInt()
            val left = (inset + textUnit() * HINT_PAD).toInt()
            val top = (faceTop + textUnit() * HINT_PAD).toInt()
            mark.setTint(hintColour())
            mark.setBounds(left, top, left + side, top + side)
            mark.draw(canvas)
        }

        key.hint?.let { hint ->
            ink.typeface = quietFace()
            ink.textSize = textUnit() * HINT_TEXT
            ink.color = hintColour()
            ink.textAlign = Paint.Align.LEFT
            ink.getTextBounds(hint, 0, hint.length, bounds)
            // Hung from the top of its ink rather than from its height.
            //
            // The same trap the label fell into, and worse here because the corner has no
            // room to spare. `bounds` is measured from the baseline, so a quote - which sits
            // thirty units above it and is only eight tall - would be placed by its height
            // and drawn thirty above that, off the top of the key entirely. Subtracting the
            // top edge instead puts the first ink exactly on the padding, whether the mark
            // is a digit, a quote, or an underscore that hangs below the line.
            canvas.drawText(
                hint,
                inset + textUnit() * HINT_PAD,
                faceTop + textUnit() * HINT_PAD - bounds.top,
                ink
            )
        }

        if (shove != 0f) canvas.restore()
    }

    /** The face a key's own character is set in. */
    private fun labelFace() = if (theme.tahoma) classicFont else labelFont

    /** And the face of everything that is a note about the key rather than the key: see [classicFont]. */
    private fun quietFace() = if (theme.tahoma) classicFont else hintFont

    /**
     * What is written across the key's middle.
     *
     * Empty for a key that carries a glyph instead, and empty for space - the space bar says
     * the language's name, but that is set as a label by the grid when more than one
     * language is on, and is otherwise blank the way the phone had it.
     */
    private fun faceLabel(): String = when {
        glyph != null -> ""
        key.action == Action.SPACE -> key.label.takeIf { it != " " } ?: ""
        key.action != null -> key.label
        shifted -> key.shifted ?: key.label.uppercase()
        else -> key.label
    }

    companion object {

        /** The two greys, as the alpha that produces them over black. See [fillFor]. */
        const val LETTER_FILL_ALPHA = 0.200f      // #333333 on Dark
        const val FUNCTION_FILL_ALPHA = 0.302f    // #4D4D4D on Dark

        /**
         * How far a key that is already the accent sinks when it is pressed. See [pressedFill].
         *
         * A third of the way to the page, which is enough to be unmistakable at a glance and
         * little enough that the key is still obviously the same colour it was. It cannot be
         * a step toward white or toward black, because it has to darken the accent on the
         * Dark setting and lighten it on the Light one; toward the background is both.
         */
        const val PRESSED_ACCENT_SINK = 0.33f

        /**
         * Text and glyph sizes, as fractions of one key's width.
         *
         * Most were measured off the reference rather than chosen: a lowercase `o` eighteen
         * pixels tall on a sixty-one pixel key, `&123` set across forty-four. Working back
         * from the drawn size to the font size is why they are not round numbers.
         *
         * The corner marks are the exception and are a fifth larger than the phone drew them.
         * They carry more here than they did there - the phone marked only the number row,
         * where this marks a symbol on every letter and the emoji panel on the comma - so
         * they have to be readable at a glance rather than merely present.
         */
        const val LABEL_TEXT = 0.57f
        const val FUNCTION_LABEL_TEXT = 0.36f
        const val HINT_TEXT = 0.36f

        /** See [glyphScale]. Four icons, four sizes, all landing on the measured drawing. */
        const val SHIFT_GLYPH = 0.86f
        const val BACKSPACE_GLYPH = 0.65f
        const val ENTER_GLYPH = 0.59f

        /** The emoji and globe keys, whose drawings do fill their own boxes. */
        const val ROUND_GLYPH = 0.56f

        /**
         * The magnifier and the arrow the enter key wears for a field's action.
         *
         * Half again the size of the other appbar drawings, and the exception is earned. Every
         * other glyph on this keyboard shares its key with nothing, so it can be drawn at the
         * weight of a letter and read as one mark among thirty. These two stand in for a
         * *word* - they are where `search` and `go` used to be spelled out - and a picture the
         * size of a letter where a word was reads as a smaller key, not as a shorter label.
         * The enter key is also the widest key on the row, so there is room for it.
         *
         * See [glyphFit], which is how this reaches the one key that uses it.
         */
        const val ENTER_MARK_GLYPH = ROUND_GLYPH * 1.5f

        /**
         * How tall the Windows flag is drawn, as a fraction of a key's width.
         *
         * Taken from the words it replaces rather than from the other glyphs: `&123` is set
         * at [FUNCTION_LABEL_TEXT] and a mark standing in for a label has to carry the same
         * weight on the row, which a shift arrow sitting alone on its key does not. A little
         * larger than the text, because a drawing reads smaller than letters of the same
         * height do.
         *
         * It was three quarters, reasoning from the proportion the Start button gave it. That
         * was the wrong button to reason from: the Start button is short and wide and the
         * flag nearly filled its height, where this key is a tall one at the end of a row and
         * the same fraction left the mark standing too high in it. A fifth off, and the width
         * comes down with it - the drawing keeps its own shape, which is the one thing about
         * it that is not adjustable.
         */
        const val FLAG_GLYPH = 0.60f

        /** The caps-lock bar: how wide, how far below the middle, how thick. */
        const val UNDERLINE_W = 0.34f
        const val UNDERLINE_DROP = 0.26f
        const val UNDERLINE_H = 0.05f

        /** Where the hint sits in from the key's top-left corner: eight pixels of sixty-one. */
        const val HINT_PAD = 0.12f

        /** See [labelDrop]. In dp, because it is a correction for the eye, not a proportion. */
        const val LABEL_DROP_DP = 5f

        /** A drawn corner mark, sized to sit where a hint character's ink would. */
        const val HINT_GLYPH = 0.31f

        /**
         * How much of its width a key wider than it is tall measures its contents against.
         *
         * A third off, which is what the number pad's digits needed. See [textUnit].
         */
        const val WIDE_KEY_SCALE = 0.67f

        /**
         * How faint the hint is.
         *
         * The phone's was `#808080` on a `#333333` key, which is not "grey" but a specific
         * amount of the key's own foreground laid over it - and stating it as the alpha that
         * produces that number is what makes it come out right on the Light theme too, where
         * `#808080` would be far too pale to read.
         */
        const val HINT_ALPHA = 0.38f

        /** What a hold is, before the user has said otherwise. See [holdMillis]. */
        const val DEFAULT_HOLD_MS = 350L


        /**
         * Backspace: the pause before it starts running, then the two rates.
         *
         * The fast rate is a ceiling as much as a speed. Every repeat is work in the
         * application being typed into as well as here, and forty milliseconds turned out to
         * be faster than some of them can keep up with.
         */
        const val FIRST_REPEAT_MS = 400L

        /**
         * Characters placed by the baseline rather than centred on their own ink.
         *
         * The ones whose position below the line is the character. See [labelBaseline].
         */
        val ON_THE_LINE = setOf("_")

        /**
         * How far sideways before a press on the space bar becomes a caret slide.
         *
         * It was ten, which is barely past the system's own touch slop of about eight - the
         * distance below which Android does not consider a finger to have moved at all. That
         * is the right threshold for "a drag rather than a tap" and much too low for "a drag
         * rather than a *space*": the space bar is pressed constantly, by a thumb, at speed,
         * and a keyboard that stops reliably typing spaces has broken the one key it cannot
         * afford to.
         *
         * More than twice that now, so the gesture has to be meant. It stays cheap to ask
         * for, because the caret only starts moving after [SLIDE_STEP_DP] more.
         */
        const val SLIDE_SLOP_DP = 22f

        /** And how far per character after that. About a fingertip's width of travel. */
        const val SLIDE_STEP_DP = 12f
        const val SLOW_REPEAT_MS = 95L
        const val FAST_REPEAT_MS = 55L
        const val REPEAT_RAMP = 8
    }
}

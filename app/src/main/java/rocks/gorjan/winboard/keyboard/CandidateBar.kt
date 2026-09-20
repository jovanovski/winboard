package rocks.gorjan.winboard.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.winboard.R
import rocks.gorjan.winboard.SvgIcon
import rocks.gorjan.winboard.KeyboardPalette

/**
 * The strip above the keys: two glyphs at the near end, suggestions filling the rest.
 *
 * This is the bar in the reference screenshot, and working out what it actually was settled a
 * design question. It looks like chrome - a keyboard-switch and a microphone on the left, a
 * move handle and a close cross on the right, and nothing in between - but that empty middle
 * is where Windows 10 Mobile put its word candidates. The strip *is* the suggestion bar. So
 * suggestions fill it and the glyphs stay at the edge, and the keyboard keeps the height the
 * phone had rather than growing a fifth row to hold predictions.
 *
 * The first suggestion is always the literal text the user typed. That is not a detail either:
 * it is what makes an unwanted autocorrection one tap to reject, so the correction logic can
 * afford to be confident without ever being a trap.
 *
 * **The words are as wide as the words are, and the row scrolls.** It used to be three fixed
 * columns, on the reasoning that a suggestion in a place a thumb can learn is worth more than
 * a fourth-best guess. That was worth trying and it was the wrong trade twice over: three
 * columns of equal width give `a` the same target as `absolutely` and waste most of the bar
 * on the short ones, and three is simply fewer than the dictionary has to say - the fourth
 * word is often the right one and there was nowhere to put it. Sizing each word to its own
 * text fits five or six of the ordinary ones on screen at once, and the rest are a flick away
 * up to [MAX_WORDS]. The phone's own bar scrolled for exactly this reason.
 */
@SuppressLint("ViewConstructor")
class CandidateBar(
    context: Context,
    private var palette: KeyboardPalette
) : View(context) {

    /** A suggestion was chosen. The index is into whatever was last given to [setWords]. */
    var onWordPicked: ((Int) -> Unit)? = null

    /**
     * A suggestion was held and dragged onto the bin: forget it.
     *
     * The way out of a wrong thing the keyboard has learned, and it needs one. Learning from
     * what somebody types is what makes the keyboard theirs, and it means a single mistyped
     * word becomes a suggestion that comes back for months - this phone had `thank fod` in it
     * within a day. A dictionary that only ever grows is a dictionary that only ever gets
     * worse at the one thing it was added for.
     */
    var onWordForgotten: ((Int) -> Unit)? = null

    /** The microphone. */
    var onVoice: (() -> Unit)? = null

    /**
     * The clipboard mark was tapped: show what is on there.
     *
     * Asked for rather than taken, always. The bar's own offer to paste the newest clip
     * straight in went, because a mark that pasted something it had not shown you is a guess
     * acted on - and once the list is one tap away there is nothing the shortcut saved that
     * was worth the surprise.
     */
    var onClipboard: (() -> Unit)? = null


    /** Set by the grid on every measure pass, so the bar is sized in the same units. */
    private var keyW = 0f
    private var gap = 0f

    private var words: List<String> = emptyList()

    /**
     * Which word is emphasised - the one a space would accept.
     *
     * Not necessarily the first. When the keyboard intends to autocorrect, the word it means
     * to use is the one shown in the accent, so that what is about to happen is visible
     * *before* it happens rather than being discovered afterwards.
     */
    private var emphasis = -1

    /**
     * How many of the words are emoji, sitting at indices 1 upwards.
     *
     * The bar has to be told rather than work it out. An emoji entry is drawn with no
     * typeface at all - see [styleFor] - and that cannot be guessed from the string.
     * Sniffing the code points for "is this an emoji" is a question with no good answer and
     * one the caller already knows.
     */
    private var emojiCount = 0

    /**
     * Which suggestion is drawn as pressed, or -1.
     *
     * Not simply "what is under the finger". Nothing is shown as pressed the moment a finger
     * lands: see [pressCheck].
     */
    private var pressed = -1

    /**
     * What the finger actually landed on, whether or not it is being shown as pressed.
     *
     * Kept apart from [pressed] because the two answer different questions - one is what the
     * bar is drawing, the other is what this gesture is about - and they are deliberately out
     * of step for the first fraction of a second of every touch.
     */
    private var downSlot = -1

    /** Whether this gesture has already been answered with a tick, so it is answered once. */
    private var answered = false

    /**
     * Which suggestion is being dragged to the bin, or -1.
     *
     * A mode, and the only one this view has. While it is on, the bar stops being a row of
     * words and two glyphs and becomes one word under the finger and somewhere to drop it -
     * because a strip this short cannot show the word travelling *and* keep everything else
     * in place without the two overlapping in the middle.
     */
    private var dragging = -1

    /** Where the finger is, so the word being dragged can ride under it. */
    private var dragX = 0f

    /** Whether the finger is over the bin, so the drop is visible before it happens. */
    private var overBin = false

    /** Where the finger went down, to tell a hold apart from a flick of the thumb. */
    private var downX = 0f
    private var downY = 0f

    // ---------------------------------------------------------------- scrolling

    /**
     * How far the row of words has been dragged, in pixels, from its start.
     *
     * The bar's own rather than the view's `scrollX`, because only the *middle* of the bar
     * scrolls: the two glyphs are chrome and stay where they are, and scrolling the view
     * would carry them off the end with the words.
     */
    private var scroll = 0f

    /** How far it may be dragged: the words' total width, less what is on screen at once. */
    private var maxScroll = 0f

    /** Whether this gesture has become a scroll, which nothing else can then claim. */
    private var scrolling = false

    /** Where [scroll] stood when the finger went down, so the drag is absolute and cannot drift. */
    private var scrollFrom = 0f

    private var velocity: VelocityTracker? = null
    private val fling = OverScroller(context)

    /** Rebuilt on the next paint or touch, whichever comes first. See [layoutSlots]. */
    private var slotsDirty = true

    /**
     * The hold that starts a drag.
     *
     * The platform's own long-press timeout rather than the keyboard's `hold to show symbols`
     * setting, which somebody may well have taken down to 150 ms. That is the right number
     * for a key - the alternates it opens are what the hold is *for*, and they are wanted
     * fast - and the wrong one here, where a hold is a rare, deliberate thing and the common
     * gesture on the same pixels is an ordinary tap that must never turn into one.
     */
    private val startDrag = Runnable { beginDrag() }

    /**
     * The wait before a finger on a suggestion is treated as a press at all.
     *
     * A bar that lights up and ticks the instant it is touched is a bar that lights up and
     * ticks every time somebody flicks it sideways - which is now the ordinary way to see the
     * rest of the suggestions, so it was happening constantly, on a word the user had no
     * intention of taking. The touch that begins a scroll and the touch that begins a tap are
     * the same touch; nothing can tell them apart at the moment it lands, and the only honest
     * thing to do is wait to find out.
     *
     * [ViewConfiguration.getTapTimeout] is the platform's own name for exactly this wait, and
     * it is what every scrolling list on the phone uses before showing a row as pressed. A tap
     * that lifts before it elapses is not left silent - see the release, which answers it
     * then - so what this actually delays is only the *highlight* of a slow tap, and what it
     * removes is the tick of a fast swipe.
     */
    private val pressCheck = Runnable { showPressed() }

    private fun showPressed() {
        if (downSlot == -1 || scrolling || dragging >= 0) return
        pressed = downSlot
        answer()
        invalidate()
    }

    /** The tick and the click a press gets, once per gesture whenever it is confirmed. */
    private fun answer() {
        if (answered) return
        answered = true
        KeyboardHaptics.key(this)
        KeyboardSounds.tap()
    }

    /** Nothing on this gesture is a press any more: no highlight, no hold, no tick to come. */
    private fun cancelPress() {
        removeCallbacks(pressCheck)
        removeCallbacks(startDrag)
        downSlot = -1
        if (pressed != -1) {
            pressed = -1
            invalidate()
        }
    }

    /**
     * Whether dictation is running.
     *
     * Drawn as the accent, because that is what the accent means everywhere else on this
     * keyboard: the thing under your thumb, or the thing that is about to happen. A
     * microphone that looks identical whether or not it is listening is a microphone nobody
     * trusts - the whole question somebody has while dictating is "is this on".
     */
    var listening = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Whether dictation is starting up.
     *
     * The engine takes a moment to open the microphone and, the first time, to load a
     * model. Turning the button straight to the accent claims it is listening before it
     * is, so the first word said into it is lost and the button is what said it would be
     * caught. A ring turning over the mark says "wait" instead, and the accent follows
     * when the engine is actually up.
     */
    var preparing = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startSpinner() else stopSpinner()
            invalidate()
        }

    private var spinnerSweepStart = 0f
    private var spinner: ValueAnimator? = null

    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val spinnerBounds = RectF()

    private fun startSpinner() {
        spinner?.cancel()
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = SPINNER_TURN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                spinnerSweepStart = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpinner() {
        spinner?.cancel()
        spinner = null
    }

    override fun onDetachedFromWindow() {
        // A repeating animator outlives the view it was invalidating otherwise.
        stopSpinner()
        releaseVelocity()
        super.onDetachedFromWindow()
    }

    private val face = Paint()
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val font = ResourcesCompat.getFont(context, R.font.segoeui_regular)

    /** The other face the bar can be set in. See `KeyView.classicFont`. */
    private val classicFont = ResourcesCompat.getFont(context, R.font.tahoma)

    /**
     * What the keyboard is made of. See [KeyboardTheme].
     *
     * The bar takes almost the whole of a theme through the palette - its ground, the navy
     * behind the word under a finger, the black the words are set in - and needs to be told
     * only one thing, which is what to set them in. A row of suggestions in Segoe above a
     * keyboard in Tahoma is the join showing, and it is the join people look straight at:
     * the bar is a finger's width above the letters being typed.
     *
     * Slots are laid out from measured text, so a change of face is a change of width and
     * the row has to be measured again.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            slotsDirty = true
            invalidate()
        }

    /** The face the words are set in, which is the theme's. */
    private fun wordFace() = if (theme.tahoma) classicFont else font

    /** How far a finger may wander and still be holding still. */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    /** Where each word sits along the row, measured from its start. Rebuilt by [layoutSlots]. */
    private var slots: List<CandidateSlot> = emptyList()

    // Held from construction: SvgIcon re-parses its file on every call and has no cache.
    private val voiceGlyph: Drawable? = SvgIcon.fromAsset(context, "$ICONS/appbar.microphone.svg")
    private val pasteGlyph: Drawable? =
        SvgIcon.fromAsset(context, "$ICONS/appbar.clipboard.paste.svg")?.mutate()
    private val binGlyph: Drawable? =
        SvgIcon.fromAsset(context, "$ICONS/appbar.delete.svg")?.mutate()

    init {
        isClickable = true
    }

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        voiceGlyph?.setTint(p.foreground)
        invalidate()
    }

    /**
     * Set by the host before it measures. No `requestLayout` from here: this is called during
     * the host's own measure pass, and asking for another one from inside it is how a view
     * ends up measuring on every frame forever.
     */
    fun setMetrics(keyW: Float, gap: Float) {
        if (keyW != this.keyW) slotsDirty = true
        this.keyW = keyW
        this.gap = gap
    }

    /**
     * The words to offer, and which of them a space would take.
     *
     * @param emphasised index of the word the keyboard would choose by itself, or -1 when it
     *   would leave the typed text alone.
     * @param emoji how many entries, starting at index 1, are emoji rather than words. They
     *   are drawn differently and the bar cannot tell by looking - see [styleFor].
     */
    fun setWords(words: List<String>, emphasised: Int, emoji: Int = 0) {
        // Copied rather than a `subList` view of the caller's list: the service keeps its own
        // reference to that list and a view of it would go stale under the bar.
        this.words = words.take(MAX_WORDS)
        this.emphasis = emphasised
        this.emojiCount = emoji
        // Back to the start, every time. The words have changed, so wherever the row was
        // scrolled to was a position in a different list - and the one thing the user is
        // entitled to assume is that the leftmost word is what they just typed.
        stopFling()
        scroll = 0f
        slotsDirty = true
        invalidate()
    }

    fun clear() {
        message = null
        setWords(emptyList(), -1)
    }

    /**
     * A line of text across the bar, in place of suggestions.
     *
     * For the handful of things the keyboard has to say rather than offer - dictation being
     * unavailable, or having stopped for a reason worth knowing. It goes here because the bar
     * is the only surface the keyboard owns that the user is already looking at, and a toast
     * over somebody's application to say the microphone did not start is worse than the
     * silence it replaces.
     */
    fun setMessage(text: String?) {
        if (message == text) return
        message = text
        invalidate()
    }

    private var message: String? = null

    /** The bar is a fixed share of a key's height - tall enough to touch, short enough to spare. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (keyW > 0f) (keyW * HEIGHT).toInt() else 0
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // How far the row may scroll depends on how much of it is on screen.
        slotsDirty = true
    }

    // ---------------------------------------------------------------- geometry

    /** Where the words begin: past both glyphs, which do not scroll with them. */
    private fun contentLeft(): Float = keyW * GLYPH_SLOT * GLYPHS

    /** How much of the row is on screen at once. */
    private fun viewport(): Float = (width - contentLeft()).coerceAtLeast(0f)

    /** Measures every word and lays the row out. The rules are in [candidateSlots]. */
    private fun layoutSlots() {
        slotsDirty = false
        if (keyW <= 0f) {
            slots = emptyList()
            maxScroll = 0f
            return
        }
        // Measuring is the only part of this that needs a font, so it is the only part that
        // stays here. The arithmetic is [candidateSlots], where it can be asked questions.
        slots = candidateSlots(
            widths = words.mapIndexed { i, word ->
                styleFor(i)
                ink.measureText(word)
            },
            padding = keyW * WORD_PADDING,
            minimum = keyW * MIN_SLOT
        )
        maxScroll = candidateScrollRange(slots, viewport())
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    private fun slotsReady() {
        if (slotsDirty) layoutSlots()
    }

    /**
     * Points [ink] at whatever the entry in [slot] is drawn in. Any other index is a word.
     *
     * Emoji get **no typeface at all**, which is not an oversight: they are drawn by the
     * system's own colour-emoji font, found through Android's ordinary glyph-fallback search
     * whatever typeface is set, and naming Segoe here would only risk a font that quietly has
     * no colour table for these code points. The emoji panel does the same, for the same
     * reason.
     *
     * At the same size as the words beside them. They were set half again as large for a
     * while, on the reasoning that a picture read at the size of the letters around it is a
     * smudge; on the bar it read instead as one entry shouting over the rest of the row, and
     * a suggestion is a suggestion whether it is a word or a picture. The line they all sit
     * on is worked out once from the words' font, so the emoji font's own metrics do not
     * become a difference in height. See [baseline].
     */
    private fun styleFor(slot: Int) {
        ink.typeface = if (slot in 1..emojiCount) null else wordFace()
        ink.textSize = keyW * TEXT
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        slotsReady()
        trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A flick that is still coasting is stopped by the finger landing on it, and
                // that touch belongs to the stop rather than to whatever it landed on. Same
                // rule every scrolling list on the phone follows.
                val coasting = !fling.isFinished
                stopFling()
                downSlot = if (coasting) -1 else slotAt(event.x)
                pressed = -1
                answered = false
                downX = event.x
                downY = event.y
                scrollFrom = scroll
                scrolling = false
                // Nothing is lit and nothing is felt yet. Whether this is a press or the
                // start of a scroll is not knowable here. See [pressCheck].
                if (downSlot != -1) postDelayed(pressCheck, TAP_TIMEOUT)
                // Only a word can be dragged to the bin. The two glyphs are ways in to
                // something, not things the keyboard has an opinion about.
                if (downSlot >= 0) postDelayed(startDrag, LONG_PRESS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging >= 0) {
                    dragX = event.x
                    val onIt = event.x > width - keyW * GLYPH_SLOT
                    if (onIt != overBin) {
                        overBin = onIt
                        // Felt at the edge of the bin rather than only on release, so the
                        // drop is known to be armed without having to look.
                        KeyboardHaptics.key(this)
                    }
                    invalidate()
                    return true
                }

                val travel = event.x - downX
                if (!scrolling && maxScroll > 0f && abs(travel) > touchSlop &&
                    abs(travel) > abs(event.y - downY)
                ) {
                    // A sideways drag along the words is a scroll, and from here nothing else
                    // gets a look at this gesture: not the hold that opens the bin, not the
                    // tap that would take a word, and not whatever is above the keyboard.
                    scrolling = true
                    cancelPress()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrolling) {
                    // Counted from where the finger started rather than from the last event,
                    // so the row and the finger cannot drift apart over a long drag.
                    scroll = (scrollFrom - travel).coerceIn(0f, maxScroll)
                    invalidate()
                    return true
                }

                // A thumb that has travelled is a thumb going somewhere else: off the bar, or
                // onto a row that has nothing left to scroll and so never claimed the gesture
                // above. Either way it has stopped being a tap on the word it started on, and
                // it does not become a tap on the word it has wandered to - the bar is one
                // row of small targets and a finger sliding along it is not choosing.
                if (abs(travel) > touchSlop || abs(event.y - downY) > touchSlop) cancelPress()
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(startDrag)
                removeCallbacks(pressCheck)
                if (dragging >= 0) {
                    val index = dragging
                    val dropped = overBin
                    endDrag()
                    // Released short of the bin, the word is *not* taken either. Somebody who
                    // held a suggestion and thought better of it meant to cancel, and
                    // inserting the word they were trying to delete is the worst available
                    // reading of that.
                    if (dropped) onWordForgotten?.invoke(index)
                    releaseVelocity()
                    return true
                }
                if (scrolling) {
                    startFling()
                    scrolling = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    releaseVelocity()
                    return true
                }
                // Only what the finger was actually holding, and only if it is still there. A
                // tap that landed on a coasting row was a tap that stopped it - [downSlot] was
                // never set - and taking the word underneath as well would put a suggestion
                // into somebody's sentence for the crime of stopping a scroll.
                val chosen = slotAt(event.x)
                val take = chosen != -1 && chosen == downSlot
                // The tick a quick tap never waited around for. A finger down and up inside
                // the tap timeout is the commonest way of all to take a suggestion, and it
                // must not be the one that goes unanswered.
                if (take) answer()
                downSlot = -1
                pressed = -1
                invalidate()
                if (take) {
                    when {
                        chosen == VOICE -> onVoice?.invoke()
                        chosen == PASTE -> onClipboard?.invoke()
                        chosen >= 0 -> onWordPicked?.invoke(chosen)
                    }
                }
                releaseVelocity()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPress()
                endDrag()
                scrolling = false
                parent?.requestDisallowInterceptTouchEvent(false)
                releaseVelocity()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun trackVelocity(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocity?.clear()
            velocity = velocity ?: VelocityTracker.obtain()
        }
        velocity?.addMovement(event)
    }

    private fun releaseVelocity() {
        velocity?.recycle()
        velocity = null
    }

    /**
     * The row carries on after the finger leaves it.
     *
     * A row of ten words is a couple of screens wide at most, so this is rarely more than one
     * flick - but a list that stops dead the instant it is let go is the one thing that makes
     * a scrolling surface feel like it is not really scrolling.
     */
    private fun startFling() {
        val tracker = velocity ?: return
        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
        val vx = tracker.xVelocity
        if (abs(vx) < minFling || maxScroll <= 0f) return
        fling.fling(scroll.toInt(), 0, -vx.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
        postInvalidateOnAnimation()
    }

    private fun stopFling() {
        if (!fling.isFinished) fling.abortAnimation()
    }

    /**
     * The hold has fired: the word comes off the bar and the bin appears.
     *
     * The parent is asked to keep its hands off the rest of the gesture. The bar lives inside
     * the keyboard's own view group, and a drag that wandered a few pixels vertically without
     * this would be taken for a scroll by whatever is above it and the finger would be lost
     * halfway to the bin.
     */
    private fun beginDrag() {
        if (downSlot < 0) return
        dragging = downSlot
        downSlot = -1
        pressed = -1
        dragX = downX
        overBin = false
        parent?.requestDisallowInterceptTouchEvent(true)
        KeyboardHaptics.tap(this)
        invalidate()
    }

    private fun endDrag() {
        if (dragging < 0) return
        dragging = -1
        overBin = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    /** What the word being dragged says, or null when nothing is being dragged. */
    private fun draggedWord(): String? = words.getOrNull(dragging)

    /** Which word, or which glyph, is at [x]. The glyph slots use negative indices. */
    private fun slotAt(x: Float): Int {
        val side = keyW * GLYPH_SLOT
        // Both glyphs at the near end, in the order a thumb reaches them: the microphone
        // first, because it is the one that is looked for, and the clipboard beside it.
        if (x < side) return VOICE
        if (x < side * GLYPHS) return PASTE
        return candidateAt(x, contentLeft(), scroll, slots)
    }

    // ---------------------------------------------------------------- paint

    override fun onDraw(canvas: Canvas) {
        if (keyW <= 0f) return
        slotsReady()

        // A flick still coasting. Stepped here rather than from `computeScroll`, which is the
        // parent's call to make and only ever arrives for a view being drawn anyway.
        if (fling.computeScrollOffset()) {
            scroll = fling.currX.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }

        // The bar sits on the same ground as the keyboard, not on the keys' own grey. It is
        // the gap the phone left above the keys, with things in it - and under a theme that
        // draws its own keys that ground is the shell's dialog colour, so this follows it
        // there too. See `KeyboardView.groundColour`, which is the same decision and has the
        // reasoning.
        face.color =
            if (theme.drawsKeys) palette.background
            else ColorUtils.blendARGB(palette.background, palette.foreground, GROUND_ALPHA)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)

        val side = keyW * GLYPH_SLOT

        // A drag takes the bar over. Everything else steps aside for it - the two glyphs
        // included - because the word has to be seen travelling and there is one row to do it
        // in. What is left is the word under the finger and the bin it is going to, and the
        // bin is at the far end, which is the end nothing else uses.
        draggedWord()?.let { word ->
            drawGlyph(canvas, binGlyph, width - side / 2f, overBin)
            ink.typeface = wordFace()
            ink.textSize = keyW * TEXT
            ink.textAlign = Paint.Align.CENTER
            ink.color = if (overBin) palette.accent else palette.foreground
            // Held clear of the bin so the word never sits on top of what it is aimed at.
            canvas.drawText(word, dragX.coerceIn(side, width - side), baseline(), ink)
            return
        }

        val voiceCentre = side / 2f
        drawGlyph(canvas, voiceGlyph, voiceCentre, pressed == VOICE || listening)
        if (preparing) drawSpinner(canvas, voiceCentre, side)
        // The clipboard beside it. Always drawn, whatever is on the clipboard and whatever the
        // rest of the bar is up to: it is a way in to something, like the microphone, and a
        // control that comes and goes is one nobody learns the place of. An empty clipboard is
        // something for the list to say, not a reason to hide the way of asking - see the
        // message the host puts up when there is nothing to show.
        drawGlyph(canvas, pasteGlyph, side + side / 2f, pressed == PASTE)

        styleFor(-1)
        ink.textAlign = Paint.Align.CENTER

        // One line for everything on the bar, taken from the words' own font before anything
        // is drawn in any other. The emoji font has metrics of its own and would otherwise
        // put its entries on a line slightly below the words - see [baseline], which is about
        // exactly this and which answers differently the moment the paint is restyled.
        val line = baseline()
        val start = contentLeft()

        message?.let { text ->
            ink.color = palette.foregroundSubtle
            canvas.drawText(text, start + viewport() / 2f, line, ink)
            return
        }

        if (slots.isEmpty()) return

        // Clipped to what is past the glyphs, which is what makes this scroll at all: a word
        // half off the near edge is cut in half rather than painted over the clipboard, and
        // that half word is also the only thing on the bar saying there is more to the left.
        canvas.save()
        canvas.clipRect(start, 0f, width.toFloat(), height.toFloat())

        for (slot in slots) {
            val left = start - scroll + slot.left
            val right = start - scroll + slot.right
            if (right < start || left > width) continue

            if (slot.index == pressed) {
                face.color = palette.accent
                canvas.drawRect(left, 0f, right, height.toFloat(), face)
            }

            styleFor(slot.index)
            ink.color = when {
                slot.index == pressed -> palette.onAccent()
                // The correction a space would apply, and only ever that. See the note in
                // the service where the emphasis is decided.
                slot.index == emphasis -> palette.accent
                else -> palette.foreground
            }
            // The word entire, never shortened to fit: a suggestion drawn short of what it
            // would insert is a suggestion that lies about itself.
            canvas.drawText(words[slot.index], (left + right) / 2f, line, ink)

            // A hairline between suggestions. The phone separated them rather than boxing
            // each one, which on a strip this short is the difference between a row of words
            // and a row of buttons.
            if (slot.index > 0) {
                face.color = ColorUtils.blendARGB(palette.background, palette.foreground, DIVIDER_ALPHA)
                val top = height * DIVIDER_INSET
                canvas.drawRect(left, top, left + keyW * DIVIDER_WIDTH, height - top, face)
            }
        }
        canvas.restore()
    }

    /**
     * The one line every word on the bar sits on.
     *
     * From the font's own metrics rather than from the ink of the word being drawn, and that
     * is the whole point. Measuring each word centres each word - which is right for a single
     * character alone on a key, and wrong for a row of words, because the ink of `your` runs
     * below the baseline where the ink of `house` does not. Centring both on their own boxes
     * puts them at two different heights, so the row appears to bounce as the suggestions
     * change under it.
     *
     * A font's ascent and descent are the same for every word set in it, so this is one line,
     * and the words sit on it the way words on a line do.
     */
    private fun baseline(): Float {
        val metrics = ink.fontMetrics
        return height / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    /**
     * A ring turning over the microphone while dictation starts up.
     *
     * Drawn around the mark rather than replacing it: the button is still the microphone,
     * and swapping the glyph out for a spinner would read as a different control.
     */
    private fun drawSpinner(canvas: Canvas, centre: Float, side: Float) {
        val radius = side * SPINNER_RADIUS_SHARE
        val middle = height / 2f
        spinnerPaint.color = palette.accent
        spinnerPaint.strokeWidth = side * SPINNER_STROKE_SHARE
        spinnerBounds.set(centre - radius, middle - radius, centre + radius, middle + radius)
        canvas.drawArc(spinnerBounds, spinnerSweepStart, SPINNER_SWEEP, false, spinnerPaint)
    }

    private fun drawGlyph(canvas: Canvas, glyph: Drawable?, centreX: Float, lit: Boolean) {
        val drawable = glyph ?: return
        if (lit) {
            face.color = palette.accent
            val half = keyW * GLYPH_SLOT / 2f
            canvas.drawRect(centreX - half, 0f, centreX + half, height.toFloat(), face)
        }
        drawable.setTint(if (lit) palette.onAccent() else palette.foreground)
        val size = (keyW * GLYPH - GLYPH_TRIM_DP * resources.displayMetrics.density).toInt()
        val left = (centreX - size / 2f).toInt()
        val top = (height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    // Not private: the keyboard has to know how tall the bar is to work out whether the two
    // of them fit on the screen together. See `KeyboardView.TOTAL_UNITS`.
    internal companion object {
        /** One turn of the ring shown while dictation starts up. */
        private const val SPINNER_TURN_MS = 900L

        /** How much of the ring is drawn, so it reads as turning rather than as a circle. */
        private const val SPINNER_SWEEP = 100f

        // Sized off the button rather than the screen, so it stays a ring around the mark
        // on a short keyboard as well as a tall one.
        private const val SPINNER_RADIUS_SHARE = 0.34f
        private const val SPINNER_STROKE_SHARE = 0.055f

        /**
         * The microphone's slot, as a negative index so that words can use 0 upwards.
         *
         * There was a close cross at the far end, because the reference screenshot has one -
         * but the keyboard's own chevron in the navigation bar already does exactly that, two
         * centimetres below it, on every screen. Two ways to dismiss the keyboard is not twice
         * as useful; it is one of them taking room from the suggestions, which are what the
         * bar is for.
         */
        const val VOICE = -2

        /** The clipboard's slot, beside the microphone and on the same principle as [VOICE]. */
        const val PASTE = -3

        /** How many glyphs sit at the near end, ahead of the words. */
        const val GLYPHS = 2f

        /**
         * All as fractions of one key's width, like everything else in the keyboard.
         *
         * [HEIGHT] is measured off the phone and not guessed: the reference keyboard's header
         * strip is 77 pixels against a 61 pixel key, which is 1.26. Guessing it at half that
         * made a bar of about 25dp - barely half Android's minimum touch target, and the
         * first thing anyone said about it was that they could not hit anything on it. At
         * 1.26 it comes out around 51dp, which clears the minimum with a little to spare.
         */
        const val HEIGHT = 1.26f
        /**
         * The suggestions, a point larger than the hint in a key's corner.
         *
         * As a fraction of a key's width like everything else, so it scales with the
         * keyboard - which on this phone puts it at about 15sp. It is not the corner mark's
         * size because it is not that kind of text: a hint is a label on something you can
         * already see, and a suggestion is a word you are being asked to read and decide
         * about at typing speed.
         *
         * It was 0.409 - a point larger again - until the words were sized to their own ink.
         * Three fixed columns could afford the extra point because nothing was competing for
         * the room; a row of eight or ten words is competing for all of it, and a point off
         * the text is most of another suggestion on the bar. Taken as fifteen sixteenths of
         * what it was, because a fraction of a key's width cannot name an exact number of
         * points on every screen - only the ratio carries across.
         */
        const val TEXT = 0.383f

        /**
         * The microphone, at twice the size it started.
         *
         * It is the only thing on the bar that is not a word, so it is the only thing that
         * can be found without reading - which is worth having it drawn large enough to be
         * seen out of the corner of an eye rather than sized like a piece of punctuation.
         */
        const val GLYPH = 1.0f

        /**
         * Taken off both glyphs, in real millimetres rather than as a fraction.
         *
         * A fraction would have been the house style, and it is the wrong tool here: this is
         * not a proportion anybody chose, it is four density-independent pixels trimmed off a
         * mark that was slightly too heavy for the row of words beside it. The slot they sit
         * in ([GLYPH_SLOT]) is untouched, so what is drawn gets smaller while what can be
         * tapped stays exactly where it was.
         */
        const val GLYPH_TRIM_DP = 4f
        const val GLYPH_SLOT = 1.25f

        /** The air either side of a word inside its own slot. */
        const val WORD_PADDING = 0.30f

        /**
         * The narrowest a suggestion may be, however short the word in it.
         *
         * The same width as a glyph's slot, which on this keyboard is a little over 48dp -
         * Android's minimum target. Without it `a` would be a four-millimetre sliver, and the
         * commonest suggestions in the language are the shortest ones.
         */
        const val MIN_SLOT = 1.25f

        /**
         * How many suggestions the bar will hold.
         *
         * Ten. The old bar showed three because three fixed columns is all that fits with a
         * place a thumb can learn for each - but a scrolling row has no such budget, and the
         * dictionary routinely has eight or nine things worth saying about a half-typed word.
         * Ten rather than everything the search returns because past that they are guesses
         * about guesses, and a row that takes four flicks to reach the end of is not a row
         * anybody reads.
         */
        const val MAX_WORDS = 10

        const val GROUND_ALPHA = 0.102f
        const val DIVIDER_ALPHA = 0.22f
        const val DIVIDER_WIDTH = 0.014f
        const val DIVIDER_INSET = 0.22f

        const val ICONS = "icons"

        /** How long a suggestion has to be held before it comes off the bar. */
        val LONG_PRESS = ViewConfiguration.getLongPressTimeout().toLong()

        /** And how long before a finger on one counts as a press at all. See [pressCheck]. */
        val TAP_TIMEOUT = ViewConfiguration.getTapTimeout().toLong()
    }
}

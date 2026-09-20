package rocks.gorjan.winboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rocks.gorjan.winboard.R
import rocks.gorjan.winboard.SvgIcon
import rocks.gorjan.winboard.KeyboardPalette

/**
 * The field of keys.
 *
 * Everything is sized from the width of a single key, which is the layout's share of
 * whatever width the keyboard has been given. The phone's own proportions - a gutter a
 * tenth of a key, a key half again as tall as it is wide - then hold at any screen size,
 * which is what lets a layout drawn for a 480-wide phone land correctly on hardware that is
 * a good deal wider and a great deal taller. It is the calculator's grid with two things
 * generalised: the column count is the layout's rather than a constant, because Macedonian
 * needs twelve across where English needs ten, and a key's width is a fraction rather than
 * a whole number, because the phone's enter key is 1.4 keys wide and nothing else will do.
 *
 * The height is *not* a free parameter. An input method is asked how tall it wants to be and
 * is given it, so the grid asks for exactly the height its rows need at the width it has.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private var palette: KeyboardPalette
) : ViewGroup(context) {

    var listener: KeyView.Listener? = null
        set(value) {
            field = value
            grids.values.forEach { grid -> grid.forEach { it.listener = value } }
        }

    /**
     * The layout the service asked for, which is what [currentLayout] reports.
     *
     * Not always what is drawn - see [layout]. The service compares ids against the symbol
     * pages and puts back whatever it was told, and none of that should have to know that the
     * letters grew a row of digits on the way to the screen.
     */
    private var requested: KeyboardLayout = Layouts.EN_QWERTY

    /** The layout on screen: [requested], with the number row if the setting wants one. */
    private var layout: KeyboardLayout = Layouts.EN_QWERTY
    /**
     * The keys of the layout on screen. Not the only ones attached - see [grids].
     */
    private var keys: List<KeyView> = emptyList()

    /**
     * One built grid per layout, kept alive and simply hidden.
     *
     * Switching to `&123` used to rebuild the grid, and that was the delay: thirty-four views
     * destroyed and thirty-four made, and then a measure and layout pass before any of them
     * had a size to be tapped. Keeping both sets means the symbols are already built, already
     * measured and already in position - the switch is two loops setting `visibility` and
     * nothing else, and the first symbol can be tapped in the same instant the page appears.
     *
     * The cost is a few dozen small views for layouts the user has actually visited, which
     * against a keyboard that is already holding two dictionaries is not worth counting.
     * Filled lazily, so a language never selected never builds one.
     */
    private val grids = mutableMapOf<String, List<KeyView>>()

    /**
     * The width each grid was last laid out at.
     *
     * A hidden grid keeps the bounds it had when it was last visible, which is exactly what
     * makes the switch free - but only while those bounds are still right. Rotate the phone,
     * or open the keyboard in a window of a different size, and they are not. This is how
     * [rebuild] knows whether it can skip the layout pass or has to ask for one.
     */
    private val laidOutAt = mutableMapOf<String, Int>()

    /** The geometry those bounds were measured against, so a change to it can void them. */
    private var lastKeyW = 0f
    private var lastKeyH = 0f

    /** Measured once per pass and read by everything that sizes itself. */
    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f

    /**
     * How much of the bottom of this view the system is going to draw on top of.
     *
     * An input method is given the bottom of the screen, and the system then draws its own
     * strip over the bottom of that - the back chevron and the keyboard-switcher globe.
     * Nothing warns about the overlap: the keyboard simply gets a window reaching the very
     * bottom and finds its last row underneath, which on this layout is the space bar and
     * the enter key, the two that can least afford it.
     *
     * The inset the window reports is **not** enough on its own. With gesture navigation it
     * describes the gesture handle, which is the shorter thing; the strip the system puts up
     * while an input method is showing is a full navigation bar's height, and measuring it
     * on a Pixel gave 80 pixels reported against about 146 actually painted on. So the
     * larger of the two is reserved, and only when there is a bar there at all.
     *
     * The keys are laid out above whatever is reserved and the ground colour runs underneath,
     * which is also what the phone did: the strip below the keys was keyboard, not app.
     */
    private var bottomInset = 0

    /**
     * The screen [bottomInset] was reported for, in dp, or zeroes for "nothing yet".
     *
     * Remembered alongside the inset because the inset is only an answer for the screen it
     * was measured on, and this view outlives the screen. Turn the phone to landscape and
     * back and the same view is shown again in portrait, still holding whatever the system
     * asked for while the phone was on its side - which is less, and which is the keyboard
     * sitting a little lower than it should with its bottom row under the chevron and the
     * globe. Tying the two together is what makes that answer expire by itself: it is no
     * longer for this screen, so it is no longer used, and the bar's own height stands in
     * until the system says otherwise.
     */
    private var insetsW = 0
    private var insetsH = 0

    /**
     * Whether the framework has said how much room the navigation bar wants *here*.
     *
     * Until it has, the honest answer is not zero. Zero is a real answer - it means there is
     * no bar to keep clear of - and treating "not told yet" as "nothing to reserve" is what
     * puts the bottom row of keys under the navigation bar. So an untold view reserves the
     * bar's usual height, and gives it back the moment it is told otherwise. A band of dead
     * keyboard for one frame is a far smaller thing to be wrong about than a row of keys that
     * cannot be pressed.
     */
    private val insetsKnown: Boolean
        get() {
            val config = resources.configuration
            return insetsW > 0 && insetsW == config.screenWidthDp && insetsH == config.screenHeightDp
        }

    private var shifted = false

    /**
     * Whether a row's own [Row.heightScale] is honoured or ignored.
     *
     * The layouts say the bottom row is three-quarters the height of a letter row, which is
     * what the phone did and what gives the app above a little more room. Turning this off
     * makes every row the same height without the layouts having to know anything about it -
     * the declaration of intent stays where it belongs and this decides whether to act on it.
     */
    var shortBottomRow: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * Whether the letters have a row of digits across the top. See [Layouts.withNumberRow].
     *
     * Applied here rather than by the service choosing a different layout, for the same
     * reason as [shortBottomRow]: the service's idea of which language is up, which page is
     * showing and what to put back after a slide all stay exactly as they were.
     */
    var numberRow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // The symbol pages keep their grids and change height - see [stretch] - so the
            // bounds cached for them are no longer where their keys go.
            laidOutAt.clear()
            layout = drawn(requested)
            if (keys.isEmpty()) requestLayout() else rebuild()
        }

    private fun drawn(next: KeyboardLayout): KeyboardLayout =
        if (numberRow) Layouts.withNumberRow(next) else next

    /**
     * Whether [layout] is a page the letters' number row has to be made up on.
     *
     * The symbol pages, which have no number row of their own and share the letters' bottom
     * row. `&123` is pressed in the middle of a sentence, and a page a row shorter than the
     * letters would resize the window - moving whatever is being typed into - on the way to
     * every symbol and again on the way back. The number pads are left alone: they come up
     * when a field changes, which moves the app anyway, and they were never the letters'
     * height to begin with.
     */
    private fun fillsNumberRow(): Boolean =
        numberRow && layout.language.isEmpty() &&
            layout.rows.lastOrNull()?.keys?.any { it.action == Action.SPACE } == true

    /** The height of the letters' number row, gutter included, for a page that has none. */
    private fun missingRowHeight(): Float =
        keyH * (if (shortBottomRow) Layouts.NUMBER_ROW_SCALE else 1f) + gap

    /**
     * What each row above the space bar is given on such a page, so the page comes to the
     * letters' height. Shared out rather than put in one place, which would be one oddly tall
     * row of symbols.
     */
    private fun stretch(): Float =
        if (fillsNumberRow()) missingRowHeight() / (layout.rows.size - 1) else 0f

    /** How tall a row is drawn: its own share of a key, and its share of any [stretch]. */
    private fun heightOf(row: Row): Float =
        keyH * scaleOf(row) + if (row !== layout.rows.last()) stretch() else 0f

    /** How long a hold takes, pushed down to every key. See [KeyView.holdMillis]. */
    var holdMillis: Long = KeyView.DEFAULT_HOLD_MS
        set(value) {
            field = value
            grids.values.forEach { grid -> grid.forEach { it.holdMillis = value } }
        }

    /**
     * How tall the keys are against the height the phone's own were, as a multiplier.
     *
     * The one proportion in this keyboard somebody can move, and it earns the exception the
     * same way the vibration strength does: [KEY_ASPECT] is a measurement off a 480-wide
     * phone held by nobody in particular, and how much of a screen a keyboard should take is
     * a trade between the size of a thumb and the size of what is being typed into - both of
     * which vary by more than any single number can cover. A four-inch phone in one hand and
     * a tablet-sized one in two are not the same keyboard.
     *
     * A multiplier rather than a height, so it means the same thing on every screen: the keys
     * are still a fraction of the width, still in the phone's proportions, and this says how
     * much of one. Only the *keys* answer to it - the gutters between them and the suggestion
     * bar do not, because those are sized for a fingertip and a line of text respectively and
     * neither of those changes when somebody wants taller keys.
     */
    var keyHeightScale: Float = 1f
        set(value) {
            val clamped = value.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
            if (field == clamped) return
            field = clamped
            // Every cached grid is holding bounds measured against the old height.
            laidOutAt.clear()
            requestLayout()
        }

    /**
     * Whether the caret joystick is showing. See [JoystickView].
     *
     * Turning it on takes the caret slide off the space bar, which is not two settings folded
     * into one but the same one said twice: the space bar's slide exists because there was
     * nowhere else to put the gesture, and once there is somewhere, leaving it on the space
     * bar only keeps the occasional wrong space it costs.
     */
    var joystick: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            grids.values.forEach { grid -> grid.forEach { it.caretSlide = !value } }
            if (value) attachJoystick() else detachJoystick()
        }

    private var joystickView: JoystickView? = null

    private fun attachJoystick() {
        if (joystickView != null) return
        val view = JoystickView(context, palette)
        view.theme = theme
        // Zero when there is no listener, which is the same answer the field gives when the
        // caret has nowhere left to go: nothing moved.
        view.onDrag = { steps -> listener?.onCursorSlide(view, steps) ?: 0 }
        view.onDragLines = { lines -> listener?.onCursorLines(view, lines) ?: 0 }
        joystickView = view
        addView(view)
        requestLayout()
    }

    private fun detachJoystick() {
        joystickView?.let { removeView(it) }
        joystickView = null
        requestLayout()
    }

    /**
     * Whether a hold on shift or `&123` slides across the keys it brings up.
     *
     * Pushed down to every key, cached grids included, the way the hold time is: a grid that
     * is not on screen is one tap from being so, and one that had missed the change would
     * have keys whose hold did nothing until the next rebuild.
     */
    var slideKeys: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            grids.values.forEach { grid -> grid.forEach { it.slideSelect = value } }
            if (!value) endSlide()
        }

    /**
     * What the keys are made of. See [KeyboardTheme].
     *
     * Pushed down to every grid for the same reason the two settings above are, and the
     * colours that go with it arrive separately through [applyPalette] - the service changes
     * both when this setting moves. The keys are the only thing here that knows about it: a
     * theme is a palette plus the shape of a button, and everything except the buttons is
     * already painted from the palette.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            setBackgroundColor(groundColour())
            grids.values.forEach { grid -> grid.forEach { it.theme = value } }
            // The two things that float above the keys, which are made of what the keys are
            // made of. Nothing else here has a shape of its own to change.
            popup.theme = value
            languagesPopup.theme = value
            preview.theme = value
            joystickView?.theme = value
        }

    /**
     * Whether a pressed key lifts its letter clear of the finger. See [KeyPreviewPopup].
     *
     * Turning it off does not merely stop showing the flag, it puts away one that is up: this
     * can be switched from the settings page while the keyboard is on screen underneath it,
     * which is the whole reason that page has a text box to type in.
     */
    var keyPreview: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) preview.dismiss()
        }

    // ------------------------------------------------------------------ alternates

    /**
     * The row a hold opens.
     *
     * Lives in a window of its own rather than being painted in here, because it belongs
     * above the key being held and above the top row is outside this view altogether. See
     * [AlternatesPopup].
     */
    private val popup = AlternatesPopup(context, palette)

    /** The letter lifted clear of the finger, when the setting asks for it. */
    private val preview = KeyPreviewPopup(context, palette)

    /** Which key's row is open, so a stray drag from another key cannot move the selection. */
    private var popupKey: KeyView? = null

    /** Opens the row of alternates a hold on [key] offers, if it has any. */
    fun showAlternates(key: KeyView) {
        val chars = key.alternates()
        if (chars.isEmpty() || keyW <= 0f) return
        popupKey = key
        // Two things cannot float over the same key. The row is the more useful of them and
        // it is what the finger is now steering, so the flag goes - at once rather than after
        // its usual delay, which would leave the two overlapping for a visible moment.
        preview.dismiss()
        popup.show(this, key, chars, keyW, keyH, gap)
    }

    fun moveAlternates(key: KeyView, x: Float) {
        if (popupKey !== key) return
        popup.moveTo(key.left + x, keyW, gap)
    }

    /** What the finger was over when it came off, or null if no row was open. */
    fun takeAlternate(): Char? = if (popupKey == null) null else popup.selected

    fun hideAlternates() {
        popupKey = null
        popup.dismiss()
        // The flag too. Its own delay is there so that fast typing moves one window instead
        // of flashing several, and none of the things that call this are fast typing - they
        // are the keyboard being put away, a panel taking over, or a hold ending. A letter
        // still hanging over the keys through any of those is a letter over nothing.
        preview.dismiss()
    }

    // ------------------------------------------------------------------ languages

    /**
     * The list a hold on the space bar opens. See [LanguagesPopup].
     *
     * A window of its own for the same reason the alternates row is, only more so: half a
     * dozen names stand well above the keyboard's own top edge.
     */
    private val languagesPopup = LanguagesPopup(context, palette)

    /** The languages turned on, in the order they are offered. */
    private var languages: List<KeyboardLayout> = emptyList()

    /** Which of them is in use, which is where the list opens and what it comes back to. */
    private var languageInUse: KeyboardLayout? = null

    /** Whose hold is showing the list, on the same terms as [popupKey]. */
    private var languageKey: KeyView? = null

    /**
     * What the space bar's hold offers, and what it is currently saying.
     *
     * Both together because the list is only ever shown as a choice *from* somewhere: the
     * language in use is the one the finger starts on, and a hold that goes nowhere has to
     * come back to exactly it. Pushed down to every grid, cached ones included, the way the
     * hold time and the slide setting are - a hidden grid is one tap from being shown, and
     * one that missed a language being turned on would have a space bar whose hold offered
     * yesterday's list.
     */
    fun setLanguages(all: List<KeyboardLayout>, current: KeyboardLayout) {
        languages = all
        languageInUse = current
        val on = all.size > 1
        grids.values.forEach { grid -> grid.forEach { it.languageSwitch = on } }
        if (!on) hideLanguages()
    }

    /** Opens the language list a hold on the space bar offers, if there is a choice to make. */
    fun showLanguages(key: KeyView) {
        if (languages.size < 2 || keyH <= 0f) return
        languageKey = key
        // Two things cannot float over the keys at once, and a letter left over from the key
        // pressed before this one has no business hanging under a list of languages.
        preview.dismiss()
        val current = languages.indexOfFirst { it.id == languageInUse?.id }.coerceAtLeast(0)
        // A letter key's height rather than the space bar's own, which is the shorter bottom
        // row's and would set the names smaller than the space bar itself says them.
        languagesPopup.show(this, key, languages.map { it.name }, current, keyH, gap, headroom(key))
    }

    /**
     * The finger has moved, in the space bar's own coordinates.
     *
     * A tick per name crossed, which is the same reasoning the slide across the keys uses:
     * the list is above the hand steering it and the cell that lights up is the one thing
     * about this gesture the person may not be looking at.
     */
    fun moveLanguages(key: KeyView, x: Float, y: Float) {
        if (languageKey !== key) return
        if (languagesPopup.moveTo(key.left + x, key.top + y)) KeyboardHaptics.key(key)
    }

    /** Which language the finger was over, or null if no list was open. */
    fun takeLanguage(): KeyboardLayout? =
        if (languageKey == null) null else languages.getOrNull(languagesPopup.selected)

    fun hideLanguages() {
        languageKey = null
        languagesPopup.dismiss()
    }

    /**
     * How much screen there is above the space bar for the list to grow into.
     *
     * On the screen rather than in this view: the list is a window precisely so it can leave
     * the keyboard, and the one edge it must not cross is the top of the display. A gutter is
     * left up there so a long list stops short of the edge rather than running into it.
     */
    private fun headroom(key: KeyView): Float {
        val at = IntArray(2)
        getLocationOnScreen(at)
        val face = at[1] + key.top + gap / 2f + key.overhang
        return (face - gap - gap / 2f).coerceAtLeast(0f)
    }

    // ------------------------------------------------------------------ sliding

    /**
     * The key a slide began on, or null when none is running.
     *
     * Kept for the same reason [popupKey] is: the gesture belongs to one key and every other
     * key on the board is still free to be pressed by a second finger, whose drags must not
     * steer this one.
     */
    private var slideFrom: KeyView? = null

    /** The key the finger is over, lit and waiting to be taken. */
    private var slideOver: KeyView? = null

    /** A slide has begun on [key], which has just brought up the page being slid across. */
    fun startSlide(key: KeyView) {
        slideFrom = key
        slideOver = null
        // Two things cannot float over the same key, and the flag belongs to whichever key
        // the finger is over rather than to the one it is still resting on.
        preview.dismiss()
    }

    /**
     * The finger has moved, in [key]'s own coordinates - which is how they arrive, because
     * the whole gesture is delivered to the key it started on.
     */
    fun moveSlide(key: KeyView, x: Float, y: Float) {
        if (slideFrom !== key) return
        val next = keyAt(key.left + x, key.top + y)
        if (next === slideOver) return
        slideOver?.highlight(false)
        slideOver = next
        next?.highlight(true)
        // One tick per key crossed. The finger is out on keys it cannot see - that is what
        // the gesture is for - and the fill it lights is under the thumb doing the lighting,
        // so the tick is the only part of this that reaches the person reliably. The same
        // reasoning as the space bar's slide, which ticks per character for the same reason.
        if (next != null) KeyboardHaptics.key(key)
    }

    /** What the finger was over, or null if it was over nothing. */
    fun takeSlide(): KeyView? = slideOver

    /** Puts out the light, whether or not anything was taken. */
    fun endSlide() {
        slideOver?.highlight(false)
        slideFrom = null
        slideOver = null
    }

    /**
     * Which key is at a point in this view, or null for the gutter around the outside.
     *
     * Last match rather than first, because that is the one a finger landing there would have
     * pressed: the views overlap where one claims ground above another - the space bar does,
     * to catch the thumbs that aim low - and Android hands a touch to the topmost of them,
     * which is the later of the two.
     */
    private fun keyAt(x: Float, y: Float): KeyView? {
        for (i in keys.indices.reversed()) {
            val view = keys[i]
            if (view.visibility != VISIBLE) continue
            if (x >= view.left && x < view.right && y >= view.top && y < view.bottom) return view
        }
        return null
    }

    /**
     * A key has started or stopped looking pressed.
     *
     * Only keys that type something get a flag. Shift, backspace, the space bar and the rest
     * say what they do by what they do, and a flag reading `\u2190` over the backspace key is
     * an animation in the corner of the eye for no information at all - which is why every
     * keyboard that has ever done this has done it for characters only.
     */
    private fun showPreview(key: KeyView, pressed: Boolean) {
        if (!keyPreview) return
        if (!pressed) {
            preview.hide()
            return
        }
        if (key.key.action != null) return
        // The row of alternates is already up over this key, and has the finger.
        if (popupKey != null) return
        val text = key.output()
        if (text.isBlank()) return
        preview.show(this, key, text, heightOf(rowOf(key)), gap)
    }

    /**
     * Which row a key belongs to, so a flag over the shorter bottom row is the height of
     * *that* row rather than of a letter row it is not in.
     *
     * Found by walking the layout rather than remembered on the key, because the key views
     * are rebound as the layout changes and a remembered row would be the previous layout's.
     * It is a scan of at most a few dozen entries and it runs once per keystroke, not per
     * frame.
     */
    private fun rowOf(key: KeyView): Row {
        var index = 0
        val position = keys.indexOf(key)
        for (row in layout.rows) {
            if (position < index + row.keys.size) return row
            index += row.keys.size
        }
        return layout.rows.last()
    }

    /**
     * A window outlives the view that opened it unless something says otherwise.
     *
     * The keyboard's view is thrown away and rebuilt often - every layout change makes a new
     * one - and a row still showing at that moment would be left on screen with nothing
     * behind it and no way to dismiss it.
     */
    override fun onDetachedFromWindow() {
        hideAlternates()
        hideLanguages()
        endSlide()
        preview.dismiss()
        super.onDetachedFromWindow()
    }

    /**
     * The glyphs, parsed once and shared.
     *
     * [SvgIcon.fromAsset] re-reads and re-parses the file on every call - there is no cache
     * behind it - so a keyboard that asked per key, or worse per paint, would be parsing XML
     * in the input path. A handful of them, read when the view is built, and the keys are
     * handed the results.
     */
    private val glyphs = mutableMapOf<Action, Drawable?>()

    /**
     * The same drawings again, for corner marks.
     *
     * A separate cache rather than sharing [glyphs], because a `Drawable` carries its own
     * bounds and tint: one instance on a key's face and in another key's corner would be
     * drawn at whichever size and colour was set last. `SvgIcon` has no `ConstantState` to
     * copy from either, so `newDrawable()` quietly returns null - which is why the comma's
     * smiley did not appear at all the first time.
     */
    private val hintGlyphs = mutableMapOf<Action, Drawable?>()

    init {
        setBackgroundColor(groundColour())
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            // Only while the keyboard is actually up. A hidden input method still gets
            // dispatched insets - rotating the phone with the keyboard down provokes one -
            // and what a window nobody can see is told about the navigation bar is not what
            // will be drawn over it when it is shown again. Believing one of those is the
            // other way the bottom row ends up under the chevron and the globe.
            if (isShown) {
                val reported = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                // No bar, nothing to keep clear of. A reported zero is the one case where
                // reserving the frame height anyway would leave a band of dead keyboard.
                val next = if (reported <= 0) 0 else maxOf(reported, navigationBarFrameHeight())
                val changed = next != bottomInset || !insetsKnown
                bottomInset = next
                val config = resources.configuration
                insetsW = config.screenWidthDp
                insetsH = config.screenHeightDp
                if (changed) requestLayout()
            }
            insets
        }
    }

    /**
     * Asks for the insets again, because nothing else will.
     *
     * [bottomInset] has exactly one writer - the listener above - and that listener only runs
     * when the framework decides to dispatch insets to this view. Turn the phone while the
     * keyboard is down and it is not dispatched anything worth having, because there is no
     * keyboard on screen to be dispatched anything about; and an input view is often built
     * afresh after a configuration change, so what comes back is a view that has never been
     * told how much room the navigation bar wants and is holding zero. Reserving nothing is
     * what puts the bottom row of keys underneath the navigation bar - the keyboard
     * "rendered lower" - and it survives until something else happens to make the framework
     * dispatch insets.
     *
     * So it is asked for at the three moments the remembered answer stops being true or was
     * never true to begin with: on attach, whenever the configuration changes, and - the one
     * that matters after the phone has been turned and turned back - whenever the keyboard
     * comes back on screen, which is the first moment the answer can be believed at all.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) ViewCompat.requestApplyInsets(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Turning the phone changes every measurement here: the keys, the navigation bar, and
        // the bounds each cached grid is holding from the orientation it was laid out in. The
        // navigation bar's own reservation needs nothing said to it - it is remembered against
        // the screen it was measured on, and this is a different screen. See [insetsW].
        laidOutAt.clear()
        ViewCompat.requestApplyInsets(this)
        requestLayout()
    }

    // ---------------------------------------------------------------- contents

    fun setLayout(next: KeyboardLayout) {
        if (next.id == requested.id && keys.isNotEmpty()) return
        requested = next
        layout = drawn(next)
        rebuild()
    }

    fun currentLayout(): KeyboardLayout = requested

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        setBackgroundColor(groundColour())
        glyphs.clear()
        hintGlyphs.clear()
        popup.applyPalette(p)
        languagesPopup.applyPalette(p)
        preview.applyPalette(p)
        joystickView?.applyPalette(p)
        markGlyphs.clear()
        // Every grid, not just the one on screen: a hidden one is one tap from being shown
        // and would come back in the old colours.
        grids.values.forEach { grid ->
            grid.forEach { key ->
                key.applyPalette(p)
                key.glyph = key.key.action?.let { glyphFor(it) }
                key.hintGlyph = if (key.key.hint == null) {
                    key.key.holdAction?.let { hintGlyphFor(it) }
                } else {
                    null
                }
            }
        }
        // After the loop, which has just given the enter key the return arrow whatever the
        // field had asked it to show. See [applyEnterFace].
        applyEnterFace()
        invalidate()
    }

    /**
     * Shift, in all three of its states.
     *
     * Off, on for one letter, and locked. The keys have to be told because their faces
     * change, and the shift key itself has to be told twice over: it takes the accent while
     * shift is on at all, and a bar under its arrow only once it is locked.
     */
    fun setShiftState(on: Boolean, locked: Boolean) {
        shifted = on
        shiftLocked = locked
        keys.forEach { it.setShifted(on) }
        applyShiftStyle()
    }

    /**
     * Paints the shift key for the state shift is in.
     *
     * Its own function because it has to run again whenever a grid is shown as well as
     * whenever shift changes: the accent is put on by replacing the key, so a grid hidden
     * while shift was on comes back still wearing it.
     */
    private fun applyShiftStyle() {
        val shift = keys.firstOrNull { it.key.action == Action.SHIFT } ?: return
        val style = if (shifted) Style.ACCENT else Style.FUNCTION
        if (shift.key.style != style) {
            shift.key = shift.key.copy(style = style)
            shift.applyPalette(palette)
        }
        shift.underline = shiftLocked
    }

    /** Whether shift is locked rather than one-shot, so [applyShiftStyle] can redraw it. */
    private var shiftLocked = false

    /**
     * Relabels the enter key for whatever the field being typed into has asked for.
     *
     * A field that wants to send says `send`, one that wants a search gets the magnifier, one
     * that wants to go gets an arrow, and a note gets the return arrow - the same key doing
     * several jobs, which is the phone's behaviour as much as it is Android's.
     *
     * Two of them are marks where the rest are words - see [EnterMark], which says which and
     * why. A mark, when there is one, is the whole of the key's face; [label] is what is left
     * for the actions that have none.
     */
    fun setEnterKey(label: String?, mark: EnterMark = EnterMark.NONE) {
        enterLabel = label
        enterMark = mark
        applyEnterFace()
    }

    /** What the enter key was last told to show, so a repaint can put it back. */
    private var enterLabel: String? = null
    private var enterMark = EnterMark.NONE

    /**
     * Puts that on the key.
     *
     * Its own function because [applyPalette] hands every key the glyph its action calls for,
     * which for enter is the return arrow - so a palette change on a field that wanted `send`
     * used to swap the word out for an arrow and leave it there until the field was reopened.
     * Re-applying the remembered face afterwards is what keeps the two from fighting.
     */
    private fun applyEnterFace() {
        val view = keys.firstOrNull { it.key.action == Action.ENTER } ?: return
        val marked = enterMark != EnterMark.NONE
        view.key = view.key.copy(label = if (marked) "" else enterLabel.orEmpty())
        view.glyph = when {
            marked -> markGlyph(enterMark)
            enterLabel == null -> glyphFor(Action.ENTER)
            else -> null
        }
        // Neither mark is the return arrow, and neither is drawn at its size: they come out of
        // the appbar set, and they are standing in for a word rather than labelling a key.
        // See [KeyView.ENTER_MARK_GLYPH].
        view.glyphFit = if (marked) KeyView.ENTER_MARK_GLYPH else null
        view.applyPalette(palette)
    }

    /**
     * The marks the enter key can wear, held like the rest.
     *
     * A cache of their own rather than a corner of [glyphs], which is keyed by what a key
     * *does* - and this key does enter whichever mark is on it. These belong to the field's
     * request, not to an action. See [glyphs] for why nothing here parses per paint.
     */
    private val markGlyphs = mutableMapOf<EnterMark, Drawable?>()

    private fun markGlyph(mark: EnterMark): Drawable? = markGlyphs.getOrPut(mark) {
        when (mark) {
            EnterMark.SEARCH -> SvgIcon.fromAsset(context, "$ICONS/appbar.magnify.svg")
            EnterMark.GO -> SvgIcon.fromAsset(context, "$ICONS/appbar.arrow.right.svg")
            EnterMark.NONE -> null
        }?.mutate()
    }

    /**
     * Relabels the `&123` key, which says `abc` once it has taken you to a symbol page.
     *
     * The symbol pages share the letters' bottom row rather than declaring one of their own,
     * because every other key on it - emoji, comma, space, full stop, enter - is identical on
     * all four layouts, and a second copy would be five keys of duplication kept in step by
     * hand for the sake of one label.
     */
    fun setSymbolsLabel(label: String) {
        val view = keys.firstOrNull { it.key.action == Action.SYMBOLS } ?: return
        view.key = view.key.copy(label = label)
        view.invalidate()
    }

    /**
     * Sets what the contextual key types - the one beside the space bar.
     *
     * See [Key.contextual]. Its shifted form is set to the same character, because these are
     * punctuation rather than letters and there is nothing for shift to do to them.
     */
    fun setContextualKey(character: String) {
        val view = keys.firstOrNull { it.key.contextual } ?: return
        if (view.key.output == character) return
        val plain = character == ","
        view.key = view.key.copy(
            output = character,
            label = character,
            shifted = character,
            // The emoji panel rides on this key's hold while it is the comma. In a field that
            // wanted a slash or an at sign it is not a comma any more, and a hold offering
            // emoji on a key that now types `/` is a hold nobody would look for.
            holdAction = if (plain) Action.EMOJI else null
        )
        view.hintGlyph = if (plain) hintGlyphFor(Action.EMOJI) else null
        view.invalidate()
    }

    /**
     * What the space bar says: the name of the language being typed.
     *
     * The phone put it there and it earns its place - it is the only thing on screen that
     * says which of several keyboards you are on, and the moment somebody turns on a second
     * language it is the difference between knowing and guessing.
     *
     * Remembered rather than merely applied, because the space bar outlives the layout it is
     * currently part of: the symbol pages have one too and belong to no language, so the name
     * has to survive a trip to `&123` and back. See [applySpaceLabel], called from [rebuild].
     */
    private var spaceLabel = ""

    fun setSpaceLabel(label: String) {
        if (label == spaceLabel) return
        spaceLabel = label
        applySpaceLabel()
    }

    private fun applySpaceLabel() {
        val view = keys.firstOrNull { it.key.action == Action.SPACE } ?: return
        // A single space rather than an empty string when there is no name: the layouts spell
        // the blank key that way, and [KeyView.faceLabel] reads that exact value as "blank".
        view.key = view.key.copy(label = spaceLabel.ifEmpty { " " })
        view.invalidate()
    }

    /**
     * Points the grid at whatever [layout] now is.
     *
     * The views are **reused**, not rebuilt. Tearing the grid down and making another one is
     * the obvious way to change layout and it is the reason `&123` felt slow: thirty-four
     * views destroyed and thirty-four constructed, every one of them looking up two fonts and
     * allocating its paints, and - worse than the cost - a moment in which the keys the user
     * was about to press did not exist, because a view added this frame has no size until the
     * next layout pass. A tap that arrived in that gap hit nothing at all.
     *
     * Rebinding avoids both. The views stay attached and keep their bounds, so a symbol
     * tapped the instant the page appears lands on a key that is already there, and the only
     * work left is the measure pass that any layout change needs anyway.
     */
    /**
     * Puts the grid for [layout] on screen, building it the first time and only then.
     *
     * The whole of the switch, in the ordinary case, is hiding one list of views and showing
     * another. Nothing is created, nothing is measured, and the layout pass is skipped
     * outright when the grid coming back was last laid out at the width it is coming back to
     * - which it was, unless the keyboard has changed size since.
     */
    private fun rebuild() {
        keys.forEach { it.visibility = GONE }

        val fresh = layout.id !in grids
        keys = grids.getOrPut(layout.id) {
            layout.rows.flatMap { it.keys }.map { key ->
                KeyView(context, key, palette).also {
                    it.listener = listener
                    it.holdMillis = holdMillis
                    it.caretSlide = !joystick
                    it.slideSelect = slideKeys
                    it.languageSwitch = languages.size > 1
                    it.theme = theme
                    it.onPressedChanged = ::showPreview
                    addView(it)
                }
            }
        }

        keys.forEach { view ->
            view.visibility = VISIBLE
            // Cleared rather than assumed. A grid that was hidden mid-press - which is what
            // happens to the letters when `&123` is released over them - would otherwise come
            // back still lit, or still believing a hold had fired on it.
            view.bind(view.key)
            view.setShifted(shifted)
            view.glyph = view.key.action?.let { glyphFor(it) }
            view.hintGlyph = if (view.key.hint == null) {
                view.key.holdAction?.let { hintGlyphFor(it) }
            } else {
                null
            }
        }

        applySpaceLabel()
        applyShiftStyle()
        applyEnterFace()

        // A grid built just now went in above the dot, which has to stay on top of the keys
        // it is painting a hole in. Only on the pass that built one: this is a re-add, and
        // re-adding on every layout switch would ask for a layout pass that switch avoids.
        if (fresh) joystickView?.let { removeView(it); addView(it) }

        // A grid coming back at the width it left at is already in the right place, and
        // asking for a layout pass would be asking for the delay this exists to remove. The
        // dot is the one thing that still has to move: its corner belongs to *this* layout's
        // bottom row, and the grid whose bounds are being reused is a different one.
        if (!fresh && laidOutAt[layout.id] == width) {
            placeJoystick()
            return
        }

        // Otherwise it needs laying out, and **now** rather than on the next frame. A view
        // that has just been added has no bounds until it is laid out, and a key with no
        // bounds cannot be pressed - so a grid shown and tapped inside the same frame would
        // swallow that tap, which during fast typing is not a hypothetical. Waiting for the
        // frame is the very delay this whole arrangement exists to remove; it would simply
        // have moved from every switch to the first one.
        if (width <= 0 || height <= 0) {
            requestLayout()
            return
        }
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        if (measuredHeight == height) {
            layout(left, top, right, bottom)
        } else {
            // The keyboard is a different height for this layout - a number pad is shorter -
            // so the window has to resize and only a real pass can do that.
            requestLayout()
        }
    }



    /** A second instance of a glyph, for a corner mark. See [hintGlyphs]. */
    private fun hintGlyphFor(action: Action): Drawable? = hintGlyphs.getOrPut(action) {
        when (action) {
            Action.EMOJI -> SvgIcon.fromAsset(context, "$ICONS/appbar.smiley.happy.svg")
            Action.SETTINGS -> SvgIcon.fromAsset(context, "$ICONS/appbar.settings.svg")
            else -> null
        }
    }

    /**
     * The glyph a function key wears, or null for the ones that are written rather than drawn.
     *
     * All but one come out of the phone's own icon set, which is already in the assets and
     * already the right weight. Enter is the exception and is a vector of its own: the set
     * has no return arrow, having never needed one.
     */
    private fun glyphFor(action: Action): Drawable? = glyphs.getOrPut(action) {
        val drawable = when (action) {
            Action.SHIFT -> SvgIcon.fromAsset(context, "$ICONS/appbar.arrow.up.svg")
            Action.BACKSPACE -> context.getDrawable(R.drawable.kb_backspace)
            Action.ENTER -> context.getDrawable(R.drawable.kb_enter)
            Action.EMOJI -> SvgIcon.fromAsset(context, "$ICONS/appbar.smiley.happy.svg")
            Action.SETTINGS -> SvgIcon.fromAsset(context, "$ICONS/appbar.settings.svg")
            else -> null
        }
        drawable?.mutate()
    }

    // ---------------------------------------------------------------- geometry

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)

        // A row of twelve keys is a row of *narrower* keys, not a shorter keyboard.
        //
        // Height, and the gutters, come from a fixed reference of ten columns rather than
        // from whatever the current layout has. Deriving them from the layout's own key width
        // - which is the obvious thing, and was what this did - made the whole Macedonian
        // keyboard shrink by a fifth: shorter keys, shorter rows, a shorter keyboard, and the
        // app above it reflowing every time the language changed. Only the width of a key
        // should answer to how many of them there are.
        reference = verticalUnit(resources, w, REFERENCE_COLUMNS, keyHeightScale, numberRow)
        gap = reference * GAP
        keyH = reference * KEY_ASPECT * keyHeightScale
        // The keys still fill the width, whatever the height was capped to. That is the whole
        // shape of a landscape keyboard: as wide as the screen, no taller than it needs.
        keyW = (w - layout.columns * gap) / layout.columns

        // The cached grids are holding bounds measured against the old geometry. Rotating is
        // the case: a grid laid out in portrait and shown again in landscape would come back
        // in portrait's positions, because [rebuild] trusts [laidOutAt] to say otherwise.
        if (keyW != lastKeyW || keyH != lastKeyH) {
            laidOutAt.clear()
            lastKeyW = keyW
            lastKeyH = keyH
        }

        val h = contentHeight() + if (insetsKnown) bottomInset else navigationBarFrameHeight()

        var index = 0
        for (row in layout.rows) {
            val rowH = heightOf(row)
            for (key in row.keys) {
                val view = keys.getOrNull(index++) ?: continue
                view.setMetrics(keyW, rowH, gap)
                // Taller than it is drawn, where the key asks to be. See [Key.overhangTop].
                val overhang = keyH * key.overhangTop
                view.overhang = overhang
                view.measure(
                    MeasureSpec.makeMeasureSpec(spanWidth(key.span) + gap.toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(
                        (rowH + overhang).toInt() + gap.toInt(), MeasureSpec.EXACTLY
                    )
                )
            }
        }
        joystickView?.let { dot ->
            dot.setMetrics(reference)
            val side = MeasureSpec.makeMeasureSpec(dot.side(), MeasureSpec.EXACTLY)
            dot.measure(side, side)
        }
        setMeasuredDimension(w, h)
    }

    /** What a key of [span] units is painted at, gutters between its units included. */
    private fun spanWidth(span: Float): Int = (span * keyW + (span - 1f) * gap).toInt()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var index = 0
        var y = gap / 2f
        for (row in layout.rows) {
            var x = gap / 2f + row.indentStart * (keyW + gap)
            val rowH = heightOf(row)
            for (key in row.keys) {
                val view = keys.getOrNull(index++) ?: continue
                val painted = spanWidth(key.span)
                // The view is half a gutter larger than the key on every side, so it is
                // laid out shifted back by that much and given that much extra.
                // Started higher by whatever it overhangs, and ending where it always did,
                // so the face lands in the same place while the target reaches further up.
                val overhang = keyH * key.overhangTop
                view.layout(
                    (x - gap / 2f).toInt(),
                    (y - gap / 2f - overhang).toInt(),
                    (x - gap / 2f).toInt() + painted + gap.toInt(),
                    (y - gap / 2f).toInt() + rowH.toInt() + gap.toInt()
                )
                x += painted + gap
            }
            y += rowH + gap
        }
        placeJoystick()
        // What lets the next switch back to this grid skip the layout pass entirely: these
        // bounds are good for as long as the keyboard is this wide. See [laidOutAt].
        laidOutAt[layout.id] = r - l
    }

    /**
     * Puts the dot on the crossing of the two gutters around the bottom row's first key.
     *
     * Not a position of its own but a corner of the grid, so it lands in the same place on
     * every layout and at every key height without anything having to be told twice. The one
     * place on a full keyboard where nothing is.
     *
     * Worked out from the metrics rather than picked up while the rows are being laid out,
     * which is what lets it be re-placed on the fast path in [rebuild]. That path skips the
     * layout pass entirely for a grid coming back at the width it left at - which is the whole
     * point of it - and a dot that could only be positioned during a pass would be left at the
     * previous layout's corner. The letters and the symbol pages share a bottom row so it
     * would never have shown there; the number pad has four columns and it would have shown
     * every time.
     */
    private fun placeJoystick() {
        val dot = joystickView ?: return
        val row = layout.rows.lastOrNull() ?: return
        val first = row.keys.firstOrNull() ?: return
        if (keyW <= 0f || dot.measuredWidth <= 0) return

        // Down to the top of the last row, which is where the rows above it end.
        var top = gap / 2f
        for (i in 0 until layout.rows.lastIndex) top += heightOf(layout.rows[i]) + gap
        // And across to the far side of that row's first key.
        val after = gap / 2f + row.indentStart * (keyW + gap) + spanWidth(first.span) + gap

        // The centre of each gutter, rather than an edge of either.
        val centreX = after - gap / 2f
        val centreY = top - gap / 2f

        val half = dot.measuredWidth / 2
        val left = (centreX - half).toInt()
        val topLeft = (centreY - half).toInt()
        dot.layout(left, topLeft, left + dot.measuredWidth, topLeft + dot.measuredHeight)
    }

    /**
     * How tall the system's own bar at the foot of the keyboard is.
     *
     * Read out of the framework's resources by name because there is no public constant for
     * it - `navigation_bar_frame_height` is the dimension the system lays that strip out to.
     * Looking it up by name is doing what the platform does internally rather than guessing,
     * and if a device does not have it, 48dp is the value it has had for as long as there
     * has been a navigation bar to measure.
     */
    private fun navigationBarFrameHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        if (id != 0) {
            val fromSystem = resources.getDimensionPixelSize(id)
            if (fromSystem > 0) return fromSystem
        }
        return (FALLBACK_BAR_DP * resources.displayMetrics.density).toInt()
    }

    /**
     * What the keyboard sits on.
     *
     * Not [KeyboardPalette.background]. The phone's keyboard ground was a shade off black, a
     * step darker than the keys rather than the page behind them, which is what stops a
     * field of dark keys from reading as holes cut in the screen.
     *
     * A theme that draws its own keys wants none of that and is given the shell's own dialog
     * colour instead. A step darker behind them is how a flat key is told from the field it is
     * on, and a key with an edge does not need telling - its bevel or its outline has already
     * said it. Windows put its buttons straight onto the dialog they belonged to, in the same
     * colour, and the gutters between these keys are that dialog showing through.
     */
    private fun groundColour(): Int =
        if (theme.drawsKeys) palette.background
        else androidx.core.graphics.ColorUtils.blendARGB(
            palette.background, palette.foreground, GROUND_ALPHA
        )

    /**
     * How tall the rows come to, gutters included and the navigation bar excluded.
     *
     * Not simply four times a row any more: the bottom row is shorter than the letters. Asked
     * for by anything that has to be exactly as tall as the keyboard - the emoji panel, which
     * replaces it, and which would otherwise resize the window every time it opened.
     */
    fun contentHeight(): Int {
        val rows = layout.rows.sumOf { (keyH * scaleOf(it) + gap).toDouble() }
        // Added whole rather than summed row by row with the [stretch] in it. The letters add
        // exactly this number for their number row, so the two pages come to exactly the same
        // total - and one pixel between them is a window resize on every trip to `&123`.
        val missing = if (fillsNumberRow()) missingRowHeight().toDouble() else 0.0
        return (rows + missing).toInt()
    }

    /** How tall a row is, allowing for the setting. See [shortBottomRow]. */
    private fun scaleOf(row: Row): Float = if (shortBottomRow) row.heightScale else 1f

    /** What the keys currently measure, for anything that has to line up with them. */
    fun unitWidth(): Float = keyW

    /**
     * The unit everything that is *not* a key is measured in. See [referenceUnit].
     *
     * Held from [onMeasure] rather than worked out again, so there is one answer and it is
     * the one the keys were actually built against.
     */
    private var reference = 0f

    /**
     * One key's width **on a ten-column layout**, whatever layout is currently up.
     *
     * The companion to [unitWidth], and the difference between them is the difference between
     * a key and the keyboard. [unitWidth] is how wide a key is *here*: twelve Cyrillic letters
     * across gives a narrower one, a four-column number pad a much wider one, and anything
     * drawn to line up with the keys has to follow it. This is the keyboard's own scale, fixed
     * at the ten columns every proportion in it was measured from, and it is what the gutters,
     * the row heights and the suggestion bar are already built on - see [onMeasure], where a
     * unit taken from the layout made the Macedonian keyboard shrink by a fifth.
     *
     * Anything anchored to the bar rather than to a key belongs on this one. The clipboard
     * list is the case that found it: it takes a unit to size its rows and its text with, was
     * handed the layout's, and so came up two and a half times its usual size over a number
     * pad - a list of clips in letters the height of the keypad's digits, which is not a
     * bigger version of the same control but a different one.
     */
    fun referenceUnit(): Float =
        if (reference > 0f) reference
        else verticalUnit(resources, width, REFERENCE_COLUMNS, keyHeightScale, numberRow)
    fun keyHeight(): Float = keyH
    fun gutter(): Float = gap
    /**
     * What is held clear at the foot of the keyboard, for anything that has to match it.
     *
     * The emoji panel stands in for the keys and has to reserve exactly the same, or it would
     * resize the window every time it opened. Reports the provisional reservation too, so the
     * panel agrees with the keys even before the framework has said anything - see
     * [insetsKnown].
     */
    fun bottomReserved(): Int = if (insetsKnown) bottomInset else navigationBarFrameHeight()

    companion object {

        /** The gutter, as a fraction of one key's width. Measured off the phone: 6.5 of 61. */
        const val GAP = 0.107f

        /**
         * How much taller than wide a key is.
         *
         * The one proportion that is nothing like the calculator's, whose keys are wider
         * than they are tall. A keyboard's are the other way up because ten of them have to
         * fit across a screen that a calculator only puts four on.
         */
        const val KEY_ASPECT = 1.56f

        /** `#1A1A1A` on Dark. See [groundColour]. */
        /**
         * How much darker than the keys the ground is. Not private: the theme previews on the
         * settings page are drawn from the same number, so that the picture of a keyboard and
         * the keyboard cannot disagree about it. See [ThemePreview].
         */
        const val GROUND_ALPHA = 0.102f

        private const val ICONS = "icons"

        /**
         * One key's width at a given keyboard width, shared so nothing has to copy it.
         *
         * The candidate bar sizes its text and its glyph slots in key widths like everything
         * else, and it is measured before the keys are - so it cannot ask them and must be
         * able to work it out. Two copies of this expression drifting apart would show up as
         * a bar whose text was subtly the wrong size on one layout.
         */
        fun unitWidth(width: Int, columns: Float): Float = width / (columns * (1f + GAP))

        /**
         * The unit that everything vertical is measured from, which is not always the width.
         *
         * Every proportion in this keyboard is a fraction of one key's width, and in portrait
         * that is exactly right: a wider screen should give a taller keyboard, in the same
         * proportions, and it does.
         *
         * Turn the phone sideways and it stops being right. The screen is now twice as wide
         * and half as tall, so a unit taken from the width doubles - and four rows at
         * [KEY_ASPECT] came to more than the whole height of the screen. The keyboard was not
         * merely too big; it did not fit, which is what "broken in landscape" was.
         *
         * So the unit is capped by what there is room for. The keyboard and its suggestion
         * bar together come to roughly [totalUnits] of them, and they may have
         * [MAX_HEIGHT_SHARE] of the screen, which fixes the largest unit that fits. In
         * portrait the width is far below that cap and nothing changes at all; in landscape
         * the cap decides, and the result is keys that are wide and short - which is what a
         * landscape keyboard should be, and what every other one does.
         *
         * Taken by every part of the keyboard that has a height, including the suggestion bar
         * in [KeyboardHost], so that they agree.
         */
        fun verticalUnit(
            resources: android.content.res.Resources,
            width: Int,
            columns: Float,
            heightScale: Float = 1f,
            numberRow: Boolean = false
        ): Float {
            val fromWidth = unitWidth(width, columns)
            // From the configuration rather than from `displayMetrics`. Both describe the
            // screen and only one of them is guaranteed to describe it *now*: the
            // configuration is what the system revises when the phone turns, and it is what
            // every other part of an app reads the orientation from. Display metrics on a
            // view in an input method have been known to lag it, and a cap taken from the
            // wrong orientation is a keyboard built to the wrong height.
            val screen = resources.configuration.screenHeightDp * resources.displayMetrics.density
            if (screen <= 0f) return fromWidth
            return minOf(fromWidth, screen * MAX_HEIGHT_SHARE / totalUnits(heightScale, numberRow))
        }

        /**
         * The column count everything vertical is measured against.
         *
         * Ten, because that is what the phone's own layout had and what the reference the
         * proportions were measured from is. A layout with more columns gets narrower keys of
         * the same height; one with fewer would get wider ones. See [onMeasure].
         */
        const val REFERENCE_COLUMNS = 10f

        /**
         * How much of the screen the keyboard and its bar may take between them.
         *
         * Only ever reached in landscape - see [verticalUnit]. Three fifths leaves the app
         * above enough to be worth typing into, which in landscape is the scarce thing.
         */
        const val MAX_HEIGHT_SHARE = 0.6f

        /**
         * The whole keyboard's height, in units, for that cap.
         *
         * Four rows at [KEY_ASPECT], a gutter each, and the suggestion bar. Deliberately the
         * tall case - four full rows, as though the shorter bottom row were switched off -
         * because the cap has to hold for the taller of the two and a keyboard that fits when
         * shortened is not a keyboard that fits.
         *
         * [heightScale] multiplies the rows and nothing else, which is what makes the cap
         * still hold when somebody asks for taller keys in landscape: the keys grow, the
         * total grows with them, and the unit the whole keyboard is built from shrinks to
         * keep it inside the share of the screen it is allowed. The result is a keyboard that
         * honours the setting as far as there is room for it and then stops, rather than one
         * that runs off the top of the screen.
         *
         * [numberRow] is a fifth row, counted full height for the same reason the bottom row
         * is.
         */
        fun totalUnits(heightScale: Float, numberRow: Boolean = false): Float {
            val rows = if (numberRow) 5f else 4f
            return rows * KEY_ASPECT * heightScale + rows * GAP + CandidateBar.HEIGHT
        }

        /**
         * How far the key-height setting may be moved.
         *
         * Two thirds to half again. The floor is where a row of keys stops clearing Android's
         * 48dp target on an ordinary phone, which is the point past which a shorter keyboard
         * is not a preference but a keyboard that is hard to hit; the ceiling is where four
         * rows and the bar start crowding out the thing being typed into even in portrait.
         * Anything wanted outside that range is a request for a different keyboard.
         */
        const val MIN_HEIGHT_SCALE = 0.65f
        const val MAX_HEIGHT_SCALE = 1.5f

        /** What a navigation bar is, and has been, when the system will not say. */
        private const val FALLBACK_BAR_DP = 48f

        /** The alternates row: how tall each cell is against a key. */
        private const val POPUP_HEIGHT = 0.82f
    }
}

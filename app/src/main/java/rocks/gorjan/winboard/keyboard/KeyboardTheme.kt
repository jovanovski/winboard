package rocks.gorjan.winboard.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.ColorInt
import rocks.gorjan.winboard.KeyboardPalette

/**
 * What the keys are made of.
 *
 * The accent and the light-or-dark are not a theme. They are two settings Windows Phone
 * itself had, chosen on this app's own page now that there is no launcher to inherit them
 * from, and a keyboard wearing a different accent is still the same keyboard.
 *
 * What is worth choosing is one thing, and it is not another accent: a keyboard that is not a
 * Windows Phone keyboard at all. Three of the four here are keyboards made out of some older
 * Windows shell's push button, and a push button is precisely what a palette cannot describe.
 * Windows 98's was *raised* - two bands of light down the top and left, two of shadow down the
 * bottom and right - and pressing one turned the bevel inside out so the button appeared to go
 * into the screen. XP's was a rounded rectangle with a smooth white-to-cream wash down it and
 * a navy outline. Vista's was the same shape in cool grey with a gloss across the top half,
 * and it answered a press by turning blue. None of that can be said in a set of colours, which
 * is why this is a theme and not one more accent.
 *
 * Three parts, and they are deliberately separate:
 *
 *  - [palette] answers for everything that is only a colour. A theme hands back the
 *    [KeyboardPalette] the whole keyboard is painted from, so the suggestion bar, the emoji
 *    panel, the clipboard list and every popup follow it without knowing a theme exists.
 *  - [chrome] answers for the thing a palette cannot carry, which is the *shape* of a key.
 *    Null for [PHONE], because a Windows Phone key has no shape - it is a rectangle of one
 *    colour, which is the whole of what it ever was. See [KeyChrome].
 *  - [tahoma] and [flag] answer for the two smaller things that follow from the era rather
 *    than from the drawing.
 *
 * Kept in the app's settings file - see [KeyboardSettings.getThemeId]. It is stored as [id]
 * rather than as an ordinal, so the numbering of this enum is free to change and a value
 * written by a later version falls back to [PHONE] rather than to whatever now sits at that
 * index.
 */
enum class KeyboardTheme(
    /** What is written to the settings file. Never change one of these. */
    val id: String,
    /** What the settings page calls it. */
    val title: String,
    /** And what the settings page says about it, underneath. */
    val note: String
) {

    PHONE(
        "phone",
        "Windows Phone 8.1",
        "flat keys in the accent and background chosen below. a pressed key lights up. " +
            "this is the keyboard the phone had."
    ),

    WIN98(
        "win98",
        "Windows 98",
        "beige keys with a raised edge, in Tahoma. a pressed key goes into the screen " +
            "instead of lighting up, the accent is the navy that version of Windows " +
            "selected things in, and the symbols key wears the flag."
    ),

    WINXP(
        "winxp",
        "Windows XP",
        "Luna's push button: rounded, outlined in navy and white, and it dips into the " +
            "blue when pressed. Tahoma, on the pale blue XP put behind the panels in " +
            "every Explorer window."
    ),

    WINXP_TASKBAR(
        "winxp_taskbar",
        "Windows XP, taskbar",
        "the same keys on the blue of the taskbar itself. louder, and the one XP surface " +
            "everybody can picture without being reminded of it."
    ),

    VISTA(
        "vista",
        "Windows Vista",
        "Aero's push button: the same shape in cool grey, glossed across the top half, " +
            "and it answers a press by going blue rather than by lighting up. Segoe UI, " +
            "which is the face Vista introduced."
    );

    /**
     * How a key is drawn, or null where there is nothing to draw.
     *
     * Asked as a question about the theme rather than tested against each name at the dozen
     * places that care, which is what lets a fourth shell be one entry here and nothing else.
     * It was a `bevelled` flag while Windows 98 was the only one of these; a flag answers
     * "raised or flat" and stops being able to answer anything the moment a second kind of
     * button exists.
     */
    internal val chrome: KeyChrome?
        get() = when (this) {
            PHONE -> null
            WIN98 -> KeyChrome.CLASSIC
            WINXP -> KeyChrome.LUNA
            WINXP_TASKBAR -> KeyChrome.LUNA_TASKBAR
            VISTA -> KeyChrome.AERO
        }

    /**
     * Whether the keys are a shape this theme draws for itself.
     *
     * The question everything that is *not* a key asks - the ground behind them, the
     * suggestion bar, the emoji panel, the caret dot - because all of those want the same
     * answer for all three Windows shells: take your colours from the theme's own palette
     * rather than from a wash over the phone's background.
     */
    val drawsKeys: Boolean get() = chrome != null

    /**
     * Whether the keyboard is set in Tahoma.
     *
     * 98 and XP both were: Tahoma was the shell font of that decade, and MS Sans Serif,
     * which 98 actually shipped with, is a bitmap face that exists at eight sizes and nothing
     * in between - it has no business on a screen at this density, and Tahoma is what
     * Microsoft replaced it with. Vista is the release that moved to Segoe UI, which is the
     * face this keyboard is already set in, so it simply keeps it.
     */
    val tahoma: Boolean get() = this == WIN98 || this == WINXP || this == WINXP_TASKBAR

    /**
     * Whether the `&123` key wears the Windows flag instead of the words.
     *
     * Windows 98 alone. The mark is the Start button's own, cropped from it - see
     * `res/drawable-nodpi/win98_flag.png` - and there is no equally genuine XP or Vista one
     * to hand: XP's sits anti-aliased against the green of its Start button and cannot be
     * lifted off it cleanly, and half a pair is worse than neither.
     */
    val flag: Boolean get() = this == WIN98

    /**
     * The colours to paint in, given the ones the user chose.
     *
     * [PHONE] hands back what it was given, which is not a special case being tolerated - it
     * is the theme. The Windows Phone keyboard *is* the chosen accent on the chosen
     * background, and the accent picker is the whole of what there is to say about it. The
     * other three are shells with colours of their own and take no notice of either setting.
     */
    fun palette(chosen: KeyboardPalette): KeyboardPalette = chrome?.palette ?: chosen

    companion object {

        /**
         * The theme [id] names, or [PHONE] for anything else.
         *
         * Anything else covers a fresh install, a settings file written by a later version
         * that knows a theme this one does not, and a restored backup from either.
         */
        fun of(id: String?): KeyboardTheme = entries.firstOrNull { it.id == id } ?: PHONE
    }
}

/**
 * How one Windows shell drew a push button, and the whole of what a theme adds to a palette.
 *
 * Everything else about a theme is a colour and travels in a [KeyboardPalette]; this is the
 * part that cannot. A key here is not a rectangle filled in - it is a shape with an edge, and
 * the edge is what says which decade it is from and whether it is being pressed. So the
 * drawing lives with the colours it needs rather than being a chain of `when` branches at
 * every place that paints something key-shaped: the grid, the key-preview float and the
 * alternates row all hand over a rectangle and are told nothing about which shell answered.
 *
 * It owns its paints. They were borrowed from the caller when Windows 98 was the only one of
 * these and a bevel needs nothing but a colour; a wash needs a `Shader`, and a `Shader` left
 * on somebody else's paint is the next thing they draw coming out striped. One instance per
 * theme, shared by every key on screen, which is safe because all of this happens on the one
 * thread that draws.
 */
internal sealed interface KeyChrome {

    /** The colours the whole keyboard is painted from while this chrome is in force. */
    val palette: KeyboardPalette

    /**
     * What is written on a key.
     *
     * Not [KeyboardPalette.foreground], and the difference is not pedantry. That one is the
     * ink of the *ground* - the suggestion bar's words, the emoji panel's labels - and for
     * two of these three themes the ground and the key face are near enough the same colour
     * that one value answered for both. Put the keys on something the keys are not, as Luna
     * does on its taskbar blue, and the two part company completely: white on the ground,
     * black on the keys, and a single value would make one of them invisible.
     */
    @get:ColorInt
    val ink: Int

    /** The same, for a corner mark: that shell's `COLOR_GRAYTEXT`, on the key's own face. */
    @get:ColorInt
    val subtleInk: Int

    /**
     * How far what is written on a key moves when the key is pressed, in pixels.
     *
     * Windows 98's button moved its caption a pixel down and to the right, with the light,
     * because the face it is written on went into the screen and a label that stayed put
     * looked like a hole with something floating over it. XP and Vista answer a press by
     * changing colour instead and move nothing, so they say zero.
     */
    fun shove(density: Float): Float = 0f

    /**
     * Draws one key, from its fill to its edge.
     *
     * @param pressed a thumb is on it now.
     * @param default the key is *switched on* - shift while shift is held - which every one
     *   of these shells drew as the dialog's default button: the ordinary button with a
     *   frame around the outside of it. The phone's theme fills the whole key with the accent
     *   instead, which is why that case never reaches here.
     */
    fun key(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        density: Float,
        pressed: Boolean,
        default: Boolean
    )

    companion object {
        /** Windows 98's raised bevel. See [Win98]. */
        val CLASSIC: KeyChrome = Bevelled

        /**
         * Windows XP's, in Luna Blue.
         *
         * The button is the one the sibling launcher sampled off the shell itself and keeps
         * in its `WinPalette.XP`, so the two applications draw the same button. The pressed
         * wash runs the other way up - dark at the top - which is how a rounded button says
         * "pushed in" once it has stopped having a bevel to turn inside out.
         *
         * **The ground is not `COLOR_3DFACE`.** That is `#ECE9D8`, the warm beige-grey every
         * XP *dialog* was, and a keyboard drawn on it comes out looking yellow - which is a
         * fair description of an XP dialog and a poor description of XP. What people picture
         * is the blue, so both of these are blue and they are the two blues XP had: the pale
         * wash behind the task panes down the left of every Explorer window, and the taskbar.
         *
         * This one is the pale wash, and it is the quieter of the two - close enough to a
         * dialog that the keys still read as buttons sitting on a window, which is the one
         * place Luna genuinely put buttons on something other than the dialog face. The
         * accent is Luna's own `COLOR_HIGHLIGHT`, which stands against it perfectly well.
         */
        val LUNA: KeyChrome = luna(
            KeyboardPalette(
                accent = 0xFF316AC5.toInt(),
                background = 0xFFD6DFF7.toInt(),
                foreground = Color.BLACK,
                foregroundSubtle = 0xFF5A6070.toInt(),
                inactive = Color.argb(51, 0, 0, 0),
                chrome = 0xFFD6DFF7.toInt(),
                onChrome = Color.BLACK,
                isDark = false
            )
        )

        /**
         * The same keys on the taskbar.
         *
         * `#245CD7` is the taskbar's body colour, read off the tile the sibling launcher
         * draws its own taskbar from - down a column of it the wash starts at `#2562C6`,
         * brightens through the band at the top, and settles here.
         *
         * Which puts white buttons on a saturated blue, and that is an arrangement the
         * taskbar itself had: the notification area, the Start button, every toolbar dropped
         * onto it. It is also what forced the ground's ink and the keys' ink apart - white on
         * the blue, black on the keys. See [ink].
         *
         * The accent is the taskbar's own highlight, the pale band across the top of it,
         * rather than Luna's `COLOR_HIGHLIGHT` `#316AC5` - which is a fine selection navy
         * against a dialog and all but invisible against this.
         */
        val LUNA_TASKBAR: KeyChrome = luna(
            KeyboardPalette(
                accent = 0xFF4993E6.toInt(),
                background = 0xFF245CD7.toInt(),
                foreground = Color.WHITE,
                foregroundSubtle = Color.argb(179, 255, 255, 255),
                inactive = Color.argb(51, 255, 255, 255),
                chrome = 0xFF245CD7.toInt(),
                onChrome = Color.WHITE,
                isDark = true
            )
        )

        /**
         * Luna's button, on whichever of XP's two blues it has been given.
         *
         * The button is the same object in both: the shell drew one push button and put it on
         * everything, and two copies of these numbers that could drift apart would be two
         * XP themes rather than one XP theme on two grounds.
         */
        private fun luna(palette: KeyboardPalette): KeyChrome = Rounded(
            palette = palette,
            ink = Color.BLACK,
            subtleInk = 0xFF9A9A9A.toInt(),
            border = 0xFF003C74.toInt(),
            // White, with the faintest cool wash down it rather than none at all. Flat white
            // is not a button - it is a rectangle - and the whole of what says this one is
            // raised is that the top is lighter than the bottom. At six levels the wash is
            // not a colour anybody would name; it is only the light.
            top = 0xFFFFFFFF.toInt(),
            bottom = 0xFFF1F4FA.toInt(),
            // Pressed, it turns over *and* takes a tint of the ground, which the cream
            // version did not have to do: cream against beige had a whole step of value to
            // move through, and white against white has none. The blue is the ground's own,
            // so a pressed key reads as one that has sunk into the page rather than as one
            // that has changed colour.
            pressedTop = 0xFFCFDAF1.toInt(),
            pressedBottom = 0xFFF4F7FC.toInt(),
            pressedBorder = 0xFF003C74.toInt(),
            gloss = false
        )

        /**
         * Windows Vista's, in Aero.
         *
         * Again the launcher's sampled values - `WinPalette.VISTA` - with one addition the
         * two-stop version in that file leaves out: the crisp line across the middle. An Aero
         * button is two washes rather than one, a bright half over a duller half meeting at a
         * hard edge, and that line is the single most recognisable thing about it. The ends
         * of both halves are still the sampled pair.
         *
         * The accent is `#3399FF`, which is Vista's `COLOR_HIGHLIGHT` - a menu item under the
         * pointer, a selected line of text - rather than the pale wash Explorer fills a
         * chosen row with. The accent on this keyboard is a fill with white written on top of
         * it, and the pale wash would leave the suggestion under your finger unreadable.
         */
        val AERO: KeyChrome = Rounded(
            palette = KeyboardPalette(
                accent = 0xFF3399FF.toInt(),
                background = 0xFFF0F0F0.toInt(),
                foreground = Color.BLACK,
                foregroundSubtle = 0xFF808080.toInt(),
                inactive = Color.argb(51, 0, 0, 0),
                chrome = 0xFFF0F0F0.toInt(),
                onChrome = Color.BLACK,
                isDark = false
            ),
            ink = Color.BLACK,
            subtleInk = 0xFF808080.toInt(),
            border = 0xFF707070.toInt(),
            top = 0xFFFAFAFA.toInt(),
            bottom = 0xFFE0E0E0.toInt(),
            midTop = 0xFFF2F2F2.toInt(),
            midBottom = 0xFFE6E6E6.toInt(),
            pressedTop = 0xFFDFF2FC.toInt(),
            pressedBottom = 0xFF9DCBE8.toInt(),
            pressedMidTop = 0xFFC2E4F6.toInt(),
            pressedMidBottom = 0xFFACD9F2.toInt(),
            pressedBorder = 0xFF3D7BAD.toInt(),
            gloss = true
        )
    }
}

/**
 * Windows 98's key: the beige, and the bevel that makes it a button.
 *
 * Nothing here is a shape. The face is a rectangle and the edge is eight rectangles, which is
 * exactly how the shell drew it, and it is why this one needs no anti-aliasing and no radius.
 */
private object Bevelled : KeyChrome {

    override val palette: KeyboardPalette get() = Win98.PALETTE

    override val ink: Int get() = Win98.INK
    override val subtleInk: Int get() = Win98.SHADOW

    private val fill = Paint()
    private val edge = Paint()

    override fun shove(density: Float): Float = Win98.band(density)

    override fun key(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        density: Float,
        pressed: Boolean,
        default: Boolean
    ) {
        fill.shader = null
        fill.color = Win98.FACE
        canvas.drawRect(left, top, right, bottom, fill)

        var l = left; var t = top; var r = right; var b = bottom
        val band = Win98.band(density)
        // The frame Windows put round the dialog's default button: drawn outside the bevel,
        // with the bevel inset behind it, exactly as the shell did it - so a default button
        // is a button with a frame rather than a slightly different button.
        if (default) {
            edge.color = Win98.DARK_SHADOW
            canvas.drawRect(l, t, r, t + band, edge)
            canvas.drawRect(l, b - band, r, b, edge)
            canvas.drawRect(l, t, l + band, b, edge)
            canvas.drawRect(r - band, t, r, b, edge)
            l += band; t += band; r -= band; b -= band
        }
        Win98.bevel(canvas, l, t, r, b, edge, band, sunken = pressed)
    }
}

/**
 * XP's and Vista's key: a rounded rectangle, a wash down it, and a line around it.
 *
 * One class for both, because they are the same button drawn in different colours - which is
 * what the two shells actually shipped. The only structural difference is [gloss], and even
 * that is a question about how many stops the wash has rather than about a different drawing.
 *
 * [midTop] and [midBottom] are the pair either side of the hard line across the middle of an
 * Aero button. Left null - which is XP - the wash is the plain two-stop run from [top] to
 * [bottom] that Luna drew.
 */
private class Rounded(
    override val palette: KeyboardPalette,
    @param:ColorInt override val ink: Int,
    @param:ColorInt override val subtleInk: Int,
    @param:ColorInt private val border: Int,
    @param:ColorInt private val top: Int,
    @param:ColorInt private val bottom: Int,
    @param:ColorInt private val pressedTop: Int,
    @param:ColorInt private val pressedBottom: Int,
    @param:ColorInt private val pressedBorder: Int,
    private val gloss: Boolean,
    @param:ColorInt private val midTop: Int = 0,
    @param:ColorInt private val midBottom: Int = 0,
    @param:ColorInt private val pressedMidTop: Int = 0,
    @param:ColorInt private val pressedMidBottom: Int = 0
) : KeyChrome {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    /**
     * The last wash built, and what it was built for.
     *
     * A `LinearGradient` is tied to the two heights it runs between, so one has to be made
     * whenever a key of a new size is drawn - and a row of alternates repaints every one of
     * its cells on every touch move, which at ten cells and sixty hertz is six hundred of
     * these a second if nothing remembers. One entry is enough: within a single pass every
     * key being drawn is the same height, so the second one onwards is a hit.
     */
    private var cachedTop = Float.NaN
    private var cachedBottom = Float.NaN
    private var cachedPressed = false
    private var cached: LinearGradient? = null

    override fun key(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        density: Float,
        pressed: Boolean,
        default: Boolean
    ) {
        val line = maxOf(1f, BORDER_DP * density)
        val radius = RADIUS_DP * density

        fill.shader = wash(top, bottom, pressed)
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null

        // Inset by half the stroke, because a stroke straddles the path it is drawn on and
        // half of one hung outside the key would be half a line of somebody else's gutter.
        rect.inset(line / 2f, line / 2f)
        edge.strokeWidth = line
        edge.color = if (pressed) pressedBorder else border
        canvas.drawRoundRect(rect, radius, radius, edge)

        // Switched on, drawn as the dialog's default button: a second line in the shell's own
        // selection colour, just inside the first. Both of these shells marked the default
        // that way - XP with a navy ring, Vista with a blue glow - and a ring is the half of
        // that which survives being a fifth of an inch across.
        if (default) {
            rect.inset(line, line)
            edge.color = palette.accent
            canvas.drawRoundRect(rect, radius, radius, edge)
        }
    }

    /** The wash down the key, remembered between calls. See [cached]. */
    private fun wash(top: Float, bottom: Float, pressed: Boolean): LinearGradient {
        val held = cached
        if (held != null && top == cachedTop && bottom == cachedBottom && pressed == cachedPressed) {
            return held
        }
        val made = if (gloss) {
            LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(
                    if (pressed) pressedTop else this.top,
                    if (pressed) pressedMidTop else midTop,
                    if (pressed) pressedMidBottom else midBottom,
                    if (pressed) pressedBottom else this.bottom
                ),
                // The two middle stops sit a thousandth apart, which is what makes the join
                // between the bright half and the dull half a line rather than a fade.
                floatArrayOf(0f, 0.5f, 0.501f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, top, 0f, bottom,
                if (pressed) pressedTop else this.top,
                if (pressed) pressedBottom else this.bottom,
                Shader.TileMode.CLAMP
            )
        }
        cachedTop = top
        cachedBottom = bottom
        cachedPressed = pressed
        cached = made
        return made
    }

    private companion object {
        /** How round the corners are. Three, which is what both shells' buttons measure. */
        const val RADIUS_DP = 3f

        /**
         * The line around a button, in dp rather than in the one pixel the shells drew.
         *
         * The same argument as [Win98.band]: copying the number rather than the size gives a
         * hairline nobody can see on a screen four times the density. Thinner than the
         * bevel's band because this is one line and that is two.
         */
        const val BORDER_DP = 1.2f
    }
}

/**
 * The beige, and the two bands of it that make a button.
 *
 * Windows 95 through 2000 drew every button, every toolbar and every dialog out of one
 * colour and four shades of it, and the shades are not decoration: they are a light source in
 * the top-left corner of the screen, applied consistently enough that a rectangle with white
 * along its top reads as sticking out and the same rectangle with black along its top reads
 * as pushed in. That is the entire mechanism, and it is why a pressed key here does not need
 * to change colour to say it has been pressed.
 *
 * The *relationships* are the system's - `3DLIGHT` halfway between the face and white,
 * `3DSHADOW` two thirds of the face, `3DDKSHADOW` as good as black - and they are what the
 * eye is actually reading, so they are kept exactly. The face they are worked out from is
 * not `#C0C0C0`: it is the warm grey of the plastic those machines were moulded in, which is
 * what people picture when they picture one, and it is the colour this theme was asked for
 * in. Every band is derived from it rather than copied from the system palette, because a
 * neutral grey highlight over a warm face is the one thing that would make it look like a
 * mistake instead of a choice.
 */
internal object Win98 {

    /**
     * The face of every key, and the ground they sit on.
     *
     * `COLOR_3DFACE`'s part, in the beige of the case rather than the grey of the screen.
     */
    @ColorInt
    const val FACE = 0xFFD3CEC7.toInt()

    /**
     * `COLOR_3DHIGHLIGHT`: the outer band along the lit edges of a raised button.
     *
     * White, and not tinted with the rest of them. It is not a shade of the face, it is the
     * light falling on it, and Windows used the same white whatever the face was set to.
     */
    @ColorInt
    const val HIGHLIGHT = 0xFFFFFFFF.toInt()

    /** `COLOR_3DLIGHT`: the inner band, halfway from the face to the highlight. */
    @ColorInt
    const val LIGHT = 0xFFE9E7E3.toInt()

    /** `COLOR_3DSHADOW`: the inner band along the shaded edges, and a dimmed label. */
    @ColorInt
    const val SHADOW = 0xFF8D8985.toInt()

    /** `COLOR_3DDKSHADOW`: the outer band, and the frame around a default button. */
    @ColorInt
    const val DARK_SHADOW = 0xFF0A0A0A.toInt()

    /**
     * `COLOR_HIGHLIGHT`. What Windows selected text, menu items and list rows in.
     *
     * Left at the navy it always was. It is the one colour here that was never a shade of the
     * face - it had to stand against whatever the face happened to be - and against this
     * beige it stands exactly as well as it did against the grey.
     */
    @ColorInt
    const val SELECTION = 0xFF000080.toInt()

    /** What is written on a key. Black, as everything in that shell was. */
    @ColorInt
    const val INK = 0xFF000000.toInt()

    /**
     * The whole keyboard's colours, as a [KeyboardPalette].
     *
     * Everything on this keyboard already takes its colours from a palette, so a theme that
     * hands over a different one repaints the suggestion bar, the emoji panel, the clipboard
     * list, the alternates row and the key preview without any of them being told that themes
     * exist. That is the reason this is expressed as a palette at all rather than as a set of
     * constants the keys read directly.
     *
     * The accent is the selection navy, which is the right answer rather than a convenient
     * one: the accent is used here for the word under a finger on the suggestion bar, the
     * chosen cell of the alternates row and the emoji under a thumb, and every one of those
     * is the thing Windows would have drawn in `COLOR_HIGHLIGHT` with white on top of it.
     *
     * `isDark` is false, which is what tells the system to draw its own chevron and globe in
     * the strip under the keyboard in black rather than white - see
     * `WinBoardService.paintNavigationBar`. On a beige this pale, white ones would be
     * gone.
     */
    val PALETTE = KeyboardPalette(
        accent = SELECTION,
        background = FACE,
        foreground = INK,
        // Not the palette's usual translucent black: on this face a dimmed label is
        // `COLOR_3DSHADOW`, which is the one Windows used for exactly that.
        foregroundSubtle = SHADOW,
        inactive = Color.argb(51, 0, 0, 0),
        chrome = FACE,
        onChrome = INK,
        isDark = false
    )

    /**
     * How thick one band of a bevel is.
     *
     * Windows drew it in single pixels, on a screen whose pixels were a third of a millimetre
     * across. Copying the number rather than the size would produce a hairline nobody could
     * see on a phone, so it is copied in dp and comes out at about the size it looked - and
     * floored at a whole pixel, because a bevel that lands on half a pixel is a smear rather
     * than an edge.
     */
    fun band(density: Float): Float = maxOf(1f, BAND_DP * density)

    /**
     * Draws a button's edge inside the given rectangle: raised, or pressed in.
     *
     * Two bands a side, four colours, and [sunken] swaps the pairs over. That swap is the
     * whole of what a press looks like in this theme - the face does not change colour, and
     * the light simply moves to the other corner of the button.
     *
     * The caller has already filled the face; this only draws the edge over it. [paint] is
     * borrowed and its colour is left wherever the last band put it, so it must not be the
     * paint holding the face's fill.
     */
    fun bevel(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        paint: Paint,
        band: Float,
        sunken: Boolean
    ) {
        edge(
            canvas, left, top, right, bottom, paint, band,
            lit = if (sunken) DARK_SHADOW else HIGHLIGHT,
            shaded = if (sunken) HIGHLIGHT else DARK_SHADOW
        )
        edge(
            canvas, left + band, top + band, right - band, bottom - band, paint, band,
            lit = if (sunken) SHADOW else LIGHT,
            shaded = if (sunken) LIGHT else SHADOW
        )
    }

    /**
     * One band: [lit] along the top and left, [shaded] along the bottom and right.
     *
     * The shaded pair is drawn second so that it owns the two corners where the bands meet.
     * Either choice leaves a corner belonging to one side or the other - Windows mitred them,
     * which at a single pixel was a decision about one pixel - and the shaded corner is the
     * one that reads correctly, because the eye takes the darker mark as the edge furthest
     * from the light.
     */
    private fun edge(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        paint: Paint,
        band: Float,
        @ColorInt lit: Int,
        @ColorInt shaded: Int
    ) {
        if (right - left <= band * 2 || bottom - top <= band * 2) return
        paint.color = lit
        canvas.drawRect(left, top, right, top + band, paint)
        canvas.drawRect(left, top, left + band, bottom, paint)
        paint.color = shaded
        canvas.drawRect(left, bottom - band, right, bottom, paint)
        canvas.drawRect(right - band, top, right, bottom, paint)
    }

    /** See [band]. A pixel of 1998 was about this much of a phone. */
    private const val BAND_DP = 1.5f
}

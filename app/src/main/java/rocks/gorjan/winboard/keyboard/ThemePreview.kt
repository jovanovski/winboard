package rocks.gorjan.winboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.winboard.KeyboardPalette
import rocks.gorjan.winboard.R

/**
 * A picture of one theme, for the settings page to choose between.
 *
 * **Drawn, not photographed.** The obvious way to put a picture of a keyboard on a settings
 * page is to take five screenshots and ship them as drawables, and it is the wrong way: the
 * one thing a preview has to be is *true*, and a screenshot stops being true the first time
 * anybody touches a colour. Worse, two of these themes have no fixed appearance at all - the
 * Windows Phone one is whatever accent and background the user picked two rows further down
 * this same page, and a photograph of it would be a picture of somebody else's keyboard.
 *
 * So it draws itself out of the same parts the keyboard does. [KeyChrome.key] paints the keys
 * here exactly as it paints them there, which means a preview cannot disagree with the thing
 * it is previewing - a change to a button's wash shows up in both or in neither.
 *
 * Two rows and no more. Enough to show the ground, the shape of a key, what is written on one
 * and in what face, and - because one key is drawn held down - what a press looks like, which
 * is the single most characteristic thing any of these shells does. A third row would say
 * nothing further and take a third more of a page that is already long.
 */
@SuppressLint("ViewConstructor")
internal class ThemePreview(
    context: Context,
    private val theme: KeyboardTheme,
    /** The accent and background the user chose, which is the whole of the phone's theme. */
    chosen: KeyboardPalette
) : View(context) {

    private val palette = theme.palette(chosen)
    private val chrome = theme.chrome

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private val segoe = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
    private val tahoma = ResourcesCompat.getFont(context, R.font.tahoma)

    private var keyW = 0f
    private var keyH = 0f
    private var gap = 0f

    init {
        setBackgroundColor(ground())
        isClickable = true
    }

    /**
     * As wide as it is given, and as tall as two rows of that width come to.
     *
     * The proportions are the keyboard's own - [KeyboardView.GAP] and
     * [KeyboardView.KEY_ASPECT] - so what this shows is the shape of a real key and not an
     * approximation of one. It comes out small because the box is narrow, which is the right
     * way round: the same ten columns, less room, so narrower keys.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        keyW = KeyboardView.unitWidth(width, COLUMNS)
        keyH = keyW * KeyboardView.KEY_ASPECT
        gap = keyW * KeyboardView.GAP
        setMeasuredDimension(width, (keyH * ROWS + gap * (ROWS + 1)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (keyW <= 0f) return
        row(canvas, TOP_ROW, y = gap, indent = 0f)
        row(canvas, HOME_ROW, y = gap * 2 + keyH, indent = 0.5f)
    }

    /** One row of letters, indented by [indent] keys from the left. */
    private fun row(canvas: Canvas, letters: String, y: Float, indent: Float) {
        ink.typeface = if (theme.tahoma) tahoma else segoe
        ink.textSize = keyW * KeyView.LABEL_TEXT
        ink.textAlign = Paint.Align.CENTER

        letters.forEachIndexed { i, letter ->
            val left = gap / 2f + (indent + i) * (keyW + gap) + gap / 2f
            val right = left + keyW
            // One key held down, because what a press looks like is the most characteristic
            // thing about every one of these shells and the only one a still picture would
            // otherwise miss.
            val pressed = letters === TOP_ROW && i == PRESSED
            if (chrome == null) {
                fill.color = if (pressed) palette.accent else blend(KeyView.LETTER_FILL_ALPHA)
                canvas.drawRect(left, y, right, y + keyH, fill)
            } else {
                chrome.key(
                    canvas, left, y, right, y + keyH,
                    resources.displayMetrics.density, pressed = pressed, default = false
                )
            }

            ink.color = when {
                chrome != null -> chrome.ink
                pressed -> palette.onAccent()
                else -> palette.foreground
            }
            val text = letter.toString()
            // Centred on the ink rather than on the font's line box, for the same reason the
            // keys themselves are: Segoe carries a lot of empty space above a lowercase.
            ink.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(
                text,
                (left + right) / 2f,
                y + keyH / 2f - (bounds.top + bounds.bottom) / 2f,
                ink
            )
        }
    }

    /**
     * The ground the keys sit on, which is the keyboard's own.
     *
     * The same two answers `KeyboardView.groundColour` gives: the shell's dialog colour where
     * there is one, and a step darker than the keys where there is not.
     */
    @ColorInt
    private fun ground(): Int =
        if (theme.drawsKeys) palette.background else blend(KeyboardView.GROUND_ALPHA)

    @ColorInt
    private fun blend(alpha: Float): Int =
        ColorUtils.blendARGB(palette.background, palette.foreground, alpha)

    private companion object {

        /** Ten, like the keyboard, so a key here is the shape a key is there. */
        const val COLUMNS = 10f

        const val ROWS = 2

        /** The two rows shown. The second is held in half a key, as a real one is. */
        const val TOP_ROW = "qwertyuiop"
        const val HOME_ROW = "asdfghjkl"

        /** Which key of the top row is drawn held down. */
        const val PRESSED = 2
    }
}

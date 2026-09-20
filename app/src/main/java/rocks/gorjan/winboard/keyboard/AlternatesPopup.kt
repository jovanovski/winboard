package rocks.gorjan.winboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import rocks.gorjan.winboard.R
import rocks.gorjan.winboard.KeyboardPalette

/**
 * The row of alternates a hold opens, in a window of its own.
 *
 * It has to be its own window. The row belongs *above* the key being held - a row that
 * appeared below would be a row under the user's own thumb, which defeats the point of
 * showing it - and above the top row of keys is off the top of the input method's window.
 * Painting it into the keyboard would work for three rows out of four and be clipped away
 * for the fourth, and the fourth is the row with the numbers on it, so it is the one holds
 * are used on most.
 *
 * A [PopupWindow] with [PopupWindow.setClippingEnabled] turned off is the way out of that,
 * and is what AOSP's own keyboard does with its more-keys row. The window is deliberately
 * neither focusable nor touchable: the gesture belongs to the key that was pressed, which
 * receives the whole of it and passes the movement along, so a window that accepted touches
 * of its own would be competing for the same finger.
 */
internal class AlternatesPopup(
    context: Context,
    private var palette: KeyboardPalette
) {

    private val content = RowView(context)

    private val window = PopupWindow(content).apply {
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        setBackgroundDrawable(null)
        // The whole reason this is a window at all: without it the row is confined to the
        // keyboard's own bounds and the top row's alternates are clipped to nothing.
        isClippingEnabled = false
        animationStyle = 0
    }

    /** What the finger is currently over, or null when no row is showing. */
    val selected: Char? get() = alternateAt(chars, cell, primary)

    val isShowing: Boolean get() = chars.isNotEmpty()

    private var chars: String = ""

    /** Which cell of the drawn row, counted from its left edge - not which character. */
    private var cell = 0

    /** Which cell sits over the key. See [alternateAt]. */
    private var primary = 0

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        content.invalidate()
    }

    /**
     * What the keyboard is made of. See [KeyboardTheme].
     *
     * The row floats over the keys and is the same thing they are - a cell of it is a key you
     * have not lifted your finger onto yet - so under a theme that draws its own keys the
     * cells are that shell's buttons, like the ones underneath them. Flat grey cells over
     * raised or outlined ones would read as the row having come from somewhere else.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            content.invalidate()
        }

    /**
     * Shows [alternates] above [key].
     *
     * @param anchor the keyboard, whose position in the window is what the row is placed
     *   relative to. The box arithmetic is [alternatesBox]'s and is in the keyboard's own
     *   coordinates, so it only needs shifting by where the keyboard sits.
     */
    fun show(anchor: View, key: KeyView, alternates: String, keyW: Float, keyH: Float, gap: Float) {
        if (alternates.isEmpty() || keyW <= 0f) return
        chars = alternates

        val box = alternatesBox(
            // The key as painted, which is inset from its view's bounds by half a gutter.
            faceLeft = key.left + gap / 2f,
            faceTop = key.top + gap / 2f,
            faceRight = key.right - gap / 2f,
            count = alternates.length,
            keyW = keyW,
            keyH = keyH,
            gap = gap,
            viewWidth = anchor.width.toFloat(),
            heightFraction = HEIGHT_FRACTION
        )
        primary = box.primary
        cell = box.primary
        content.configure(chars, box.primary, keyW, gap)
        left = box.left

        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        val x = at[0] + box.left.toInt()
        val y = at[1] + box.top.toInt()

        if (window.isShowing) {
            window.update(x, y, box.width.toInt(), box.height.toInt())
        } else {
            window.width = box.width.toInt()
            window.height = box.height.toInt()
            window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        // The cell over the key, which is the one the finger is already on. Set after the
        // window is up so the first paint has it right.
        content.select(box.primary)
    }

    /** Where the row's left edge sits in the keyboard's coordinates, for hit testing. */
    var left: Float = 0f
        private set

    fun moveTo(x: Float, keyW: Float, gap: Float) {
        if (chars.isEmpty()) return
        val next = alternateCellAt(x, left, chars.length, keyW, gap)
        if (next != cell) {
            cell = next
            content.select(next)
        }
    }

    fun dismiss() {
        chars = ""
        cell = 0
        primary = 0
        if (window.isShowing) window.dismiss()
    }

    /** The row itself: flat cells, the chosen one in the accent. */
    @SuppressLint("ViewConstructor")
    private inner class RowView(context: Context) : View(context) {

        private val face = Paint()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()
        private val font = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
        private val classicFont = ResourcesCompat.getFont(context, R.font.tahoma)

        private var chars: String = ""
        private var primary = 0
        private var chosen = 0
        private var cellW = 0f
        private var gutter = 0f

        fun configure(chars: String, primary: Int, cellW: Float, gutter: Float) {
            this.chars = chars
            this.primary = primary
            this.cellW = cellW
            this.gutter = gutter
            ink.textSize = cellW * TEXT_FRACTION
            requestLayout()
            invalidate()
        }

        fun select(index: Int) {
            if (index == chosen) return
            chosen = index
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (chars.isEmpty()) return
            ink.typeface = if (theme.tahoma) classicFont else font
            ink.textAlign = Paint.Align.CENTER
            val chrome = theme.chrome
            val density = resources.displayMetrics.density
            val pitch = cellW + gutter
            for (i in chars.indices) {
                val x = i * pitch
                val on = i == chosen
                // The chosen cell keeps the accent under every theme, which under the Windows
                // ones is the colour that shell selected everything in - a list row, a menu
                // item, a run of text. The rest are that shell's buttons, or the flat float
                // the phone's theme has always drawn. See [fill].
                if (on) {
                    face.color = palette.accent
                    canvas.drawRect(x, 0f, x + cellW, height.toFloat(), face)
                } else if (chrome != null) {
                    chrome.key(
                        canvas, x, 0f, x + cellW, height.toFloat(),
                        density, pressed = false, default = false
                    )
                } else {
                    face.color = fill()
                    canvas.drawRect(x, 0f, x + cellW, height.toFloat(), face)
                }

                val ch = (alternateAt(chars, i, primary) ?: continue).toString()
                ink.color = if (on) palette.onAccent() else (chrome?.ink ?: palette.foreground)
                ink.getTextBounds(ch, 0, ch.length, bounds)
                canvas.drawText(
                    ch,
                    x + cellW / 2f,
                    height / 2f - (bounds.top + bounds.bottom) / 2f,
                    ink
                )
            }
        }

        /**
         * What an unchosen cell is painted.
         *
         * A step lighter than a function key, so the row reads as sitting on top of the
         * keyboard rather than as part of it. It is the only thing here meant to look like it
         * is floating, and on a field of `#333333` and `#4D4D4D` the way to say so is to be
         * neither of them.
         */
        private fun fill(): Int =
            ColorUtils.blendARGB(palette.background, palette.foreground, FILL_ALPHA)
    }

    private companion object {
        const val HEIGHT_FRACTION = 0.82f
        const val TEXT_FRACTION = 0.57f
        const val FILL_ALPHA = 0.42f
    }
}

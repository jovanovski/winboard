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
 * The languages a hold on the space bar opens, in a window of its own.
 *
 * The phone put the list here and it is the obvious place for it: the space bar is the one
 * key that already says which language you are typing in, so it is the one to ask for another.
 * Android's own answer - the globe in the navigation bar - is still there and still works;
 * this is the answer for the hand that is already on the keyboard, and for the times the
 * navigation bar is a gesture handle with nothing on it to press.
 *
 * A window rather than something painted into the keyboard, for [AlternatesPopup]'s reason
 * and then some: a list of half a dozen names is taller than the keyboard has above the
 * bottom row, so most of it is outside the input method's window altogether. Like that one it
 * is neither focusable nor touchable - the gesture belongs to the space bar, which was pressed
 * and which passes the movement along.
 */
internal class LanguagesPopup(
    context: Context,
    private var palette: KeyboardPalette
) {

    private val content = ListPane(context)

    private val window = PopupWindow(content).apply {
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        setBackgroundDrawable(null)
        // The whole reason this is a window: the list stands well clear of the keyboard, and
        // painted into it would be a list clipped to its bottom row.
        isClippingEnabled = false
        animationStyle = 0
    }

    val isShowing: Boolean get() = names.isNotEmpty()

    /**
     * Which language the finger is over.
     *
     * The one already in use until the finger is moved up onto the list, so a hold and a
     * straight lift is not a language change. See [languageAt].
     */
    val selected: Int get() = cell

    private var names: List<String> = emptyList()

    /** Which cell is lit, counted through the list rather than across the drawn columns. */
    private var cell = 0

    /** The language in use when the list opened, which is where the finger starts. */
    private var current = 0

    private var box = LanguageBox(0f, 0f, 0f, 0f, 1, 1, 0f)
    private var cellH = 0f
    private var gutter = 0f

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        content.invalidate()
    }

    /**
     * What the keyboard is made of. See [KeyboardTheme].
     *
     * The cells are the keys' own button under the Windows themes, the way the alternates
     * row's are: the list floats over the keys and is made of what they are made of.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            content.invalidate()
        }

    /**
     * Shows [names] above [key], with [current] the one in use.
     *
     * @param anchor the keyboard, whose position is what the list is placed relative to.
     *   [languagesBox] works in the keyboard's own coordinates, so the result only needs
     *   shifting by where the keyboard sits.
     * @param headroom how much screen there is above the key. The list wraps into columns
     *   rather than climbing off the top of the display - see [languageGrid].
     */
    fun show(
        anchor: View,
        key: KeyView,
        names: List<String>,
        current: Int,
        keyH: Float,
        gap: Float,
        headroom: Float
    ) {
        if (names.size < 2 || keyH <= 0f) return
        this.names = names
        this.current = current.coerceIn(0, names.size - 1)
        this.cell = this.current
        cellH = keyH * HEIGHT_FRACTION
        gutter = gap

        box = languagesBox(
            // The key as painted. The space bar is the one key whose face is not simply its
            // view inset by half a gutter: it reaches up into the row above to catch the
            // thumbs that aim low, and that reach is touchable rather than drawn - a list
            // placed against it would float a gutter clear of nothing. See [Key.overhangTop].
            faceLeft = key.left + gap / 2f,
            faceTop = key.top + gap / 2f + key.overhang,
            faceRight = key.right - gap / 2f,
            count = names.size,
            cellW = content.widthFor(names, cellH),
            cellH = cellH,
            gap = gap,
            viewWidth = anchor.width.toFloat(),
            headroom = headroom
        )
        content.configure(names, box, cellH, gap)

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
        content.select(cell)
    }

    /**
     * The finger has moved, in the keyboard's own coordinates.
     *
     * @return true when the lit cell changed, which is what the caller ticks on: the list is
     *   above the finger rather than under it, but the hand doing the sliding is the one that
     *   wants to know it has crossed into the next name.
     */
    fun moveTo(x: Float, y: Float): Boolean {
        if (names.isEmpty()) return false
        val next = languageAt(x, y, box, names.size, cellH, gutter, current)
        if (next == cell) return false
        cell = next
        content.select(next)
        return true
    }

    fun dismiss() {
        names = emptyList()
        cell = 0
        current = 0
        if (window.isShowing) window.dismiss()
    }

    /** The list itself: one cell per language, the chosen one in the accent. */
    @SuppressLint("ViewConstructor")
    private inner class ListPane(context: Context) : View(context) {

        private val face = Paint()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()
        private val font = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
        private val classicFont = ResourcesCompat.getFont(context, R.font.tahoma)

        private var names: List<String> = emptyList()
        private var box = LanguageBox(0f, 0f, 0f, 0f, 1, 1, 0f)
        private var chosen = 0
        private var cellH = 0f
        private var gutter = 0f

        /**
         * How wide a cell has to be to hold the longest of [names].
         *
         * Asked before the list is placed, because the width of a cell is the one thing here
         * that is not arithmetic - it is a font measurement, and the font is the one the keys
         * are set in. A name is held off either end by half a cell's height, which is the
         * padding a key's own label would have at that size.
         */
        fun widthFor(names: List<String>, cellH: Float): Float {
            ink.typeface = if (theme.tahoma) classicFont else font
            ink.textSize = cellH * TEXT_FRACTION
            val widest = names.maxOfOrNull { ink.measureText(it) } ?: 0f
            return widest + cellH * PADDING
        }

        fun configure(names: List<String>, box: LanguageBox, cellH: Float, gap: Float) {
            this.names = names
            this.box = box
            this.cellH = cellH
            this.gutter = gap
            ink.textSize = textSizeFor(names, box.cellWidth, cellH)
            requestLayout()
            invalidate()
        }

        /**
         * How big the names are set.
         *
         * A key's own proportions, unless the list had to be narrowed to fit the keyboard -
         * two columns of names on a landscape screen - in which case the names are brought
         * down to what the cell can hold rather than being drawn over its edges. Shrinking
         * a name is a name you can still read; clipping one is a language you cannot tell
         * from its neighbour.
         */
        private fun textSizeFor(names: List<String>, cellW: Float, cellH: Float): Float {
            val natural = cellH * TEXT_FRACTION
            ink.typeface = if (theme.tahoma) classicFont else font
            ink.textSize = natural
            val widest = names.maxOfOrNull { ink.measureText(it) } ?: 0f
            val room = cellW - cellH * PADDING
            if (widest <= room || widest <= 0f || room <= 0f) return natural
            return natural * (room / widest)
        }

        fun select(index: Int) {
            if (index == chosen) return
            chosen = index
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (names.isEmpty()) return
            ink.typeface = if (theme.tahoma) classicFont else font
            ink.textAlign = Paint.Align.CENTER
            val chrome = theme.chrome
            val density = resources.displayMetrics.density
            val cellW = box.cellWidth
            for (index in names.indices) {
                // Down a column and on to the next, which is the order [languageAt] reads
                // the finger's position in and the order a list is written in.
                val left = (index / box.rows) * (cellW + gutter)
                val top = (index % box.rows) * (cellH + gutter)

                val on = index == chosen
                if (on) {
                    face.color = palette.accent
                    canvas.drawRect(left, top, left + cellW, top + cellH, face)
                } else if (chrome != null) {
                    chrome.key(
                        canvas, left, top, left + cellW, top + cellH,
                        density, pressed = false, default = false
                    )
                } else {
                    face.color = fill()
                    canvas.drawRect(left, top, left + cellW, top + cellH, face)
                }

                val name = names[index]
                ink.color = if (on) palette.onAccent() else (chrome?.ink ?: palette.foreground)
                ink.getTextBounds(name, 0, name.length, bounds)
                canvas.drawText(
                    name,
                    left + cellW / 2f,
                    top + cellH / 2f - (bounds.top + bounds.bottom) / 2f,
                    ink
                )
            }
        }

        /** What an unchosen cell is painted. See [AlternatesPopup]'s own `fill` for why. */
        private fun fill(): Int =
            ColorUtils.blendARGB(palette.background, palette.foreground, FILL_ALPHA)
    }

    private companion object {
        /**
         * How tall a cell is against a key.
         *
         * Shorter than the alternates row's cells, and deliberately so: those are keys you
         * are about to press and this is a list you are reading, so the height it needs is
         * the height of a line of it. It also decides how many fit above the keyboard, which
         * on a phone held sideways is the difference between one column and two.
         */
        const val HEIGHT_FRACTION = 0.72f

        /**
         * How big a name is set against its cell, and how much room it is held off the ends.
         *
         * The size the space bar writes the same name at, which is what these cells are
         * offering to change: a list that said `Македонски` larger or smaller than the key
         * does would read as naming something else.
         */
        const val TEXT_FRACTION = 0.5f
        const val PADDING = 0.8f

        const val FILL_ALPHA = 0.42f
    }
}

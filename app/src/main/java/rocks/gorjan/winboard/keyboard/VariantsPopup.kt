package rocks.gorjan.winboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import androidx.core.graphics.ColorUtils
import rocks.gorjan.winboard.KeyboardPalette

/**
 * The skin tones a hold on an emoji opens, in a window of its own.
 *
 * [AlternatesPopup] for emoji, and its own class rather than a second mode of that one
 * because almost nothing is shared beyond the idea: the cells here are emoji rather than
 * characters, they are the size of a grid cell rather than of a key, and there can be
 * twenty-six of them, which is a block rather than a row. What *is* shared is the reason it
 * is a window at all - the block belongs above the cell being held, and above the top row of
 * the grid is outside the input method's window altogether - and the fact that it neither
 * takes focus nor accepts touches: the gesture belongs to the grid, which received the hold
 * and passes the movement along.
 */
internal class VariantsPopup(
    context: Context,
    private var palette: KeyboardPalette
) {

    private val content = BlockView(context)

    private val window = PopupWindow(content).apply {
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        setBackgroundDrawable(null)
        // The whole reason this is a window: without it the block is confined to the panel
        // and a hold on the grid's top row would open something clipped to nothing.
        isClippingEnabled = false
        animationStyle = 0
    }

    val isShowing: Boolean get() = variants.isNotEmpty()

    /** What the finger is currently over, or null when no block is showing. */
    val selected: Emoji?
        get() = variants.getOrNull(variantAt(variants.size, cell, box))

    private var variants: List<Emoji> = emptyList()

    /** Which drawn cell of the block, counted from its bottom left - not which variant. */
    private var cell = 0

    private var box = VariantsBox(0f, 0f, 0f, 0f, 1, 1, 0)
    private var cellSize = 0f
    private var gutter = 0f

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        content.invalidate()
    }

    /**
     * Shows [variants] above the grid cell whose box is [cellLeft], [cellTop].
     *
     * @param anchor the grid, whose position in the window is what the block is placed
     *   relative to. [variantsBox] works in the grid's own coordinates, so the result only
     *   needs shifting by where the grid sits.
     * @param held the glyph that cell was drawing, which is the one the block opens on.
     *   Usually the first of [variants], and in the recents not necessarily - see
     *   [variantCellOf].
     */
    fun show(
        anchor: View,
        cellLeft: Float,
        cellTop: Float,
        variants: List<Emoji>,
        held: String,
        cellSize: Float,
        gap: Float
    ) {
        if (variants.size < 2 || cellSize <= 0f) return
        this.variants = variants
        this.cellSize = cellSize
        this.gutter = gap

        box = variantsBox(
            cellLeft = cellLeft,
            cellTop = cellTop,
            count = variants.size,
            cell = cellSize,
            gap = gap,
            viewWidth = anchor.width.toFloat()
        )
        // The cell holding the emoji the grid was already drawing, which everywhere but the
        // recents is the block's own primary - the cell over the finger. Either way, a hold
        // and a straight lift gives back exactly what a tap would have.
        cell = variantCellOf(variants.indexOfFirst { it.glyph == held }.coerceAtLeast(0), box)
        content.configure(variants, box, cellSize, gap)

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

    /** The finger has moved, in the grid's own coordinates. */
    fun moveTo(x: Float, y: Float) {
        if (variants.isEmpty()) return
        val next = variantCellAt(x, y, box, cellSize, gutter)
        if (next != cell) {
            cell = next
            content.select(next)
        }
    }

    fun dismiss() {
        variants = emptyList()
        cell = 0
        if (window.isShowing) window.dismiss()
    }

    /** The block itself: flat cells, the chosen one in the accent. */
    @SuppressLint("ViewConstructor")
    private inner class BlockView(context: Context) : View(context) {

        private val face = Paint()
        // Null typeface throughout, for the reason EmojiPanel's grid gives: emoji come from
        // the system's own colour font through Android's glyph fallback, and asking for
        // Segoe here could only find a face with no colour table for these code points.
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()

        private var variants: List<Emoji> = emptyList()
        private var box = VariantsBox(0f, 0f, 0f, 0f, 1, 1, 0)
        private var chosen = 0
        private var cellSize = 0f
        private var gutter = 0f

        fun configure(variants: List<Emoji>, box: VariantsBox, cellSize: Float, gap: Float) {
            this.variants = variants
            this.box = box
            this.cellSize = cellSize
            this.gutter = gap
            ink.textSize = cellSize * TEXT_FRACTION
            requestLayout()
            invalidate()
        }

        fun select(index: Int) {
            if (index == chosen) return
            chosen = index
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (variants.isEmpty()) return
            ink.typeface = null
            ink.textAlign = Paint.Align.CENTER
            val pitch = cellSize + gutter
            for (drawn in 0 until box.rows * box.columns) {
                val index = variantAt(variants.size, drawn, box)
                if (index < 0) continue
                // Rows from the bottom - see [VariantsBox] - so the first shades are the
                // ones nearest the finger that opened the block.
                val left = (drawn % box.columns) * pitch
                val top = (box.rows - 1 - drawn / box.columns) * pitch

                val on = drawn == chosen
                face.color = if (on) palette.accent else fill()
                canvas.drawRect(left, top, left + cellSize, top + cellSize, face)

                val glyph = variants[index].glyph
                ink.getTextBounds(glyph, 0, glyph.length, bounds)
                canvas.drawText(
                    glyph,
                    left + cellSize / 2f,
                    top + cellSize / 2f - (bounds.top + bounds.bottom) / 2f,
                    ink
                )
            }
        }

        /** What an unchosen cell is painted. See [AlternatesPopup]'s own `fill` for why. */
        private fun fill(): Int =
            ColorUtils.blendARGB(palette.background, palette.foreground, FILL_ALPHA)
    }

    private companion object {
        /** The same share of a cell the grid draws its emoji at, so nothing changes size. */
        const val TEXT_FRACTION = 0.56f
        const val FILL_ALPHA = 0.42f
    }
}

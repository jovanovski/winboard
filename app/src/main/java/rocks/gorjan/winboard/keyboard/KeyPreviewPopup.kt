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
import rocks.gorjan.winboard.R
import rocks.gorjan.winboard.KeyboardPalette

/**
 * The letter that appears above a key while it is held down.
 *
 * A thumb covers the key it is pressing, so on a phone the one thing you cannot see while
 * typing is what you have just typed. Every keyboard's answer is the same and has been since
 * the first one: lift the character clear of the finger for as long as the finger is there.
 * Windows Phone lit the key itself in the accent, which is a good answer on a keyboard held
 * in two hands and a thin one under a thumb - so the key still lights, and this is the part
 * somebody can switch on when that is not enough.
 *
 * It has to be its own window, for the same reason the alternates row does: the flag belongs
 * *above* the key, and above the top row is off the top of the input method's window
 * entirely. Painting it into the keyboard would work for three rows out of four and be
 * clipped away for the fourth. A [PopupWindow] with [PopupWindow.setClippingEnabled] turned
 * off is the way out, and is what AOSP's own keyboard does with its key previews.
 *
 * Painted the accent, like the key it came off: a flag in one colour above a key in another
 * would read as two things happening rather than as one key saying what it is. That also puts
 * it in step with [AlternatesPopup], whose chosen cell is the accent for the same reason -
 * those are the only two things on this keyboard that float above it, and two floating
 * surfaces that behaved differently would read as two different mechanisms.
 *
 * **The window is kept up between keystrokes.** Dismissing on release and showing again on
 * the next press is a window torn down and built twenty times in ten seconds, which is
 * exactly the wrong work to be doing on the touch path; instead the flag is moved with
 * [PopupWindow.update] while typing continues, and a short delay after the last key comes up
 * puts it away. That delay is also what stops the flag flickering between two letters typed a
 * few milliseconds apart.
 */
internal class KeyPreviewPopup(
    context: Context,
    private var palette: KeyboardPalette
) {

    private val content = FlagView(context)

    private val window = PopupWindow(content).apply {
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        setBackgroundDrawable(null)
        // The whole reason this is a window at all: without it the flag is confined to the
        // keyboard's own bounds and the top row's previews are clipped to nothing.
        isClippingEnabled = false
        animationStyle = 0
    }

    /** Put away shortly after the last key comes up. See the note on the class. */
    private val hide = Runnable { dismiss() }

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        content.invalidate()
    }

    /**
     * What the keyboard is made of. See [KeyboardTheme].
     *
     * The flag is the key, lifted clear of the thumb, so it is made of whatever the key is:
     * the accent under the phone's theme, and that shell's own push button under any of the
     * others. None of those shells had anything quite like this - a keyboard under a thumb
     * was not a problem they had - so the shape is borrowed from the thing it is standing in
     * for, which is the key.
     */
    var theme: KeyboardTheme = KeyboardTheme.PHONE
        set(value) {
            if (field == value) return
            field = value
            content.invalidate()
        }

    /**
     * Shows [text] above [key].
     *
     * @param anchor the keyboard, whose position in the window is what the flag is placed
     *   relative to - the key's bounds are in the keyboard's own coordinates.
     */
    fun show(anchor: View, key: KeyView, text: String, keyH: Float, gap: Float) {
        if (text.isEmpty() || keyH <= 0f) return
        content.removeCallbacks(hide)

        // The key as painted, which is inset from its view's bounds by half a gutter, and
        // starts below whatever the key overhangs upwards for the sake of its touch target.
        val faceLeft = key.left + gap / 2f
        val faceRight = key.right - gap / 2f
        val faceTop = key.top + gap / 2f + key.overhang

        val width = faceRight - faceLeft
        val height = keyH * HEIGHT_FRACTION
        // Directly above the key with one gutter between, so the flag reads as belonging to
        // the key rather than floating loose over the row above it.
        val top = faceTop - gap - height

        content.configure(text, height)

        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        val x = at[0] + faceLeft.toInt()
        val y = at[1] + top.toInt()

        if (window.isShowing) {
            window.update(x, y, width.toInt(), height.toInt())
        } else {
            window.width = width.toInt()
            window.height = height.toInt()
            try {
                window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            } catch (e: Exception) {
                // The keyboard's window can go away between the finger landing and this
                // running. A preview that cannot be shown is not worth taking the keyboard
                // down over.
            }
        }
    }

    /** The finger has come off: put the flag away, but not so fast that fast typing blinks. */
    fun hide() {
        if (!window.isShowing) return
        content.removeCallbacks(hide)
        content.postDelayed(hide, LINGER_MS)
    }

    fun dismiss() {
        content.removeCallbacks(hide)
        if (window.isShowing) window.dismiss()
    }

    /** The flag itself: one character, centred, on a fill that floats. */
    @SuppressLint("ViewConstructor")
    private inner class FlagView(context: Context) : View(context) {

        private val face = Paint()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()
        private val font = ResourcesCompat.getFont(context, R.font.segoeui_semilight)
        private val classicFont = ResourcesCompat.getFont(context, R.font.tahoma)

        private var text = ""

        fun configure(text: String, height: Float) {
            this.text = text
            ink.textSize = height * TEXT_FRACTION
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (text.isEmpty()) return
            // The float is a key lifted clear of the finger, so it is made of whatever a key
            // is made of - a button under the Windows themes, drawn unpressed because it is
            // standing *above* the one being pressed; the accent under the phone's, which is
            // what a pressed key goes there.
            val chrome = theme.chrome
            if (chrome == null) {
                face.color = palette.accent
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
            } else {
                chrome.key(
                    canvas, 0f, 0f, width.toFloat(), height.toFloat(),
                    resources.displayMetrics.density, pressed = false, default = false
                )
            }

            ink.typeface = if (theme.tahoma) classicFont else font
            ink.textAlign = Paint.Align.CENTER
            ink.color = chrome?.ink ?: palette.onAccent()
            // Centred on the ink of this particular character rather than on the font's line,
            // which is right here for the same reason it is wrong on the suggestion bar: this
            // is one character alone in a box, not a row of words that has to sit on a line.
            ink.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(
                text,
                width / 2f,
                height / 2f - (bounds.top + bounds.bottom) / 2f,
                ink
            )
        }
    }

    private companion object {

        /** As tall as a cell of the alternates row, so the two float at the same size. */
        const val HEIGHT_FRACTION = 0.82f

        /**
         * The character, against the flag's own height rather than a key's width.
         *
         * Larger than the key it came from - which is the point of lifting it clear. Sized off
         * the flag so it stays in proportion when the key-height setting makes the keys taller
         * or shorter.
         */
        const val TEXT_FRACTION = 0.62f

        /**
         * How long the flag stays up after the finger leaves.
         *
         * Long enough that a run of quick keys moves one flag instead of flashing several,
         * short enough that a single tap does not leave a letter hanging over the keyboard.
         */
        const val LINGER_MS = 70L
    }
}

package rocks.gorjan.winboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The header every Metro page shares: a back arrow and an oversized lowercase title.
 *
 * WP8.1 pages that were pushed onto a stack carried this arrow beside their title, so the
 * way out was visible rather than something the user had to remember was on the key strip.
 */
@SuppressLint("ViewConstructor")
class MetroPageHeader(
    context: Context,
    private var palette: KeyboardPalette
) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null

    /**
     * What the title is a way to, where it is a way to anything.
     *
     * Most pages are titled after themselves and there is nothing to tap; a page titled
     * after a *person* is different - the name at the top of a conversation is the one
     * thing on it that is about them rather than about what was said, so it is where
     * somebody would reach for their card. Left unset, the title is type like any other.
     */
    var onTitle: (() -> Unit)? = null
        set(value) {
            field = value
            title.isClickable = value != null
            if (value != null) TiltEffect.apply(title)
        }

    private val backArrow = ImageView(context)
    private val title = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(18), dp(20), dp(14))

        backArrow.setImageResource(R.drawable.nav_back)
        backArrow.scaleType = ImageView.ScaleType.FIT_CENTER
        backArrow.isClickable = true
        // Sized to stand level with the title rather than tucked beside it - the arrow is
        // the way out of the page, and at handle size it read as an afterthought. The view
        // is bigger than the glyph so the touch target stays generous.
        backArrow.setPadding(dp(6), dp(6), dp(6), dp(6))
        backArrow.setOnClickListener { onBack?.invoke() }
        TiltEffect.apply(backArrow)
        addView(backArrow, LayoutParams(dp(ARROW_VIEW_DP), dp(ARROW_VIEW_DP)).apply {
            // Nudged down to sit on the title's baseline rather than its box: Segoe Light
            // carries noticeable ascender space, so centring the two by their bounds
            // leaves the arrow riding high.
            topMargin = dp(ARROW_BASELINE_NUDGE_DP)
        })

        title.typeface = ResourcesCompat.getFont(context, R.font.segoeui_light)
        title.textSize = TITLE_SP
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.includeFontPadding = false
        title.setOnClickListener { onTitle?.invoke() }
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })

        applyPalette(palette)
    }

    /** WP8.1 page titles are lowercase; it is one of the most recognisable things about it. */
    fun setTitle(text: String) {
        title.text = text.lowercase()
    }

    /**
     * A name the user typed, at the head of the page showing it - as they typed it.
     *
     * The lowercasing above is a rule about *this platform's own words*: "tabs", "notes",
     * "settings". A note called "AirCare" is not one of the platform's words, and a shell
     * that quietly renamed it to "aircare" wherever it was shown would be correcting the
     * user's typing on their behalf.
     */
    fun setName(text: String) {
        title.text = text
    }

    fun playEntrance() {
        translationY = dp(16).toFloat()
        alpha = 0f
        animate().translationY(0f).alpha(1f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun applyPalette(p: KeyboardPalette) {
        palette = p
        title.setTextColor(p.foreground)
        backArrow.imageTintList = ColorStateList.valueOf(p.foreground)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TITLE_SP = 34f

        /** Arrow view edge. Its glyph then matches the title's height once padding is off. */
        private const val ARROW_VIEW_DP = 46

        /** Drop needed to line the arrow up with the title's baseline. */
        private const val ARROW_BASELINE_NUDGE_DP = 5
    }
}

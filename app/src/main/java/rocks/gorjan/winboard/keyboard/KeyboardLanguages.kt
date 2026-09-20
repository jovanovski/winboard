package rocks.gorjan.winboard.keyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import rocks.gorjan.winboard.KeyboardSettings

/**
 * Which languages the keyboard offers, and telling Android about it.
 *
 * Two separate things have to agree and neither is derivable from the other. The setting is
 * what the user chose. Android's *enabled subtypes* are what the system will let the globe in
 * the navigation bar cycle through - and by default it decides that for itself, enabling only
 * the subtypes that match the phone's own languages. So any layout the phone itself does not
 * speak is declared, registered, and never offered: turn Greek on here, and holding the globe
 * still shows English alone.
 *
 * [applyToSystem] is the bridge. It is the only way a keyboard can have a language list of its
 * own that the rest of the system honours.
 *
 * [switchTo] is the same bridge in the other direction, and is what the hold on the space bar
 * needs: a keyboard that changed language on its own without saying so would be typing Greek
 * while the system went on believing it was English, and the next thing to ask - the settings
 * page, the globe, or this keyboard on its next showing - would put it straight back.
 */
internal object KeyboardLanguages {

    /** The layouts the user has turned on, in the order the keyboard should cycle them. */
    fun enabled(themeManager: KeyboardSettings): List<KeyboardLayout> {
        val chosen = themeManager.getLanguages()
        val kept = Layouts.ALL_LANGUAGES.filter { it.id in chosen }
        // A list that ended up empty - a stale setting naming a layout that no longer ships -
        // would leave the keyboard with no letters at all.
        return kept.ifEmpty { listOf(Layouts.EN_QWERTY) }
    }

    /** Whether [layout] is one of them. */
    fun isEnabled(themeManager: KeyboardSettings, layout: KeyboardLayout): Boolean =
        layout.id in themeManager.getLanguages()

    /**
     * Turns [layout] on or off, and tells the system.
     *
     * @return false when the change was refused, which happens only for turning off the last
     *   remaining language.
     */
    fun setEnabled(
        context: Context,
        themeManager: KeyboardSettings,
        layout: KeyboardLayout,
        on: Boolean
    ): Boolean {
        val current = themeManager.getLanguages().toMutableSet()
        if (on) {
            current.add(layout.id)
        } else {
            if (current.size <= 1) return false
            current.remove(layout.id)
        }
        themeManager.setLanguages(current)
        applyToSystem(context, themeManager)
        return true
    }

    /**
     * Makes Android's enabled-subtype list match the setting.
     *
     * Android 14 and up only - [InputMethodManager.setExplicitlyEnabledInputMethodSubtypes]
     * is where this became possible for an input method to do for itself. Below that the list
     * is the system's to decide and the user has to go through its own subtype screen, which
     * is why this fails quietly rather than reporting: there is nothing to be done about it
     * and nothing useful to say.
     */
    fun applyToSystem(context: Context, themeManager: KeyboardSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return
            val self = imm.inputMethodList.firstOrNull { it.packageName == context.packageName }
                ?: return

            val wanted = enabled(themeManager).map { it.language }.toSet()
            val hashes = (0 until self.subtypeCount)
                .map { self.getSubtypeAt(it) }
                .filter { baseTag(it) in wanted }
                .map { it.hashCode() }
                .toIntArray()

            if (hashes.isNotEmpty()) {
                imm.setExplicitlyEnabledInputMethodSubtypes(self.id, hashes)
            }
        } catch (e: Exception) {
            // A keyboard that cannot rearrange the system's language list still types.
        }
    }

    /**
     * Becomes [layout]'s subtype, which is how this keyboard changes its own language.
     *
     * Android's model is that the choice is not the keyboard's to make - it is told which
     * subtype it currently is, by the globe in the navigation bar - and this does not work
     * around that so much as use the one door it leaves open: an input method may ask to be
     * switched to another of its own subtypes, and is then told about the change through
     * `onCurrentInputMethodSubtypeChanged` like any other. So the space bar's hold picks a
     * language by asking for it and then finding out, rather than by quietly becoming it.
     *
     * @return false when the system would not have it - an OEM that answers differently, a
     *   language with no subtype declared - which is not a failure the caller can do anything
     *   about beyond applying its own choice anyway. The keyboard is then the only one that
     *   knows, until the system says otherwise.
     */
    fun switchTo(service: InputMethodService, layout: KeyboardLayout): Boolean {
        if (layout.language.isEmpty()) return false
        return try {
            val imm = service.getSystemService(InputMethodManager::class.java) ?: return false
            val self = imm.inputMethodList.firstOrNull { it.packageName == service.packageName }
                ?: return false
            val subtype = (0 until self.subtypeCount)
                .map { self.getSubtypeAt(it) }
                .firstOrNull { baseTag(it) == layout.language }
                ?: return false
            service.switchInputMethod(self.id, subtype)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A subtype's language, without the region.
     *
     * The declared subtypes carry a full tag - `en-US` - where a layout knows only the
     * language. Region is not part of the choice: `en-US` and `en-GB` are the same twenty-six
     * keys.
     */
    private fun baseTag(subtype: InputMethodSubtype): String {
        val tag = @Suppress("DEPRECATION") subtype.languageTag.ifEmpty { subtype.locale }
        return tag.substringBefore('-').substringBefore('_').lowercase()
    }
}

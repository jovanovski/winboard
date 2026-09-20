package rocks.gorjan.winboard

import android.content.Context
import androidx.core.content.edit

/**
 * Everything WinBoard remembers.
 *
 * This was the Windows Phone launcher's `WP81Settings`, which held a whole shell's worth of
 * settings and kept the keyboard's in a file of its own so that two processes were not
 * writing one file between them. There is no launcher here and there is one process, so
 * there is one file and this class is the whole of it.
 *
 * Two kinds of thing live in it and the difference is worth keeping in mind:
 *
 *  - **The accent and the light-or-dark**, which on the phone belonged to the shell and were
 *    chosen once for everything on it. A keyboard installed on somebody else's launcher has
 *    nowhere to inherit them from, so it owns them - see the theme section of
 *    [rocks.gorjan.winboard.keyboard.KeyboardSettingsActivity].
 *  - **The keyboard's own settings**, which are what they always were.
 *
 * Nothing here is shared with any other application, and nothing here is read from one. That
 * is the point of the split: WinBoard is a keyboard, not a part of a launcher wearing a
 * keyboard's clothes.
 */
class KeyboardSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- appearance

    /**
     * The colour the keyboard picks things out in.
     *
     * One of [ACCENTS], though nothing enforces that - a value restored from a later version
     * that offers more of them is still a colour and is still drawn.
     */
    fun getAccent(): Int = prefs.getSafeInt(KEY_ACCENT, DEFAULT_ACCENT)

    fun setAccent(color: Int) {
        prefs.edit { putInt(KEY_ACCENT, color) }
    }

    /**
     * Whether the keyboard is black with white on it, rather than white with black.
     *
     * Dark to begin with, which is what Windows Phone shipped as and is also the right
     * default for a keyboard: it sits at the bottom of somebody else's application, and a
     * white slab under a dark app is the more startling of the two mistakes.
     *
     * Only the `phone` theme reads this. Windows 98 was one colour and had no such setting -
     * see `KeyboardTheme.WIN98`.
     */
    fun isDark(): Boolean = prefs.getBoolean(KEY_DARK, true)

    fun setDark(dark: Boolean) {
        prefs.edit { putBoolean(KEY_DARK, dark) }
    }

    // ---------------------------------------------------------------- keyboard

    /**
     * What the keys are made of: `phone` for Windows Phone's, `win98` for Windows 98's.
     *
     * A string rather than the keyboard's own `KeyboardTheme`, deliberately, so that a value
     * written by a later version that knows a theme this one does not falls back to the phone
     * rather than to whatever now sits at that index. The keyboard turns the string back into
     * a theme on its own side.
     */
    fun getThemeId(): String =
        prefs.getString(KEY_THEME, THEME_DEFAULT) ?: THEME_DEFAULT

    fun setThemeId(id: String) {
        prefs.edit { putString(KEY_THEME, id) }
    }

    /**
     * Whether the keyboard replaces a word by itself when you press space.
     *
     * **Off unless asked for.** Suggestions are always shown and are always one tap away; what
     * this controls is whether the keyboard acts on them without being told. A keyboard that
     * silently rewrites what someone typed is the single most complained-about thing a
     * keyboard does, and the sensible default for a keyboard somebody chose to install is to
     * offer rather than to insist.
     */
    fun getAutocorrect(): Boolean = prefs.getBoolean(KEY_AUTOCORRECT, false)

    fun setAutocorrect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOCORRECT, enabled) }
    }

    /**
     * Whether the keyboard turns shift on by itself at the start of a sentence.
     *
     * **Off unless asked for**, like the correction setting above. A field can ask for this -
     * that is what `TYPE_TEXT_FLAG_CAP_SENTENCES` is - but plenty of fields ask for it out of
     * habit rather than because the text wants it, and a keyboard that capitalises without
     * being told is one somebody has to keep un-capitalising. Shift is one tap away either way.
     */
    fun getAutoCapitalise(): Boolean = prefs.getBoolean(KEY_AUTOCAPS, false)

    fun setAutoCapitalise(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOCAPS, enabled) }
    }

    /**
     * Which keyboard languages are turned on, as layout ids.
     *
     * **English alone to begin with, and every other language is a switch somebody has to
     * throw.** Twenty-two layouts ship and none of them is privileged: a keyboard that
     * arrived with a second language already in the globe's rotation would be putting a
     * language nobody asked for in front of everybody who installed it, and the globe is only
     * useful if what it cycles through is short.
     *
     * Stored rather than read back from the system because the two are different questions:
     * this is what the user chose, and Android's enabled-subtype list is what was made of
     * that. See `KeyboardLanguages`, which keeps the second in step with the first.
     */
    fun getLanguages(): Set<String> =
        prefs.getString(KEY_LANGUAGES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: setOf(DEFAULT_LANGUAGE)

    fun setLanguages(ids: Set<String>) {
        // Never none. A keyboard with no language is a keyboard with no letters, and the
        // setting that produced it would be impossible to undo from the keyboard itself.
        val kept = ids.ifEmpty { setOf(DEFAULT_LANGUAGE) }
        prefs.edit { putString(KEY_LANGUAGES, kept.joinToString(",")) }
    }

    /**
     * Whether the bottom row is shorter than the letters above it.
     *
     * On by default. Nothing on that row is a letter - it is the space bar and the keys
     * either side - so it does not need a letter's target, and the height it gives back is
     * height the keyboard is not taking from whatever is being typed into. Off for anyone who
     * would rather have four even rows.
     */
    fun getShortBottomRow(): Boolean = prefs.getBoolean(KEY_SHORT_BOTTOM, true)

    fun setShortBottomRow(shorter: Boolean) {
        prefs.edit { putBoolean(KEY_SHORT_BOTTOM, shorter) }
    }

    /**
     * Whether the letters have a row of digits across the top.
     *
     * **Off unless asked for.** The phone had no number row - the top row of letters carries
     * the digits in its corners and produces them on a hold - and a fifth row is height taken
     * from whatever is being typed into, every time the keyboard is up, for the sake of the
     * occasional number. Somebody who types a lot of them can decide that trade is worth it.
     * See `Layouts.withNumberRow`.
     */
    fun getNumberRow(): Boolean = prefs.getBoolean(KEY_NUMBER_ROW, false)

    fun setNumberRow(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NUMBER_ROW, enabled) }
    }

    /**
     * Which engine dictates: Vosk on the phone, or the platform's own recogniser.
     *
     * **Vosk by default**, because that is the point: it runs on the phone and sends nothing
     * anywhere, where the platform's recogniser is Google's on most Android builds and
     * transcribes in the cloud. The platform one stays selectable, and stays as the fallback
     * whatever this says - Vosk publishes a model for about half the languages here, and for
     * the rest it is the only dictation there is.
     */
    fun getOfflineVoice(): Boolean = prefs.getBoolean(KEY_OFFLINE_VOICE, true)

    fun setOfflineVoice(offline: Boolean) {
        prefs.edit { putBoolean(KEY_OFFLINE_VOICE, offline) }
    }

    /**
     * How long a key must be held before it offers what is behind it, in milliseconds.
     *
     * Worth having as a setting rather than a constant because the right value is a property
     * of the hand and not of the keyboard: too short and reaching for a letter produces its
     * symbol, too long and the symbol feels like it is being withheld.
     */
    fun getHoldMs(): Int =
        prefs.getSafeInt(KEY_HOLD_MS, WB_HOLD_DEFAULT).coerceIn(WB_HOLD_MIN, WB_HOLD_MAX)

    fun setHoldMs(millis: Int) {
        prefs.edit { putInt(KEY_HOLD_MS, millis.coerceIn(WB_HOLD_MIN, WB_HOLD_MAX)) }
    }

    /**
     * How hard a keystroke buzzes: [WB_VIBRATION_SYSTEM] for the phone's own, or a strength
     * from zero (silent) to a hundred.
     *
     * The default is the phone's, and that is not a hedge. `Haptics` goes through the view
     * rather than the vibrator on purpose - it picks up whatever waveform the manufacturer
     * tuned for a keystroke, and it stays quiet when the user has turned touch feedback off -
     * and a keyboard that reached for the vibrator by default would throw both of those away
     * for everybody in order to serve the few who want it stronger.
     */
    fun getVibration(): Int =
        prefs.getSafeInt(KEY_VIBRATION, WB_VIBRATION_SYSTEM)
            .coerceIn(WB_VIBRATION_SYSTEM, WB_VIBRATION_MAX)

    fun setVibration(strength: Int) {
        prefs.edit {
            putInt(KEY_VIBRATION, strength.coerceIn(WB_VIBRATION_SYSTEM, WB_VIBRATION_MAX))
        }
    }

    /**
     * How tall the keys are against the height the phone's own were, as a percentage.
     *
     * A hundred is the phone's own proportions, which is where it starts and what every
     * measurement in the keyboard was taken at. It is the one piece of that geometry worth
     * making adjustable: how much of a screen a keyboard should take is a trade between the
     * size of a thumb and the size of what is being typed into, and neither of those is the
     * same on a four-inch phone in one hand as on a six-and-a-half-inch one in two.
     */
    fun getKeyHeight(): Int =
        prefs.getSafeInt(KEY_KEY_HEIGHT, WB_KEY_HEIGHT_DEFAULT)
            .coerceIn(WB_KEY_HEIGHT_MIN, WB_KEY_HEIGHT_MAX)

    fun setKeyHeight(percent: Int) {
        prefs.edit {
            putInt(KEY_KEY_HEIGHT, percent.coerceIn(WB_KEY_HEIGHT_MIN, WB_KEY_HEIGHT_MAX))
        }
    }

    /**
     * Whether a keystroke clicks.
     *
     * **Off unless asked for.** A vibration is felt by the person holding the phone; a sound
     * is heard by everybody in the room, and Android has defaulted keypress sounds to off for
     * long enough that a keyboard which arrives clicking reads as broken rather than as
     * faithful. Windows Phone shipped with one, and this is the switch that gives it back.
     */
    fun getSound(): Boolean = prefs.getBoolean(KEY_SOUND, false)

    fun setSound(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SOUND, enabled) }
    }

    /**
     * Whether a pressed key lifts its letter clear of the finger.
     *
     * **On**, which is the opposite way round from the two settings above and for a reason. A
     * thumb covers the key it is pressing, so the one thing you cannot see while typing on a
     * phone is what you have just typed - which is why every keyboard on every platform shows
     * this, and why somebody who has never opened these settings is better served with it
     * than without.
     */
    fun getKeyPreview(): Boolean = prefs.getBoolean(KEY_KEY_PREVIEW, true)

    fun setKeyPreview(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEY_PREVIEW, enabled) }
    }

    /**
     * Whether the caret joystick is showing, and the space bar has therefore stopped sliding.
     *
     * **Off unless asked for**, because it is a control the phone did not have and it takes
     * room on a keyboard that is otherwise exactly the phone's. See `JoystickView`.
     */
    fun getJoystick(): Boolean = prefs.getBoolean(KEY_JOYSTICK, false)

    fun setJoystick(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_JOYSTICK, enabled) }
    }

    /**
     * Whether a hold on shift or `&123` slides across the keys it brings up.
     *
     * On unless turned off, because it takes nothing away. Both keys keep every meaning they
     * had - a tap still switches, and a hold that ends where it started still locks shift or
     * stays on the symbol page - and the gesture is only what a finger that carries on moving
     * now does instead of nothing. See `KeyboardView.slideKeys`.
     */
    fun getSlideKeys(): Boolean = prefs.getBoolean(KEY_SLIDE_KEYS, true)

    fun setSlideKeys(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SLIDE_KEYS, enabled) }
    }

    /**
     * The GIPHY key the keyboard's GIF panel searches with, or empty for none.
     *
     * A setting rather than a constant in the source, because GIPHY issues these per person
     * and per application: one baked into a build is one key answering for everybody who
     * installs it, against one rate limit, and it would sit in the repository in plain sight.
     * So the app ships without one and each user pastes in their own - see the keyboard's
     * settings page, which is also where it says how to get one.
     */
    fun getGiphyKey(): String = prefs.getString(KEY_GIPHY_KEY, "").orEmpty().trim()

    fun setGiphyKey(key: String) {
        prefs.edit { putString(KEY_GIPHY_KEY, key.trim()) }
    }

    companion object {

        /**
         * The settings, and only the settings.
         *
         * Every key in this file is something somebody chose, which is why it is the one
         * file Android's backup is allowed to carry to a new phone - see
         * `res/xml/data_extraction_rules.xml`.
         */
        const val PREFS_FILE = "winboard"

        /**
         * The keyboard's working notes, which belong to this phone and stay on it.
         *
         * The clipboard history and the emoji reached for most recently. Neither is a
         * setting: one is a couple of hours of whatever the owner happened to copy, and the
         * other is a record of what they have been typing. A separate file purely so that
         * the backup rules can say so - they work on whole files, and "back up the settings
         * but not the clipboard" cannot be said about one file that holds both.
         *
         * The learned dictionary is kept out the same way but by a different means, in
         * `noBackupFilesDir`, which needs no rule at all. See [rocks.gorjan.winboard.keyboard.text.UserDictionary].
         */
        const val LOCAL_PREFS_FILE = "winboard_local"

        const val KEY_ACCENT = "accent"
        const val KEY_DARK = "dark"
        const val KEY_THEME = "theme"
        const val KEY_AUTOCORRECT = "autocorrect"
        const val KEY_AUTOCAPS = "autocaps"
        const val KEY_LANGUAGES = "languages"
        const val KEY_SHORT_BOTTOM = "short_bottom"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_OFFLINE_VOICE = "offline_voice"
        const val KEY_HOLD_MS = "hold_ms"
        const val KEY_VIBRATION = "vibration"
        const val KEY_KEY_HEIGHT = "key_height"
        const val KEY_SOUND = "sound"
        const val KEY_KEY_PREVIEW = "key_preview"
        const val KEY_JOYSTICK = "joystick"
        const val KEY_SLIDE_KEYS = "slide_keys"
        const val KEY_GIPHY_KEY = "giphy_key"

        /** The one every keyboard starts with. Matches `Layouts.EN_QWERTY`'s id. */
        const val DEFAULT_LANGUAGE = "en_qwerty"

        /** Windows Phone's own keyboard, which is the one this app is of. */
        const val THEME_DEFAULT = "phone"

        /**
         * Keystroke vibration: the phone's own, silent, or a strength in between.
         *
         * Minus one rather than a separate boolean because "the system's" is genuinely not a
         * point on this scale - it is a different mechanism, not a stronger or weaker version
         * of the same one - and two settings that can disagree about which is in force is the
         * shape that goes wrong.
         */
        const val WB_VIBRATION_SYSTEM = -1
        const val WB_VIBRATION_MAX = 100

        /** The hold, in milliseconds: what it is by default and how far it can be moved. */
        const val WB_HOLD_DEFAULT = 350
        const val WB_HOLD_MIN = 150
        const val WB_HOLD_MAX = 900

        /**
         * The key height, as a percentage of the phone's own.
         *
         * The ends match `KeyboardView.MIN_HEIGHT_SCALE` and `MAX_HEIGHT_SCALE`, which is
         * where the reasoning for them lives: below the floor a row stops clearing Android's
         * minimum touch target, and above the ceiling the keyboard starts crowding out the
         * thing being typed into.
         */
        const val WB_KEY_HEIGHT_DEFAULT = 100
        const val WB_KEY_HEIGHT_MIN = 65
        const val WB_KEY_HEIGHT_MAX = 150

        /**
         * The accent a fresh install starts in: Cyan, from the palette below.
         *
         * Windows Phone 8.1's own default. The launcher this keyboard came out of opened in
         * Red instead, because a launcher's first screen is its own advertisement and a wall
         * in the same blue as every other phone's is a wall nobody remembers. A keyboard has
         * no such claim to make - it is furniture at the bottom of somebody else's app - so
         * it opens in the colour the phone did.
         */
        val DEFAULT_ACCENT: Int = 0xFF1BA1E2.toInt()

        /**
         * The twenty accent colours Windows Phone 8.1 offered, in the order the
         * Settings > theme picker listed them.
         */
        val ACCENTS: List<Pair<String, Int>> = listOf(
            "Lime" to 0xFFA4C400.toInt(),
            "Green" to 0xFF60A917.toInt(),
            "Emerald" to 0xFF008A00.toInt(),
            "Teal" to 0xFF00ABA9.toInt(),
            "Cyan" to 0xFF1BA1E2.toInt(),
            "Cobalt" to 0xFF0050EF.toInt(),
            "Indigo" to 0xFF6A00FF.toInt(),
            "Violet" to 0xFFAA00FF.toInt(),
            "Pink" to 0xFFF472D0.toInt(),
            "Magenta" to 0xFFD80073.toInt(),
            "Crimson" to 0xFFA20025.toInt(),
            "Red" to 0xFFE51400.toInt(),
            "Orange" to 0xFFFA6800.toInt(),
            "Amber" to 0xFFF0A30A.toInt(),
            "Yellow" to 0xFFE3C800.toInt(),
            "Brown" to 0xFF825A2C.toInt(),
            "Olive" to 0xFF6D8764.toInt(),
            "Steel" to 0xFF647687.toInt(),
            "Mauve" to 0xFF76608A.toInt(),
            "Taupe" to 0xFF87794E.toInt(),
        )
    }
}

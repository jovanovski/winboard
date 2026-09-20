package rocks.gorjan.winboard.keyboard

import rocks.gorjan.winboard.KeyboardPalette
import rocks.gorjan.winboard.KeyboardSettings

/**
 * What the keyboard is painted in, and where those answers come from.
 *
 * This file used to be the bill for living inside a launcher. The keyboard ran in a process
 * of its own - an input method has to answer questions about the text box it is typing into,
 * and when the box belongs to the launcher and the keyboard is in the launcher too, the
 * thread that must answer is the thread that is waiting for the answer - and so the accent,
 * which was the *launcher's* setting chosen on the launcher's theme page, had to be
 * broadcast across the process line every time it changed. A preference file could not carry
 * it: SharedPreferences reads the file once per process and never looks again.
 *
 * None of that survives the move. WinBoard is a keyboard and nothing else, there is one
 * process, one settings file, and the accent is the keyboard's own - chosen on the keyboard's
 * own settings page, which is in this process, so a read here is always a read of what was
 * just written. What is left is the two questions the keyboard actually asks, and the one
 * distinction between them that is still worth drawing.
 */
internal object KeyboardAppearance {

    /**
     * The colours the *settings page* is drawn in: the accent, and light or dark.
     *
     * Deliberately not the keyboard's. That page is a page of an application and is dressed
     * as one - a Windows Phone page, with a big lowercase title and the accent in its section
     * headings - whatever the keyboard underneath it has been made to look like. Somebody
     * with the Windows 98 keyboard turned on has not asked for a beige settings screen.
     */
    fun palette(settings: KeyboardSettings): KeyboardPalette = KeyboardPalette.from(settings)

    /** Which keyboard the user chose. See [KeyboardTheme]. */
    fun theme(settings: KeyboardSettings): KeyboardTheme =
        KeyboardTheme.of(settings.getThemeId())

    /**
     * The colours the keyboard itself is drawn in.
     *
     * The theme's answer, given the accent and the background from above. `PHONE` hands back
     * what it was given, which is not a special case being tolerated - it is the theme; the
     * others are entitled to ignore both, and Windows 98 does.
     */
    fun keyboardPalette(settings: KeyboardSettings): KeyboardPalette =
        theme(settings).palette(palette(settings))
}

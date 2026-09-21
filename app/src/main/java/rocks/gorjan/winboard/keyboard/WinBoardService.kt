package rocks.gorjan.winboard.keyboard

import android.Manifest
import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import rocks.gorjan.winboard.KeyboardPalette
import rocks.gorjan.winboard.keyboard.text.Bigrams
import rocks.gorjan.winboard.keyboard.text.Composer
import rocks.gorjan.winboard.keyboard.text.Dictionary
import rocks.gorjan.winboard.keyboard.text.Suggester
import rocks.gorjan.winboard.keyboard.text.UserDictionary
import java.io.File
import java.text.BreakIterator
import java.util.concurrent.Executors
import rocks.gorjan.winboard.KeyboardSettings

/**
 * WinBoard: the Windows Phone 8.1 keyboard, as an input method for the whole phone.
 *
 * It began as one half of a launcher - the shell went to real trouble making a text box look
 * like Windows Phone, the caret, the band behind a selection, the three grips either end of
 * it, and then the keyboard that came up under it was whatever the phone happened to have -
 * and it is the whole of the application now. Nothing here reads another app's settings and
 * nothing here needs one installed.
 *
 * An input method belongs to the system, so once it is turned on it appears in every text box
 * on the device. That is the point, and it is also why almost everything in here is written
 * defensively. A crash in an ordinary activity loses that activity; a crash in here loses
 * text entry in whatever the user happened to be doing, everywhere, with the way out buried
 * in system settings.
 *
 * The palette is re-read rather than pushed. The settings page is a different window and
 * often a different task, and it has no handle on a live keyboard to push a colour into.
 * Instead the preferences are watched directly, and the palette is read again every time the
 * keyboard is shown - the listener keeps a visible keyboard current, and the re-read covers
 * the case where it was not visible when the change happened.
 */
class WinBoardService : InputMethodService(), KeyView.Listener {

    private lateinit var themeManager: KeyboardSettings
    private lateinit var palette: KeyboardPalette
    private var host: KeyboardHost? = null
    private val keyboard: KeyboardView? get() = host?.keyboard

    /** The word being typed, and what the keyboard last did to it. */
    private val composer = Composer()

    /** Learned words and pairs. Kept out of the cloud backup - see [UserDictionary]. */
    private var learned: UserDictionary? = null

    /** One per language, built on first use: loading a dictionary is three megabytes of I/O. */
    private val suggesters = HashMap<String, Suggester>()

    /** What the bar is currently offering. Index 0 is always the literal text. */
    private var offered: List<String> = emptyList()

    /**
     * How many of [offered] are emoji rather than words, sitting at indices 1 upwards.
     *
     * The bar hands back one index and knows nothing about what is in it, so this is what
     * tells a tap on 👑 from a tap on `crowns`. It is also what keeps autocorrect honest:
     * the keyboard's own best word used to be `offered[1]` by construction, and with emoji
     * in front of it that constant would have made a space insert an emoji. See
     * [correctionIndex], which is the same claim written once instead of at three call sites.
     */
    private var emojiOffered = 0

    /** Where the keyboard's own best word sits on the bar, with the emoji counted in. */
    private val correctionIndex: Int get() = 1 + emojiOffered

    /** Whether the bar's [index] holds an emoji rather than a word. */
    private fun isEmojiSlot(index: Int): Boolean = index in 1..emojiOffered

    /**
     * Sets what the bar is showing, both halves at once.
     *
     * A function rather than two assignments because they are one fact, and there are eight
     * places that clear or replace it - one of them forgetting the count would leave the
     * keyboard believing there were emoji on a bar that had none, which is a space that
     * inserts the wrong thing.
     */
    private fun offer(words: List<String>, emoji: Int = 0) {
        offered = words
        emojiOffered = emoji
    }

    /**
     * The typed text [offered] was worked out for.
     *
     * Because the search runs off the main thread, what the bar is showing can be a keystroke
     * behind what has been typed. That is fine for suggestions - they catch up in a few
     * milliseconds - and not fine for deciding an automatic correction, which must never act
     * on a guess made about a different word. See [finishWord].
     */
    private var offeredFor: String = ""

    /**
     * Suggestions are worked out here, not on the thread drawing the keyboard.
     *
     * This is what fixes typing that falls further and further behind during a fast run of
     * keys. A search takes a few milliseconds and every keystroke asked for one *on the main
     * thread*, so once the interval between keys dropped below the cost of a search the work
     * queued up behind itself and the lag grew for as long as the burst lasted.
     *
     * One thread, so the searches cannot overlap - [Suggester] keeps its working state in
     * fields and is emphatically not safe to call from two places at once - and a generation
     * counter, so that a result which arrives after the user has typed on is discarded rather
     * than briefly replacing the right answer with an older one.
     */
    private val searches = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // Raised from the default, which is the priority of background work nobody is
            // waiting for. Somebody is waiting for this: it is on the path between a key
            // going down and a word appearing, and a phone with anything else going on will
            // otherwise schedule it whenever it gets round to it.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            runnable.run()
        }, "winboard-suggest").apply { isDaemon = true }
    }
    /**
     * Reading dictionaries, which is slow, rare, and must never hold up a search.
     *
     * A thread of its own rather than a second use of [searches]. See [warmSuggester].
     */
    private val loads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "winboard-load").apply { isDaemon = true }
    }

    private val onMain = Handler(Looper.getMainLooper())
    // Read on the search thread and written on the main one, so that a search can find out
    // it has been overtaken and stop rather than finish work nobody will look at.
    @Volatile
    private var searchGeneration = 0

    /** The word before the one being typed, for next-word prediction. */
    private var previousWord: String? = null

    /**
     * The emoji panel, built on first use and kept afterwards.
     *
     * Kept because it holds three and a half thousand emoji and a scroll position, and
     * rebuilding that every time the key is pressed would be a visible stutter on opening.
     */
    private var emoji: EmojiPanel? = null

    /**
     * Whether keystrokes are being fed to the emoji panel's search box rather than the app.
     *
     * An input method cannot type into a text field of its own - it has no focus to give
     * itself - so the panel's search bar is a drawing, and while this is set the letter keys
     * build up a query for it instead of a word for the field being edited.
     */
    private var searchingEmoji = false



    /** Which letter layout is in use. The symbol pages are a detour from it, not a choice. */
    private var language: KeyboardLayout = Layouts.EN_QWERTY

    private var shiftState = ShiftState.OFF

    /** When shift was last tapped, for spotting the double-tap that locks it. */
    private var lastShiftTap = 0L

    /** When space was last committed, for the double-space that makes a full stop. */
    private var lastSpace = 0L

    /** Set for password fields and the like: no learning, and later no suggestions. */
    private var privateField = false

    private enum class ShiftState { OFF, ONCE, LOCKED }

    /**
     * The keyboard's own settings changing, in the keyboard's own process.
     *
     * Still a preference listener and not a message from anywhere, which is the point of
     * having moved the settings page into this process along with the keyboard: dragging the
     * key height slider writes a preference and this fires, with the keys resizing under the
     * finger exactly as they did when there was one process for everything.
     *
     * The accent and the background are among them. In the launcher this keyboard came out
     * of they were the launcher's, written in another process, and had to be broadcast
     * across; here they are two more settings in the same file as the rest and arrive the
     * same way. See [KeyboardAppearance].
     */
    private val prefsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KeyboardSettings.KEY_HOLD_MS,
            KeyboardSettings.KEY_AUTOCORRECT,
            KeyboardSettings.KEY_AUTOCAPS,
            KeyboardSettings.KEY_OFFLINE_VOICE,
            KeyboardSettings.KEY_SHORT_BOTTOM,
            KeyboardSettings.KEY_NUMBER_ROW,
            KeyboardSettings.KEY_SOUND,
            KeyboardSettings.KEY_KEY_PREVIEW,
            KeyboardSettings.KEY_JOYSTICK,
            KeyboardSettings.KEY_SLIDE_KEYS,
            // Changed by dragging a slider on a page that has a text box on it, so this
            // fires many times a second with the keyboard on screen underneath. It has to:
            // watching the keys resize under the finger is the whole point of that box.
            KeyboardSettings.KEY_KEY_HEIGHT -> applySettings()
            // The one keyboard setting that is also a colour. [applySettings] changes the
            // shape of a key and the palette is what everything else on the keyboard is
            // painted from, so both halves have to be asked for or a Windows 98 keyboard
            // comes up grey with a Windows Phone suggestion bar over it.
            KeyboardSettings.KEY_THEME -> {
                applySettings()
                refreshPalette()
            }
            // Colours and nothing else, so the key shapes are left alone and only the paint
            // is read again. The Windows 98 theme ignores both, which is not a case to be
            // handled here - it is what its palette says.
            KeyboardSettings.KEY_ACCENT,
            KeyboardSettings.KEY_DARK -> refreshPalette()
            // Changed from the settings page, which is a different window: the system's own
            // list has to be brought back into step before the globe is next used.
            KeyboardSettings.KEY_LANGUAGES -> {
                KeyboardLanguages.applyToSystem(this, themeManager)
                applySettings()
            }
        }
    }

    /**
     * Reads the keyboard's own settings and applies them.
     *
     * Called when the keyboard is shown as well as when a setting changes, because the
     * settings page is a different window and may well have been used while this was hidden.
     */
    private fun applySettings() {
        autocorrect = themeManager.getAutocorrect()
        autoCapitalise = themeManager.getAutoCapitalise()
        offlineVoice = themeManager.getOfflineVoice()
        keyboard?.holdMillis = themeManager.getHoldMs().toLong()
        keyboard?.shortBottomRow = themeManager.getShortBottomRow()
        keyboard?.numberRow = themeManager.getNumberRow()
        keyboard?.keyHeightScale = themeManager.getKeyHeight() / 100f
        keyboard?.keyPreview = themeManager.getKeyPreview()
        keyboard?.joystick = themeManager.getJoystick()
        keyboard?.slideKeys = themeManager.getSlideKeys()
        host?.theme = KeyboardAppearance.theme(themeManager)
        // Survives a trip to the symbol pages, which have a space bar and no language.
        keyboard?.setSpaceLabel(language.name)
        // What the hold on that same key offers. Read here rather than on the touch path
        // because this is where a language being switched on in the settings arrives, and a
        // preference read under a finger is the sort of cost nobody can account for later.
        keyboard?.setLanguages(KeyboardLanguages.enabled(themeManager), language)
        // Started now, so the dictionary is parsed and waiting before the first key is
        // pressed rather than during it. See [warmSuggester].
        warmSuggester()
        // Read here rather than on the touch path: these fire under a finger, and a
        // preference read per keystroke is exactly the sort of thing that turns into a lag
        // nobody can account for. See [KeyboardHaptics.strength].
        KeyboardHaptics.refresh(this, themeManager)
        KeyboardSounds.refresh(this, themeManager)
    }

    /**
     * Whether a space may replace a word by itself.
     *
     * Off unless asked for. Suggestions are shown and tappable either way; this is only about
     * whether the keyboard acts on one without being told to.
     */
    private var autocorrect = false

    /** Whether shift comes on by itself at the start of a sentence. Off unless asked for. */
    private var autoCapitalise = false

    /**
     * The app's preferences, which are the keyboard's. See [KeyboardSettings].
     *
     * One file and one process. The settings page writes it, the keyboard reads it, and the
     * listener below is what carries a change from the one to the other - which works
     * precisely because a preference listener is an in-process affair and both halves are in
     * this process.
     */
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(KeyboardSettings.PREFS_FILE, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        themeManager = KeyboardSettings(this)
        palette = KeyboardAppearance.keyboardPalette(themeManager)
        prefs.registerOnSharedPreferenceChangeListener(prefsWatcher)
        learned = UserDictionary.open(this)
        KeyboardLanguages.applyToSystem(this, themeManager)

        // The dictionary starts loading here, which is as early as it can: the service is
        // created before the keyboard is ever shown, so by the time anybody taps a text box
        // the reading and parsing is usually long finished. Doing it when the view appears
        // was already off the main thread, but a couple of megabytes still take a moment, and
        // somebody who taps a field and starts typing immediately would beat it and see the
        // first word or two go by with nothing offered.
        language = KeyboardLanguages.enabled(themeManager).first()
        warmSuggester()
        // The same argument as the dictionary above, on the same thread: read it before
        // anybody has tapped a field, so the first word typed can already be the name of
        // something.
        warmEmojiNames()
    }

    /**
     * The keyboard's process is going away.
     *
     * Rarely reached - an input method is kept alive by the system for as long as it is the
     * chosen one - but the listener is given back all the same. One left registered on a
     * destroyed service is the shape of leak that survives every test, because the way to
     * see it is to have the keyboard torn down and rebuilt a few hundred times.
     */
    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsWatcher)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        palette = KeyboardAppearance.keyboardPalette(themeManager)
        val view = KeyboardHost(this, palette)
        view.keyboard.listener = this
        // Whatever was last in use, unless it has since been turned off in the settings.
        if (KeyboardLanguages.enabled(themeManager).none { it.id == language.id }) {
            language = KeyboardLanguages.enabled(themeManager).first()
        }
        view.keyboard.setLayout(language)
        view.keyboard.setLanguages(KeyboardLanguages.enabled(themeManager), language)
        view.bar.onWordPicked = { index -> takeSuggestion(index) }
        view.bar.onVoice = { toggleVoice() }
        view.bar.onWordForgotten = { index -> forgetSuggestion(index) }
        view.bar.onClipboard = { showClipboardHistory() }
        host = view
        return view
    }

    /**
     * The suggester for whichever language is up, built once and kept.
     *
     * Lazily, because a dictionary is two megabytes to read and parse and the second language
     * may never be used at all - and kept, because doing it per keystroke would be absurd and
     * doing it per showing would be a visible pause every time the keyboard opens.
     *
     * **A missing dictionary is not a missing suggester.** Twenty-two languages ship a layout
     * and two of them ship a word list, so for most of them `load` finds nothing - and the
     * suggester is built anyway, around a null dictionary, because it also holds the *learned*
     * words and pairs and those are not language data that shipped, they are what this person
     * has typed. A German layout with no word list still ought to finish a name it has seen
     * five times. Caching the empty case matters as much: without it every keystroke in every
     * one of those languages opens an asset that is not there and pays for the exception.
     */
    private fun suggester(): Suggester? = suggesters[language.id]

    /**
     * Builds the suggester for the current language, off the main thread, once.
     *
     * A dictionary is a couple of megabytes read out of the assets - which are compressed, so
     * reading them is inflating them - and then parsed. That used to happen inside the first
     * call to `suggester()`, which is to say **on the main thread, on the first keystroke**,
     * where it is a stall at exactly the moment the keyboard is being judged.
     *
     * Started when the keyboard appears instead, so by the time a key is pressed the work is
     * usually already done. When it is not, the keystroke is not made to wait for it: the bar
     * shows what was typed, as it always does, and refreshes itself when the dictionary
     * arrives a moment later.
     */
    private fun warmSuggester() {
        val id = language.id
        if (suggesters.containsKey(id) || warming == id) return
        warming = id
        val layout = language
        // Its own thread, not the search queue. They shared one, and that was the stall: a
        // dictionary takes a moment to read and parse, and while it did, every keystroke's
        // search sat in the queue behind it - so the keyboard went quiet for as long as the
        // load took, at exactly the moment somebody had started typing. Searching and loading
        // are not the same kind of work and should not wait for each other.
        loads.execute {
            val dictionary = Dictionary.load(this, layout.language)
            // On the same thread and for the same reason: another asset to inflate and parse,
            // and the keyboard is no more able to afford it on the first keystroke than it
            // was able to afford the trie.
            val bigrams = Bigrams.load(this, layout.language)
            val built = Suggester(dictionary, layout, learned, bigrams)
            onMain.post {
                suggesters[id] = built
                warming = null
                // The keystrokes that arrived while this was loading were answered with the
                // literal alone. Now there is something to say about them.
                refreshCandidates()
            }
        }
    }

    /** The language whose suggester is being built, so it is not built twice at once. */
    private var warming: String? = null

    /**
     * Windows Phone never gave the keyboard the whole screen.
     *
     * Android's fullscreen mode replaces the app with a text box of the system's own when
     * the screen is short - which is to say in landscape on most phones - and it looks
     * nothing like anything in this shell. The phone simply kept the keyboard at the foot of
     * the screen and let the app have the rest, so that is what happens here.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInput() {
        // Dictation belongs to the field it was started in. Left running, it would carry on
        // hearing and start typing into whatever came next.
        stopVoice()
        // The word in progress is abandoned rather than committed: the field is going away and
        // whatever is half-typed in it is not the keyboard's to finish.
        composer.reset()
        previousWord = null
        offer(emptyList())
        learned?.flush()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // A row left open would still be on screen the next time the keyboard is shown,
        // offering alternates for a key that is no longer being held. The language list is
        // the same thing said in a taller window.
        keyboard?.hideAlternates()
        keyboard?.hideLanguages()
        cancelSlide()
        clipboardHistory?.dismiss()
        watchClipboard(false)
        // Closed, so it opens the way a keyboard opens: letters, and nothing held down.
        // A locked shift and the symbol page are both answers to the sentence being typed,
        // and neither is an answer to whatever field is asked for next - a message box that
        // comes up SHOUTING, or one showing brackets and currency signs, is the keyboard
        // still arguing about the last field. The lock has to be let go of here because it
        // is the one thing [updateAutoCaps] will not touch on the way back in: a shift the
        // user locked is deliberately left alone there, so if it is not dropped on the way
        // out it is never dropped at all.
        setShift(ShiftState.OFF)
        showLetters()
        super.onFinishInputView(finishingInput)
    }

    // ---------------------------------------------------------------- clipboard

    /** The history list, built on the first hold and kept afterwards. */
    private var clipboardHistory: ClipboardPopup? = null

    /**
     * Whether the clipboard is being watched right now.
     *
     * Only while the keyboard is on screen, which is the only time a read would be allowed
     * anyway - Android refuses the clipboard to an input method that is not the active one,
     * and a listener firing outside that window is a refusal logged for nothing. The one
     * thing it catches that [onStartInputView] does not is a copy made while the keyboard is
     * already up, which is what selecting a word in the field you are typing in and hitting
     * copy does.
     */
    private var watchingClipboard = false

    private val clipWatcher = ClipboardManager.OnPrimaryClipChangedListener {
        ClipboardStore.refresh(this)
        refreshCandidates()
    }

    private fun watchClipboard(on: Boolean) {
        if (on == watchingClipboard) return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        try {
            if (on) manager.addPrimaryClipChangedListener(clipWatcher)
            else manager.removePrimaryClipChangedListener(clipWatcher)
            watchingClipboard = on
        } catch (e: Exception) {
            // Not worth taking the keyboard down over; the paste offer simply goes stale.
        }
    }

    /** A clip was chosen from the list. */
    private fun paste(clip: Clip) {
        if (clip.text.isEmpty()) return
        commit(clip.text)
        // Pasted text is not a word this keyboard typed, so it teaches it nothing and must
        // not be treated as the word a following-suggestion would follow.
        previousWord = null
        composer.reset()
        refreshCandidates()
    }

    /** The mark was tapped: everything still inside the window, to choose from. */
    private fun showClipboardHistory() {
        val stack = host ?: return
        val clips = ClipboardStore.history(this)
        if (clips.isEmpty()) {
            // Said rather than shown as a dead button. The bar is the one surface the
            // keyboard owns that the user is already looking at, and it is where the rest of
            // what this has to say goes - see [CandidateBar.setMessage].
            clipboardHistory?.dismiss()
            stack.bar.setMessage(NOTHING_COPIED)
            onMain.postDelayed({
                host?.bar?.setMessage(null)
                refreshCandidates()
            }, MESSAGE_MS)
            return
        }
        val popup = clipboardHistory ?: ClipboardPopup(this, palette).also {
            it.onPicked = { clip -> paste(clip) }
            clipboardHistory = it
        }
        popup.applyPalette(palette)
        // The keyboard's own scale, not the current layout's key width. The list hangs off
        // the bar and is sized the way the bar is; handed a key width it grew with the keys,
        // and a four-column number pad has keys two and a half times as wide as the letters.
        // See [KeyboardView.referenceUnit].
        popup.toggle(stack.bar, clips, stack.keyboard.referenceUnit(), stack.keyboard.height)
    }

    /**
     * A field has been handed over, or the one being edited has been restarted.
     *
     * Overridden as well as [onStartInputView] because the two do not always both happen. An
     * app that clears its box after sending a message calls `restartInput`, and if the
     * keyboard is already on screen the view is not started again - so anything reset only in
     * `onStartInputView` survives into the next message. Which is how the word just sent came
     * back as soon as somebody started typing the next one.
     */
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Where the caret is before anything has been typed. The field hands this over
        // unasked, and taking it matters because [onUpdateSelection] does not fire until
        // something moves: without it the first press into a field would be judged by the
        // last field's numbers, and a backspace onto text that is already selected would
        // delete the wrong thing. See [selStart].
        selStart = (info?.initialSelStart ?: 0).coerceAtLeast(0)
        selEnd = (info?.initialSelEnd ?: 0).coerceAtLeast(0)
        reportedStart = selStart
        reportedEnd = selEnd
        unanswered = 0
        columnAt = -1
        // A new field is a new application, and it does not inherit the last one's silence.
        // Only a new one, though: an application that restarts input on every keystroke -
        // and several do - would otherwise clear the mark as fast as it could be set.
        if (!restarting) {
            notAskingUntil = 0L
            quietFor = 0L
        }
        forgetTheSentence()
    }

    /**
     * Drops everything about the sentence in progress.
     *
     * The composing region is ended explicitly rather than merely forgotten. It belongs to
     * the field, not to this - so leaving one open when the text has been sent or the field
     * has changed means the next thing typed lands on top of whatever the region still covers
     * instead of after it.
     */
    private fun forgetTheSentence() {
        phantomSpace = false
        try {
            currentInputConnection?.finishComposingText()
        } catch (e: Exception) {
            // The field can already be gone, which is the case this is guarding anyway.
        }
        composer.reset()
        previousWord = null
        offer(emptyList())
        offeredFor = ""
        host?.bar?.clear()
        // Sending a message empties the box, and an empty box is where the paste offer
        // belongs - so the bar is rebuilt rather than simply emptied.
        refreshCandidates()
    }

    /**
     * The cursor, the selection, or the composing region has moved.
     *
     * **This is how the keyboard finds out that the text changed without it.** Nothing else
     * tells it. A chat application's send button is the application's own button, not this
     * keyboard's enter key - so tapping it never runs a line of code here. The app takes the
     * text, sends it, and empties its box, and the keyboard is none the wiser: it still
     * believes it is part-way through composing `hello`, so the next letter typed produces
     * `hellow` and the sent word appears to come back from the dead.
     *
     * The test is whether the composing region is still where this put it. If the app has
     * dropped it, or the cursor has moved outside it - somebody tapped elsewhere in the text,
     * or the box was cleared - then what this thinks is being typed is not what is on screen,
     * and the right thing to do is forget it and start again from what is really there.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        // Recorded before anything else, and whatever else happens below: this is the only
        // way an input method ever learns where the caret is, and sliding the space bar needs
        // to know. See [onCursorSlide].
        selStart = newSelStart
        selEnd = newSelEnd
        reportedStart = newSelStart
        reportedEnd = newSelEnd
        // Something moved, so the moves made on the field's silence were not made against a
        // wall after all. See [onCursorSlide].
        unanswered = 0
        if (!composer.isComposing) return

        // Still ours if the region exists and the cursor sits at its end - which is exactly
        // the state `setComposingText` leaves behind.
        //
        // Deliberately *not* compared against the length of what is being composed, though
        // that reads like the stronger check. This callback comes from the other app's
        // process and arrives late: delete two characters quickly and the report about the
        // first can land after the composer has already recorded the second, so the lengths
        // disagree over nothing at all and the word is thrown away mid-edit. The three
        // numbers here all come from the same report and are consistent with each other
        // however stale they are, which is what makes the check trustworthy.
        val intact = candidatesStart >= 0 &&
            newSelStart == candidatesEnd &&
            newSelEnd == candidatesEnd
        if (!intact) forgetTheSentence()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.hideAlternates()
        keyboard?.hideLanguages()
        cancelSlide()
        // Now, rather than when the clip was made: an input method may read the clipboard
        // while it is the active one, which it is exactly now and mostly is not. See
        // [ClipboardStore], and [watchingClipboard] for the other half of the same idea.
        ClipboardStore.refresh(this)
        watchClipboard(true)
        composer.reset()
        previousWord = null
        offer(emptyList())
        applySettings()
        host?.bar?.clear()
        searchingEmoji = false
        emoji?.let {
            it.query = ""
            it.searchMode = false
            it.onClosed()
        }
        host?.hidePanel()
        refreshPalette()
        adoptSubtype()
        applyEditorInfo(info)
        // Last, after the bar has been cleared and the field is known: an empty box with
        // something on the clipboard should be offering to paste it the moment it opens,
        // which is the whole point of the offer. Nothing else would call this until the
        // first key was pressed, by which time the box is not empty any more.
        refreshCandidates()
    }

    /**
     * Reads the field being typed into and shapes the keyboard to it.
     *
     * Three separate things come out of one [EditorInfo]: what the enter key should say and
     * do, whether the first letter should be capital, and whether this is a field where a
     * keyboard should keep no record of what was typed.
     */
    private fun applyEditorInfo(info: EditorInfo?) {
        val view = keyboard ?: return
        val type = info?.inputType ?: InputType.TYPE_NULL
        val variation = type and InputType.TYPE_MASK_VARIATION
        val klass = type and InputType.TYPE_MASK_CLASS

        privateField = (klass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) ||
            (klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

        view.setLayout(layoutFor(klass))
        view.setSymbolsLabel(
            if (view.currentLayout().id.startsWith("symbols")) Layouts.LETTERS_LABEL
            else Layouts.SYMBOLS_LABEL
        )
        view.setSpaceLabel(language.name)
        view.setContextualKey(contextualKeyFor(klass, variation))
        refreshEnterKey()
        updateAutoCaps(info)
    }

    /**
     * Puts the right word, or the return arrow, on the enter key.
     *
     * Kept apart from [applyEditorInfo] because it has to be redone every time the key grid
     * is rebuilt - switching to the symbol page and back makes a fresh set of key views, and
     * the new enter key knows nothing about the field it is being shown for. Re-running the
     * whole of [applyEditorInfo] would do it, but that also chooses the layout, and choosing
     * the layout again is exactly what must not happen here: on a number field it would put
     * the digits straight back the instant the user asked for letters.
     */
    private fun refreshEnterKey() {
        val view = keyboard ?: return
        val job = enterJob()

        // A mark for the two actions that have one, and the word for the rest. See [EnterMark]
        // for which and why. Nothing at all when the key is only a return key, which leaves
        // the arrow the action's glyph would otherwise have replaced.
        val mark = if (!job.acts) EnterMark.NONE else when (job.standard) {
            EditorInfo.IME_ACTION_SEARCH -> EnterMark.SEARCH
            EditorInfo.IME_ACTION_GO -> EnterMark.GO
            else -> EnterMark.NONE
        }
        val label = if (!job.acts || mark != EnterMark.NONE) null else when (job.standard) {
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_NEXT -> "next"
            EditorInfo.IME_ACTION_DONE -> "done"
            else -> job.ownWord()
        }
        view.setEnterKey(label, mark)
    }

    /**
     * What the enter key does in the field being typed into. See [enterJobFor], which is
     * where the reasoning is and which is tested on its own.
     */
    private fun enterJob(info: EditorInfo? = currentInputEditorInfo): EnterJob =
        enterJobFor(info?.imeOptions ?: 0, info?.actionLabel, info?.actionId ?: 0)

    /**
     * Turns shift on at the places a sentence starts.
     *
     * Only when the field has asked for it - a field of surnames capitalises every word and
     * a field of passwords capitalises nothing, and both say so - and only when shift is not
     * already locked, which is a decision the user has made and the keyboard does not get to
     * overrule.
     */
    private fun updateAutoCaps(info: EditorInfo? = currentInputEditorInfo) {
        // Off unless asked for. A field asking for capitals is not the same as the user
        // wanting them, and shift is one tap away.
        if (!autoCapitalise) return
        if (shiftState == ShiftState.LOCKED) return
        val type = info?.inputType ?: return
        if (type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES == 0 &&
            type and InputType.TYPE_TEXT_FLAG_CAP_WORDS == 0 &&
            type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS == 0
        ) {
            return
        }
        // Left exactly as it is when there is no answer - see [ask]. A shift the user set is
        // not something to drop over a question the application could not be bothered with.
        val caps = ask { currentInputConnection?.getCursorCapsMode(type) } ?: return
        setShift(if (caps != 0) ShiftState.ONCE else ShiftState.OFF)
    }

    /**
     * Which keyboard a field gets before anyone has pressed anything.
     *
     * A field that only accepts a number is the one case where showing letters is not merely
     * unhelpful but wrong - the phone put a keypad up straight away, and so does every
     * keyboard since. Four wide columns of digits in telephone order, at the keyboard's own
     * height so that nothing above it moves when the field changes.
     *
     * The two pads differ only in which characters share the column with the digits: a
     * telephone number wants `+`, `*` and `#`, and an amount of money wants a decimal point
     * and a minus sign.
     */
    private fun layoutFor(inputClass: Int): KeyboardLayout = when (inputClass) {
        InputType.TYPE_CLASS_PHONE -> Layouts.PHONE_PAD
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Layouts.NUMBER_PAD
        else -> language
    }

    /**
     * What belongs on the key beside the space bar, for this field.
     *
     * A comma most of the time, and otherwise the one character this kind of field is going
     * to send somebody to the symbol page for over and over: an address needs `@`, a web
     * address needs `/`. The full stop on the other side of the space bar is left alone -
     * sentences end the same way whatever is being written. The field says which it is; that
     * is what the variation on `inputType` is for.
     */
    private fun contextualKeyFor(inputClass: Int, variation: Int): String = when (variation) {
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "@"
        InputType.TYPE_TEXT_VARIATION_URI -> "/"
        else -> ","
    }

    /**
     * Puts the current colours on everything, if they are not on it already.
     *
     * The guard is not tidiness. Repainting means clearing the glyph caches and filling them
     * again, and filling one means parsing an SVG out of the assets and building a `Path` per
     * shape in it - the shift arrow, the smiley, the settings cog, the corner marks - which is
     * tens of milliseconds of the main thread. This runs on every [onStartInputView], which is
     * every single time somebody taps into a text box, and the colours had almost never
     * changed since the last one: the accent is the launcher's and arrives by broadcast, and
     * the settings that move it are not touched between one text field and the next.
     *
     * [KeyboardPalette] is a data class, so "already on it" is exactly what its own equality says.
     * [paintNavigationBar] stays outside the guard, because that is a flag on a window rather
     * than a colour on a view, and the window is torn down and built again between showings.
     */
    private fun refreshPalette() {
        val next = KeyboardAppearance.keyboardPalette(themeManager)
        if (next != palette || !palettePainted) {
            palette = next
            palettePainted = true
            host?.applyPalette(next)
            emoji?.applyPalette(next)
        }
        paintNavigationBar()
    }

    /**
     * Whether anything has actually been painted in [palette] yet.
     *
     * The field starts out holding the colours read in [onCreate], which is the right answer
     * and has been given to nobody, so the first refresh has to run even though nothing has
     * changed. Every one after it can look at the colours alone.
     */
    private var palettePainted = false

    /**
     * Says which way round the system should draw the strip it puts over the keyboard.
     *
     * The chevron and the globe down there are the system's, not this keyboard's - see
     * [KeyboardView.bottomInset] - and it draws them white unless it is told the bar they
     * sit in is a light one. What they sit on is the keyboard's own ground, which under
     * the Light theme is very nearly white, so left untold the system draws a white globe
     * and a white chevron on white and the two keys nobody can afford to lose are gone.
     *
     * Asked for on every show rather than once: the flag belongs to a window that is torn
     * down and rebuilt with the keyboard, and the theme can change while it is down.
     */
    private fun paintNavigationBar() {
        val host = window?.window ?: return
        WindowCompat.getInsetsController(host, host.decorView)
            .isAppearanceLightNavigationBars = !palette.isDark
    }

    /**
     * The window is up: say again which way round its strip goes.
     *
     * [refreshPalette] has already asked once, from `onStartInputView`, which on some
     * shows runs before the window is on screen and has its appearance settled.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        paintNavigationBar()
    }

    // ------------------------------------------------------- asking the field

    /**
     * When the field may be asked something again, after one refused to answer promptly.
     *
     * Zero - the ordinary state - is a moment long past, so the test reads the same whether
     * anything has ever gone slow or not. See [ask].
     */
    private var notAskingUntil = 0L

    /**
     * How long the current silence is, which is not a constant and must not be.
     *
     * A fixed window costs one full stall per window, for as long as the application stays
     * slow: the silence runs out, the next question waits the system's two seconds all over
     * again, and the phone locks up once every window until the user gives up. Two seconds
     * every three is not a keyboard anybody can type on.
     *
     * So each stall in a row doubles it, up to [NOT_ASKING_MAX_MS], and the first prompt
     * answer clears it back to nothing. An application having a bad moment is asked again
     * almost immediately; one that is genuinely wedged is left alone, and costs a stall a
     * minute rather than a stall a sentence. See [ask].
     */
    private var quietFor = 0L

    /**
     * Whether this keyboard is running on the launcher's own main thread.
     *
     * **A question can only be answered by somebody else.** The keyboard ships inside the
     * launcher rather than as a separate application, so when the box being typed into is one
     * of the launcher's own - the search on the Start screen, a folder being named, the
     * address bar - the application that has to produce the answer and the keyboard that is
     * blocked waiting for it are the same thread. The work to answer is posted to a handler
     * that cannot run until this call returns, and this call does not return until the answer
     * arrives. Nothing arrives. The system waits out its full two seconds and hands back
     * null, and it does that for every question, every time.
     *
     * That is not a slow field to be given up on after the fact, it is a field that was never
     * going to answer, and the only sound thing to do with it is not to ask. The questions all
     * come back unknown immediately, which is exactly the state they were already reaching two
     * seconds later - so nothing is lost here that used to work, only the waiting.
     *
     * The process is tested rather than the package, and the two are not the same claim. If
     * the keyboard is ever given a process of its own, the launcher's fields become as remote
     * as any other application's and answer as promptly, and a check written against the
     * package name would go on treating them as unanswerable for no reason at all. Asking
     * which process this is keeps the special case tied to the thing that actually causes it.
     */
    private val ownThread: Boolean by lazy { Application.getProcessName() == packageName }

    /**
     * Asks the application being typed into a question, and gives up on one that will not
     * answer.
     *
     * **Every read of the field's own text belongs in here.** An input method does not hold
     * the text it is typing into; the application does, and the only way to see any of it is
     * to ask across a process boundary and block until the answer comes back. The answering
     * happens on the application's main thread, which may be laying out a feed or decoding an
     * image, and the system waits a full two seconds on it before handing back nothing.
     *
     * Two seconds survived once is a stutter. Two seconds paid three times over in one
     * keystroke is not: the system allows a keyboard five to answer a touch before it decides
     * the keyboard is at fault and offers to close it, and a single backspace used to ask
     * three questions in a row. That is the crash this exists to prevent, and one press of
     * one key was all it took to reach it.
     *
     * So a slow answer is remembered. Once the field has taken [SLOW_ANSWER_MS] to say
     * anything, nothing is asked of it for [NOT_ASKING_MS] and every question here comes
     * straight back null. Callers then do whatever they do when the answer is unknown, which
     * for every one of them is the plain thing rather than the clever one: no word picked
     * back up by backspace, no automatic capital, no suggestions from text nobody can see.
     *
     * Typing does not come through here and must not. Writing to the field - the letters, the
     * deletions, the committed words - is one-way and never waits, so what the user types
     * stays exact however far behind the application has fallen. Only the keyboard's
     * cleverness is dropped, and only while there is nobody to be clever with.
     */
    private fun <T> ask(question: () -> T?): T? {
        // The field cannot answer, because answering is this thread's job and this thread is
        // the one that would be waiting. See [ownThread].
        if (ownThread && currentInputEditorInfo?.packageName == packageName) return null
        val asked = SystemClock.uptimeMillis()
        if (asked < notAskingUntil) return null
        return try {
            question()
        } catch (e: Exception) {
            // The field can be gone by the time it is asked, and this is the one process on
            // the phone that must never take text entry down with it.
            null
        } finally {
            val took = SystemClock.uptimeMillis() - asked
            if (took >= SLOW_ANSWER_MS) {
                // Longer every time, because a field that has just stalled twice running is
                // not one that is about to answer. See [quietFor].
                quietFor = if (quietFor <= 0L) NOT_ASKING_MS
                else minOf(quietFor * 2, NOT_ASKING_MAX_MS)
                notAskingUntil = SystemClock.uptimeMillis() + quietFor
            } else {
                // Answered, and promptly. Whatever was wrong is over, and the next stall - if
                // there ever is one - starts its count again from the short window.
                quietFor = 0L
            }
        }
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyRepeat(view: KeyView) {
        if (view.key.action == Action.BACKSPACE) backspace(repeating = true)
    }

    /** The tick that answers a hold is [KeyView]'s; only what the hold *does* is here. */
    override fun onKeyLongPress(view: KeyView) {
        // A hold that brings up a page to slide across wins over both of the others, and can:
        // the two keys it applies to offer no characters and open nothing.
        if (startSlide(view)) return
        // A hold that opens something wins over a hold that offers characters.
        view.key.holdAction?.let { held ->
            when (held) {
                Action.EMOJI -> showEmoji()
                Action.SETTINGS -> showSettings()
                else -> Unit
            }
            return
        }
        when (view.key.action) {
            // A second tap on shift locks it, and so does a hold - the phone offered both,
            // and a hold is the one that works when the first tap was a moment ago.
            Action.SHIFT -> setShift(ShiftState.LOCKED)
            // Backspace has its own answer to a hold: it starts running. Nothing to open.
            Action.BACKSPACE -> Unit
            // The languages, on the one key that already names the one in use. Nothing at
            // all when only one is turned on, which is what the key was told on the way down
            // - see [KeyView.languageSwitch] - so on most keyboards no hold is ever timed.
            Action.SPACE -> keyboard?.showLanguages(view)
            else -> keyboard?.showAlternates(view)
        }
    }

    /** Reaching along the row a hold opened, or across the page a slide brought up. */
    override fun onKeyDrag(view: KeyView, x: Float, y: Float) {
        if (slidingKeys) {
            keyboard?.moveSlide(view, x, y)
            return
        }
        // The space bar's hold opens the languages and nothing else, so there is no question
        // of which of the two this drag belongs to. Both axes, because that list is a column.
        if (view.key.action == Action.SPACE) {
            keyboard?.moveLanguages(view, x, y)
            return
        }
        keyboard?.moveAlternates(view, x)
    }

    /**
     * The finger has come off a key that was being held.
     *
     * Takes whatever the row was offering, if a row was open. Nothing otherwise - a held
     * backspace has already done its work, and a held shift has already locked.
     */
    override fun onKeyRelease(view: KeyView) {
        if (view.key.action == Action.BACKSPACE) {
            finishRepeating()
            return
        }
        if (slidingKeys) {
            finishSlide(view)
            return
        }
        if (view.key.action == Action.SPACE) {
            val picked = keyboard?.takeLanguage()
            keyboard?.hideLanguages()
            // Whatever the finger was over, which is the language already in use unless it
            // was moved up onto the list - so a hold on the space bar and a straight lift
            // changes nothing at all. [switchLanguage] is what decides that; it is the same
            // question as picking the current language deliberately, and has one answer.
            if (picked != null) switchLanguage(picked)
            return
        }
        val chosen = keyboard?.takeAlternate()
        keyboard?.hideAlternates()
        if (chosen != null) {
            // An alternate spends a one-shot shift the same way a letter does: it is the
            // character the user was after, and the next one should not still be capital.
            if (shiftState == ShiftState.ONCE) setShift(ShiftState.OFF)
            // Through [type], not straight to the field. An accent belongs to the word being
            // typed; a symbol ends it. Committing either one directly would replace the word.
            type(chosen.toString())
        }
    }

    /**
     * The keys that change the page do it now, while the finger is still down.
     *
     * Everything else waits for the tap, because a letter must not be typed by a press that
     * turns out to be a hold - but a page change is not a character and nothing is committed
     * by it, so there is nothing to take back if the hold fires afterwards. What it buys is
     * that the new keys are already up and already laid out before the next finger arrives,
     * which during fast typing is a few milliseconds later and sometimes sooner than the
     * release of this one.
     */
    override fun onKeyPress(view: KeyView) {
        // Read before the page changes below, because `&123` changes it on the way *down* and
        // a slide that starts on this press has to put back what was here before it. Cleared
        // for every other key, so a slide can never begin on a stale one.
        pageBeforeSlide = if (view.slides()) {
            keyboard?.let { Page(shiftState, it.currentLayout()) }
        } else {
            null
        }
        when (view.key.action) {
            Action.SYMBOLS -> toggleSymbols()
            Action.SYMBOLS_PAGE -> flipSymbolPage()
            Action.LETTERS -> showLetters()
            else -> Unit
        }
    }

    override fun onKeyUp(view: KeyView) {
        if (routeToEmojiSearch(view)) return
        val key = view.key
        when (key.action) {
            null -> commitLetter(view)
            Action.SPACE -> space()
            Action.BACKSPACE -> backspace()
            Action.ENTER -> enter()
            Action.SHIFT -> tapShift()
            // Already done on the way down - see [onKeyPress]. Doing it again here would
            // switch straight back.
            Action.SYMBOLS, Action.SYMBOLS_PAGE, Action.LETTERS -> Unit
            Action.EMOJI -> showEmoji()
            // Both of these are holds rather than taps - they are reached through
            // Key.holdAction above - so a plain press on the key that carries them does
            // whatever that key normally does and never lands here.
            Action.SETTINGS -> Unit
            // And this one is on no layout at all: it is the emoji panel's own key, answered
            // by the panel. Listed so that adding a key here is a compile error rather than a
            // key that silently does nothing.
            Action.GIF -> Unit
        }
    }

    /**
     * The finger is dragging the caret along the line: on the space bar, or on the joystick.
     *
     * Moved with `setSelection` and **not** with `KEYCODE_DPAD_LEFT`/`RIGHT`, which is the
     * obvious way to do it and is a trap. A DPAD press is a caret move only while there is
     * somewhere for the caret to go; at the ends of the text it reverts to what it really is,
     * a focus movement, and focus leaving the text box takes the keyboard down with it. So
     * the gesture worked until you slid past the end of what you had written - which is
     * exactly when a long drag runs off the end of the space bar too, and looks for all the
     * world like the keyboard closing because your finger left the key.
     *
     * Naming a position instead means naming one that exists, so the move is clamped: never
     * before the start, and never past what the field says is actually there. Running out of
     * text now stops the caret, which is what it should have done all along.
     *
     * What the field says, though, and not what it fails to say. A field that is not
     * answering questions has no length, and treating that as a length of nothing is what
     * stops the caret dead the moment anything goes quiet - see the middle of this for what
     * is done instead.
     *
     * The word in progress is let go first, and **without being corrected**. Moving the caret
     * is not finishing a word - it is going back to look at something - and having the
     * keyboard seize the moment to rewrite what you had just typed would be a nasty surprise
     * from a gesture that is supposed to be about looking rather than changing.
     */
    override fun onCursorSlide(view: View, steps: Int): Int {
        if (steps == 0) return 0
        val ic = currentInputConnection ?: return 0
        if (composer.isComposing) finishWord(appending = "", correcting = false)
        phantomSpace = false

        val moved: Int
        try {
            // From the near edge of any selection, so the first move out of a selected range
            // collapses it the way an arrow key would rather than jumping from its middle.
            val from = if (steps > 0) maxOf(selStart, selEnd) else minOf(selStart, selEnd)
            val target: Int
            if (steps < 0) {
                // Leftward has nothing to ask anybody: there is always a position zero.
                target = maxOf(0, from + steps)
            } else {
                // Only as far as there is text. Asking for what is there costs one small
                // round-trip and is the whole of the clamp - the field is the only thing that
                // knows how long its own contents are.
                val room = ask { ic.getTextAfterCursor(steps, 0) }?.length
                if (room != null) {
                    unanswered = 0
                    target = from + minOf(steps, room)
                } else if (unanswered >= SLIDE_SLACK) {
                    // Enough moves have gone unanswered to call it: this is the end of the
                    // text. The optimistic ones are taken back rather than left standing,
                    // because a local position past the end of the text poisons the moves
                    // *after* it as well - the field goes on ignoring positions that do not
                    // exist, so a finger pushed the other way would appear to do nothing
                    // until it had walked back over the overshoot.
                    selStart = reportedStart
                    selEnd = reportedEnd
                    return 0
                } else {
                    // Silence is **not the same answer as none**, and reading it as none is
                    // what left the caret unable to move right at all in a field that had
                    // stalled once: one slow answer stops the keyboard asking anything for
                    // three seconds - see [ask] - and every rightward step in that window
                    // came back "no room" while every leftward one worked. A caret that only
                    // goes one way is not a caret with a limit, it is a broken control.
                    //
                    // So the move is made anyway and the field is left to refuse it. A
                    // position past the end is ignored rather than clamped, which means the
                    // caret stays where it was and no report comes back - and that silence is
                    // the only signal there is, so what is counted here is how many moves
                    // have gone without one. [onUpdateSelection] clears the count the moment
                    // anything actually moves, so it can only ever build up against a wall.
                    unanswered++
                    target = from + steps
                }
            }
            if (target == selStart && target == selEnd) return 0
            ic.setSelection(target, target)
            moved = target - from
            // Locally, because the field's own report of this move arrives after the next
            // step has already been asked for, and a slide that waited for it would crawl.
            selStart = target
            selEnd = target
        } catch (e: Exception) {
            return 0
        }
        updateAutoCaps()
        return moved
    }

    /**
     * The finger is pushing the joystick up or down, so the caret changes line.
     *
     * Worked out here rather than sent as `KEYCODE_DPAD_UP`/`DOWN`, for the reason given in
     * [onCursorSlide] - and the reason bites harder on this axis than on that one. A field
     * that has run out of lines treats those keys as what they really are, a request to move
     * the focus, and *most fields have exactly one line*: an arrow key up would walk out of
     * the search box and take the keyboard with it the first time anybody tried it. Naming a
     * position cannot do that. The worst it can do is nothing.
     *
     * Which is exactly what it does when the field will not answer, and that is the honest
     * cost of doing it this way: the line above is somewhere in the text, and the text belongs
     * to the application. A field that is not answering - see [ask] - has no lines as far as
     * this is concerned, and the dot stalls rather than guessing. It is the same bargain the
     * automatic capitals and the suggestions already strike with the same fields.
     *
     * The lines are the ones the *text* has, not the ones the field has drawn. A paragraph
     * wrapped across three rows of the screen is one line here, so pushing up from the middle
     * of it goes to the line above the whole paragraph. That is a real difference from what a
     * hardware arrow key does and it is not a corner cut: where the wrapping falls is a fact
     * about a layout this side of the process boundary has never seen, and a guess at it would
     * put the caret somewhere nobody could have predicted. Hard line endings are the part that
     * is genuinely known, so they are the part that is offered.
     */
    override fun onCursorLines(view: View, lines: Int): Int {
        if (lines == 0) return 0
        val ic = currentInputConnection ?: return 0
        if (composer.isComposing) finishWord(appending = "", correcting = false)
        phantomSpace = false

        val head = minOf(selStart, selEnd)
        val tail = maxOf(selStart, selEnd)
        // Back to the start of the line the caret is on, which is what says which column it is
        // in - and, going up, holds the line it is moving to as well.
        val before = ask { ic.getTextBeforeCursor(LINE_LOOK, 0) }?.toString() ?: return 0
        val lineStart = before.lastIndexOf('\n') + 1

        // Which column to aim for. Remembered from the last vertical move rather than measured
        // afresh every time, because measuring afresh is what makes a caret walk diagonally
        // down a page: a long line, a short one and a long one again should end where it
        // started, and it only does if the short line is somewhere the caret passed through
        // rather than somewhere it decided to be.
        val column = if (columnAt == head) caretColumn else before.length - lineStart

        val target: Int
        val moved: Int
        if (lines < 0) {
            // Up, where the line to land on is in the text before the caret and so is the end
            // of it - so the one question already asked answers the whole move.
            var at = lineStart
            var left = -lines
            while (left > 0 && at > 0) {
                // Searching from two back rather than one, so the newline that ends the line
                // above is stepped over instead of being found again. A blank line is one
                // character long and this walks over it correctly, which is the case worth
                // checking.
                at = before.lastIndexOf('\n', at - 2) + 1
                left--
            }
            // Nowhere to go: the top of the text, or the top of as much of it as can be seen.
            if (at == lineStart) return 0
            // The line ends at the newline that closes it, and the caret goes no further along
            // it than that however long a column it was keeping.
            val ends = before.indexOf('\n', at)
            target = head - before.length + minOf(at + column, ends)
            // Asked for, less however many there turned out not to be. Both are negative
            // going up, which is why this reads as an addition.
            moved = lines + left
        } else {
            // Down, which needs the other half of the text and cannot borrow anything from the
            // question above: the line below the caret is entirely in what comes after it.
            val after = ask { ic.getTextAfterCursor(LINE_LOOK, 0) }?.toString() ?: return 0
            var at = after.indexOf('\n')
            if (at < 0) return 0
            at++
            var left = lines - 1
            while (left > 0) {
                val next = after.indexOf('\n', at)
                if (next < 0) break
                at = next + 1
                left--
            }
            val next = after.indexOf('\n', at)
            val ends = if (next < 0) after.length else next
            target = tail + minOf(at + column, ends)
            moved = lines - left
        }

        if (target == selStart && target == selEnd) return 0
        try {
            ic.setSelection(target, target)
        } catch (e: Exception) {
            return 0
        }
        selStart = target
        selEnd = target
        // Kept against the position it was worked out for, so that anything else which moves
        // the caret - a letter typed, a tap in the text, a push sideways - leaves it stale and
        // it is measured again. Nothing anywhere has to remember to clear it.
        caretColumn = column
        columnAt = target
        updateAutoCaps()
        return moved
    }

    private fun commitLetter(view: KeyView) {
        // What the key produces is read *before* shift is spent, and the order is the whole
        // of it: turning a one-shot off flips every key on the board back to lowercase, and
        // this key is one of them. Asking it afterwards asks a key that has already changed
        // its mind, so a single tap on shift capitalised nothing at all.
        val text = view.output()
        // A one-shot shift is spent by the letter it capitalised. A locked one is not.
        if (shiftState == ShiftState.ONCE) setShift(ShiftState.OFF)
        type(text)
    }

    /**
     * Puts [text] into the field, as part of the word in progress or as the end of it.
     *
     * **Everything the user types goes through here**, and it must, because of how composing
     * text works: `commitText` does not append next to a composing word, it *replaces* it. So
     * a character committed directly while a word was in progress deleted that word - typing
     * `bi sakal` and then holding for a `?` left `bi ?`, with `sakal` gone, because the `?`
     * landed on the composing region rather than after it.
     *
     * Letters extend the word. Anything else ends it, and is committed together with it in
     * one operation. A field that wants no suggestions - a password, a code - never composes
     * at all, so nothing provisional is left in it and nothing is learned from it.
     */
    private fun type(text: String) {
        takeBackPhantomSpace(text)
        if (privateField || !composer.extendsWord(text)) {
            finishWord(appending = text)
            return
        }
        composer.append(text)
        showComposing()
    }

    /**
     * Puts the word in progress into the field as *composing* text, and offers suggestions.
     *
     * Composing rather than committed is what makes a correction one operation instead of ten:
     * the app is told this much is provisional, underlines it, and lets it be replaced whole.
     */
    private fun showComposing() {
        val ic = currentInputConnection
        try {
            ic?.setComposingText(composer.typed, 1)
        } catch (e: Exception) {
            // The field can go away between the finger landing and the key being read.
        }
        refreshCandidates()
    }

    /**
     * Works out what to offer for the word in progress.
     *
     * The literal text is always first. That is what makes an automatic correction safe to
     * make at all: whatever the keyboard decides, what the user actually typed is one tap
     * away, and tapping it teaches the keyboard that it was a word.
     */
    private fun refreshCandidates() {
        val bar = host?.bar ?: return
        val typed = composer.typed
        if (typed.isEmpty()) {
            // Nothing typed: offer what tends to follow the last word. The shipped table has
            // an opinion from the first keystroke of a fresh install, and what has been
            // learned is merged over the top of it and wins where the two disagree.
            val engine = suggester()
            val next = when {
                previousWord != null -> engine?.following(previousWord)
                // No previous word means one of two quite different things, and only the
                // field can say which. See [atSentenceStart].
                atSentenceStart() -> engine?.starting()
                else -> null
            }
            val words = next?.map { it.word }.orEmpty().take(BAR_SLOTS)
            offer(words)
            bar.setWords(words, emphasised = -1)
            return
        }

        // Anything this word is the name of. Worked out here rather than with the words
        // below, because it is a hash lookup rather than a search and there is no reason for
        // it to wait behind one - see [emojiFor].
        val emoji = emojiFor(typed)

        // The literal goes up immediately, before anything has been searched for. The bar is
        // never empty and never lags the keys, whatever the dictionary is busy doing.
        offer(listOf(typed) + emoji, emoji.size)
        offeredFor = typed
        bar.setWords(offered, emphasised = -1, emoji = emoji.size)

        val engine = suggester() ?: run {
            // Not ready yet. The bar already shows what was typed; this asks for the
            // dictionary and comes back when it has one.
            warmSuggester()
            return
        }
        val previous = previousWord
        val generation = ++searchGeneration
        searches.execute {
            // Overtaken while it sat in the queue. Typing four letters quickly used to mean
            // four searches run one after another, with the one whose answer anybody wanted
            // waiting behind three that had already been superseded - so the suggestions for
            // the fourth letter arrived three searches late. Only the newest is worth doing.
            if (generation != searchGeneration) return@execute
            val suggestions = try {
                // And again, from inside. Being overtaken *while* searching is the ordinary
                // case during a fast run of keys, not the exceptional one - a search takes a
                // few milliseconds and a thumb can beat that - and a search that runs to the
                // end anyway is a few milliseconds of a core spent on a word the user has
                // already finished. The keystroke that overtook it is waiting behind exactly
                // that work.
                engine.candidates(typed, previous) { generation != searchGeneration }
            } catch (e: Exception) {
                emptyList()
            }
            onMain.post {
                // Typed on since this was asked for, so the answer is about the wrong word.
                if (generation != searchGeneration) return@post
                val current = host?.bar ?: return@post

                // The literal first, then the best guesses in order, up to what the bar
                // will hold. The order is the display order: the leftmost word is always
                // exactly what was typed, so rejecting a correction is the same movement
                // every time, and the keyboard's own best guess is the one beside it. The
                // rest run off to the right and are reached with a flick.
                val words = ArrayList<String>(BAR_SLOTS)
                words.add(typed)
                // Straight after the literal, which is the only place they are worth
                // putting. Somebody who has typed `crown` in full has finished the word -
                // there is not much a completion can offer them - and an emoji tucked in
                // behind three spellings of what they already wrote is one they will never
                // see. It is also a far stronger signal than a completion: the word they
                // typed *is* the name of the thing.
                words.addAll(emoji)
                for (candidate in suggestions) {
                    if (words.size >= BAR_SLOTS) break
                    words.add(candidate.word)
                }
                offer(words, emoji.size)
                offeredFor = typed

                // Which word a space would take, marked before it is taken rather than
                // discovered afterwards. Only ever a *correction*: marking the literal when
                // nothing is going to happen put the accent on what the user had just typed,
                // which reads as the keyboard objecting to it.
                //
                // [correctionIndex] rather than a literal 1: with emoji on the bar the
                // keyboard's best word is no longer the second thing on it, and a space that
                // took `offered[1]` would insert an emoji nobody asked for.
                val willCorrect = autocorrect &&
                    composer.shouldAutocorrect(words.getOrNull(correctionIndex), typedIsAWord())
                current.setWords(
                    words,
                    emphasised = if (willCorrect) correctionIndex else -1,
                    emoji = emoji.size
                )
            }
        }
    }

    /**
     * The emoji list, once it has been read, or null while it is still being read.
     *
     * `@Volatile` and read straight off the keystroke path, which is why it is a field rather
     * than a call to [EmojiData.load]: that opens an asset and then asks the font whether it
     * can draw each of three and a half thousand glyphs, which is cheap once and is exactly
     * the sort of thing that must never happen under a finger. Null until it is ready, and the
     * bar simply offers no emoji until then - a keyboard that stalled on its first keystroke
     * to load a picture list would be a worse trade than a few seconds without pictures.
     */
    @Volatile
    private var emojiNames: EmojiData? = null

    /**
     * Reads the emoji list in the background, once.
     *
     * On [loads], the same thread the dictionaries are read on, and for the same reason:
     * both are a file and a few thousand small operations, neither belongs on the main thread
     * and neither belongs in front of a keystroke's search. Sharing one thread with the
     * dictionary also means the two do not compete - they happen one after the other, once,
     * while nobody is typing yet.
     */
    private fun warmEmojiNames() {
        if (emojiNames != null) return
        loads.execute {
            if (emojiNames != null) return@execute
            emojiNames = try {
                EmojiData.load(this).also {
                    // And its indices, here, rather than under the first keystroke that asks
                    // for one. See [EmojiData.warm]: the lookup the bar makes reads through a
                    // lazy, and a lazy is built by whoever asks first.
                    it.warm()
                }
            } catch (e: Exception) {
                // No emoji suggestions. Everything else about the keyboard still works.
                null
            }
        }
    }

    /**
     * The emoji [typed] is the name of, if it is the name of any.
     *
     * Nothing at all in a password field, which is the same rule the word suggestions follow
     * and for a stronger reason: this is a lookup of what somebody typed against a list, and a
     * field that has asked to be forgotten should not be having its contents looked up in
     * anything.
     */
    private fun emojiFor(typed: String): List<String> {
        if (privateField) return emptyList()
        val data = emojiNames ?: return emptyList()
        return data.headed(typed, EMOJI_SLOTS).map { it.glyph }
    }

    /**
     * An emoji was taken from the bar.
     *
     * The typed word goes: it was how the emoji was asked for, not something to keep - typing
     * `crown` and tapping 👑 should leave 👑, not `crown 👑`. And nothing is learned from it.
     * The word was spelled correctly or it would not have matched, so there is nothing for the
     * dictionary to gain, and the emoji itself must never enter it: a glyph learned as a word
     * would come back as a *correction* for something spelled a little like it.
     *
     * A space follows, exactly as it does for a word taken from the bar, so the sentence
     * carries on without one having to be typed - and the phantom-space rule takes it back
     * again if what comes next is a full stop. [previousWord] is cleared rather than set,
     * because an emoji is not a word to predict the next one from.
     */
    private fun takeEmojiSuggestion(glyph: String) {
        // `commit` replaces whatever is composing, which is the word that was typed to find
        // this - so the substitution and the space are one operation on the field.
        composer.reset()
        commit("$glyph ")
        phantomSpace = true
        previousWord = null
        offer(emptyList())
        offeredFor = ""
        refreshCandidates()
    }

    /**
     * Whether the caret is at the start of a sentence rather than merely between words.
     *
     * [previousWord] is null in two situations that look identical from here and are not. One
     * is a fresh field, or the moment after a full stop: there is nothing before the caret
     * because a sentence has not started yet, and the openers of the language are exactly
     * what to offer. The other is somebody tapping into the middle of a paragraph they wrote
     * yesterday, where the keyboard has simply lost track - and answering that with `i`, `you`
     * and `the` is the keyboard talking over them about a sentence it cannot see.
     *
     * So the field is asked. Two characters, on a path that runs when the bar has nothing to
     * show anyway - not on every keystroke, which is where round trips have to be counted.
     */
    private fun atSentenceStart(): Boolean {
        // No answer is not a reason to guess that a sentence is starting. An empty answer is:
        // that is a field with nothing in it, which is where a sentence begins.
        val before = ask { currentInputConnection?.getTextBeforeCursor(2, 0) } ?: return false
        return when {
            before.isEmpty() -> true
            // A sentence that has ended, and the space after it.
            before.length == 2 && before[1] == ' ' && before[0] in SENTENCE_END -> true
            before.last() == '\n' -> true
            else -> false
        }
    }

    /** Whether what has been typed is itself a word, which stops it being corrected away. */
    private fun typedIsAWord(): Boolean {
        val dictionary = suggester() ?: return false
        return dictionary.knows(composer.typed)
    }

    /**
     * Whether the space at the cursor is one the keyboard put there rather than one the user
     * pressed.
     *
     * Taking a suggestion appends a space, because the overwhelmingly common next thing is
     * another word. But the next thing is sometimes a full stop, and `hello !` is not what
     * anybody meant - the space was the keyboard guessing, and a guess should give way to
     * what was actually typed. So the space is remembered as provisional, and the punctuation
     * that attaches to a word takes it back. See [ATTACHING].
     */
    private var phantomSpace = false

    /**
     * Where the caret is, as last reported by the field.
     *
     * An input method is not told the contents of the box it is typing into and cannot ask
     * where the cursor is; it is *told*, through [onUpdateSelection], and this is that. Kept
     * because sliding the space bar has to name an absolute position to move the caret to,
     * and because it is then updated locally as the slide runs - the field's own report of
     * each move arrives too late to base the next one on.
     */
    private var selStart = 0
    private var selEnd = 0

    /**
     * And where the field itself last said it was, which is not always the same thing.
     *
     * The two part company while a slide is running, because [onCursorSlide] moves the local
     * pair ahead of the reports rather than waiting for them. This is the pair that is only
     * ever written by the field, and it is what the local one is put back to when a run of
     * moves turns out to have been made against the end of the text.
     */
    private var reportedStart = 0
    private var reportedEnd = 0

    /**
     * How many caret moves have been made without the field reporting any of them.
     *
     * Only ever counts up while the field is not answering questions, because that is the only
     * time a move is made without knowing there is room for it. See [onCursorSlide].
     */
    private var unanswered = 0

    /**
     * Which column an up-and-down push is aiming for, and where the caret was when it decided.
     *
     * Two numbers rather than one because the second is what makes the first expire: the
     * column is only meaningful while the caret is still where the vertical move left it, and
     * comparing against that position means everything else which moves the caret invalidates
     * it without having to know about it. See [onCursorLines].
     */
    private var caretColumn = 0
    private var columnAt = -1

    /** A suggestion was tapped. */
    private fun takeSuggestion(index: Int) {
        val word = offered.getOrNull(index) ?: return
        if (isEmojiSlot(index)) {
            takeEmojiSuggestion(word)
            return
        }
        if (composer.isComposing) {
            // Tapping the literal is how a correction is refused, and the honest reading of
            // that is "this is a word" - so it is learned, and stops being corrected from
            // then on. Better than remembering a grudge: it makes the keyboard right.
            composer.replaceWith(word)
            finishWord(appending = " ")
            phantomSpace = true
        } else {
            // A next-word prediction, with nothing being composed.
            //
            // Capitalised if shift is up, because that is what would have happened had the
            // word been typed rather than tapped. The predictions offered at the start of a
            // sentence are the openers of the language - `i`, `you`, `the` - and the field
            // has usually just asked for a capital, so without this the one place the bar is
            // most useful is the one place it inserts something visibly wrong.
            val text = when (shiftState) {
                ShiftState.LOCKED -> word.uppercase()
                ShiftState.ONCE -> word.replaceFirstChar { it.uppercase() }
                ShiftState.OFF -> word
            }
            // A one-shot shift is spent by the word it capitalised, exactly as a letter key
            // spends it. A locked one is not.
            if (shiftState == ShiftState.ONCE) setShift(ShiftState.OFF)
            // The space is unconditional, which it was not before. It did not need to be:
            // the bar could only offer a prediction when there *was* a previous word, so the
            // other branch was unreachable. Sentence starters reach it, and a starter taken
            // without a space behind it means the next word is typed straight onto it.
            commit("$text ")
            phantomSpace = true
            previousWord = text
            learned?.learn(text)
            refreshCandidates()
        }
    }

    /**
     * A suggestion was dragged to the bin.
     *
     * What "forget" means depends on which of the three things the bar was showing, and the
     * distinction is the whole of getting this right:
     *
     *  - **A prediction** - nothing typed, a word offered because of the one before it. The
     *    complaint is about the pairing, so the pairing goes. `see` should stop being
     *    followed by `ya`; `ya` itself is none of the keyboard's business.
     *  - **A completion or correction** - something typed, and this is one of the words
     *    offered for it. The complaint is about the word, so the word goes.
     *  - **The literal text**, which is never a suggestion. Nothing to forget: it is what the
     *    user's own fingers just did, and the bar says so rather than doing nothing.
     *
     * A word the shipped list has never heard of is then taken back entirely whichever of
     * those it was. It exists only because it was typed once, and if that once was a mistake
     * then leaving it means meeting it again tomorrow, spelled out of its own first letters.
     */
    private fun forgetSuggestion(index: Int) {
        val word = offered.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return
        val dictionary = learned ?: return

        if (composer.isComposing && index == 0) {
            say("that is what you typed")
            return
        }

        val after = if (composer.isComposing) null else previousWord
        val known = dictionary.knowsAnythingAbout(word, after)
        if (after != null) dictionary.forgetPair(after, word) else dictionary.forgetWord(word)
        // Learned-only, so it goes altogether. See the note above.
        if (suggester()?.isShipped(word) != true) dictionary.forgetWord(word)
        dictionary.flush()

        // Said either way, because "nothing happened" and "nothing was there to happen" look
        // identical from the outside and only one of them is worth wondering about. The
        // second wording is the honest one when the keyboard had learned nothing: the word
        // still stops being offered, but it was never something this person taught it.
        say(if (known) "$word forgotten" else "$word will not be suggested")
    }

    /** Puts a line on the bar for a moment, then gives it back to the suggestions. */
    private fun say(message: String) {
        host?.bar?.setMessage(message)
        onMain.postDelayed({ host?.bar?.setMessage(null); refreshCandidates() }, MESSAGE_MS)
    }

    /**
     * Backspacing into a word that was already finished picks it back up.
     *
     * A word ends when you press space, and the keyboard stops thinking about it - which is
     * right until you come back and delete the end of it, at which point you are plainly
     * typing that word again and the keyboard is the only one in the room who does not think
     * so. Every suggestion, every correction and the whole dictionary go quiet, over a word
     * that is half-typed in front of it. So the word comes out of the field and back into the
     * composer, one character shorter, exactly as though it had never been finished.
     *
     * Three conditions, and each of them is a way this goes wrong. Two of them are the
     * caller's, because they decide whether the question behind [around] is asked at all -
     * not while repeating, where twenty-five blocking round-trips a second is what froze the
     * phone once already, and not with a selection, which backspace deletes whole. The third
     * is here:
     * - **Not mid-word.** Composing text has to cover the whole word. If the cursor is inside
     *   one, recomposing the part in front of it would leave the rest outside the region, and
     *   taking a suggestion would then insert it into the middle of the word.
     *
     * @param around what [backspace] already asked for - the text either side of the caret.
     * @return true when the word was resumed and the caller should do nothing further.
     */
    private fun resumeWordBeforeCursor(
        ic: InputConnection,
        around: Pair<String, CharSequence?>
    ): Boolean {
        try {
            val before = around.first
            val after = around.second?.firstOrNull()
            if (after != null && composer.extendsWord(after.toString())) return false

            // Two ways the caret can be sitting at the end of a finished word: right after
            // its last letter, or after the space that ended it. The second is by far the
            // ordinary one, because pressing space is *how* a word gets finished - so the
            // first backspace after a word is almost always a backspace over a space, and a
            // version of this that only looked for a letter found nothing and did nothing.
            val endedBySpace = before.endsWith(" ")
            val word = (if (endedBySpace) before.dropLast(1) else before)
                .takeLastWhile { composer.extendsWord(it.toString()) }
            if (word.isEmpty()) return false

            // Deleting the space hands the word back whole - it is a word you have stopped
            // finishing, not one you are shortening. Deleting into the word itself takes the
            // letter. Either way, what is left becomes the word in progress again.
            val kept = if (endedBySpace) word else word.dropLast(1)

            // The word comes out of the field and goes back as composing text, rather than
            // being left where it is: `setComposingText` has no region to replace yet, so it
            // would insert a second copy alongside the first.
            val removing = word.length + if (endedBySpace) 1 else 0

            // As one edit, so the field never draws itself with the word missing.
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(removing, 0)
                composer.resume(kept)
                if (kept.isEmpty()) ic.finishComposingText() else ic.setComposingText(kept, 1)
            } finally {
                ic.endBatchEdit()
            }

            // The word before the one just picked up, so next-word prediction is about the
            // right pair. Without this `previousWord` is still this very word - it was set
            // when the space finished it - and the keyboard would offer what tends to follow
            // the word it is currently in the middle of.
            previousWord = before
                .dropLast(removing)
                .trimEnd()
                .takeLastWhile { composer.extendsWord(it.toString()) }
                .ifEmpty { null }

            refreshCandidates()
            updateAutoCaps()
            return true
        } catch (e: Exception) {
            // The field can go away mid-keystroke. Fall through to the ordinary delete.
            return false
        }
    }

    /**
     * The text on both sides of the caret, in as few round-trips as the platform allows.
     *
     * Every one of these is a blocking call into the *other* app's process, and that app can
     * be doing anything at all - so they are the one thing on the typing path whose cost is
     * not this keyboard's to control. Two blocking calls in the backspace repeat once froze
     * the phone; this is the same lesson applied where a single press pays it.
     *
     * `getSurroundingText` answers both halves at once and has been there since Android 12,
     * which is most phones. Below that it is two calls, as it always was.
     */
    private fun around(ic: InputConnection): Pair<String, CharSequence?>? = ask {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val text = ic.getSurroundingText(RESUME_LOOKBACK, 1, 0)
            if (text == null) {
                null
            } else {
                val at = text.selectionStart.coerceIn(0, text.text.length)
                text.text.substring(0, at) to text.text.subSequence(at, text.text.length)
            }
        } else {
            ic.getTextBeforeCursor(RESUME_LOOKBACK, 0)?.toString().orEmpty() to
                ic.getTextAfterCursor(1, 0)
        }
    }

    /**
     * Removes the space a suggestion added, when what follows it belongs against the word.
     *
     * The state is checked as well as the flag. A flag alone would be enough almost always,
     * and "almost" is doing real work here: the app can move the cursor underneath the
     * keyboard between the suggestion and the punctuation, and deleting a character that
     * turned out not to be our space would be a bug of exactly the kind that is impossible to
     * report. One round-trip to look, on a punctuation keystroke, is affordable - unlike in
     * the backspace repeat, where two of them once froze the phone.
     */
    private fun takeBackPhantomSpace(text: String) {
        val attaching = phantomSpace && text.length == 1 && text[0] in ATTACHING
        phantomSpace = false
        if (!attaching) return
        val ic = currentInputConnection ?: return
        // Through [ask], so a field that has stopped answering costs one stray space rather
        // than the wait that insisting on an answer would cost.
        if (ask { ic.getTextBeforeCursor(1, 0)?.toString() } != " ") return
        try {
            ic.deleteSurroundingText(1, 0)
        } catch (e: Exception) {
            // The field can go away mid-keystroke. The punctuation still gets typed.
        }
    }

    /**
     * Ends the word in progress, correcting it if the keyboard is confident.
     *
     * @param appending what triggered the end - the space or punctuation that follows the
     *   word - which is committed along with it in one operation so the field never briefly
     *   holds one without the other.
     * @param correcting false to leave the word exactly as typed. For the endings that are
     *   not the user finishing a word: sliding the caret away from one is going back to look
     *   at something, and a keyboard that rewrote the word on the way past would be answering
     *   a question nobody asked.
     */
    private fun finishWord(appending: String, correcting: Boolean = true) {
        val ic = currentInputConnection
        if (!composer.isComposing) {
            if (appending.isNotEmpty()) commit(appending)
            return
        }

        // Only correct on suggestions that are actually about this word. The search runs off
        // the main thread, so on a fast run of keys the last answer to arrive may be about a
        // shorter prefix - and correcting a word using a guess made about half of it is worse
        // than not correcting at all.
        val current = offeredFor == composer.typed
        val correction = if (correcting && autocorrect && current &&
            composer.shouldAutocorrect(offered.getOrNull(correctionIndex), typedIsAWord())
        ) {
            offered.getOrNull(correctionIndex)
        } else {
            null
        }
        val finished = composer.finish(correction)

        try {
            ic?.setComposingText(finished, 1)
            ic?.finishComposingText()
            if (appending.isNotEmpty()) ic?.commitText(appending, 1)
        } catch (e: Exception) {
            composer.reset()
            return
        }

        // A correction can be two words - `helloworld` becoming `hello world` - and what
        // gets learned and remembered has to be words, not the string that replaced them.
        // Learning `hello world` as one entry would offer it back as a single word forever.
        val words = finished.split(' ').filter { it.isNotBlank() }
        if (!privateField) {
            var before = previousWord
            for (word in words) {
                learned?.learn(word)
                before?.let { learned?.learnPair(it, word) }
                before = word
            }
        }
        previousWord = words.lastOrNull() ?: finished
        offer(emptyList())
        offeredFor = ""
        refreshCandidates()
    }

    /**
     * Puts text into the field, replacing anything currently composing.
     *
     * The raw operation, and the replacing is the part to be careful about: `commitText`
     * substitutes for the composing region rather than following it. Almost nothing should
     * call this directly - [type] is what handles a character the user has pressed. This is
     * for text that is meant to stand on its own, where nothing is composing by construction.
     */
    private fun commit(text: String) {
        val ic = currentInputConnection ?: return
        try {
            ic.commitText(text, 1)
        } catch (e: Exception) {
            // The field can go away between the finger landing and the key being read.
        }
        if (text != " ") lastSpace = 0L
    }

    /**
     * Space, and the double tap that makes a full stop.
     *
     * Two spaces in quick succession become a full stop and a space, which is what every
     * phone has done since well before the one this keyboard is drawn after.
     *
     * Only after a letter or a digit, so that a second space at the start of a line, or one
     * following punctuation that is already there, stays a space. Backspace currently just
     * deletes into the result; putting the two spaces back instead belongs with the rest of
     * the undo behaviour, once there is something tracking what the keyboard changed.
     */
    private fun space() {
        phantomSpace = false
        val now = SystemClock.uptimeMillis()
        val ic = currentInputConnection

        // A space is what ends a word, so the correction happens here - and it happens in the
        // same operation as the space itself, so the field never briefly shows one without
        // the other.
        if (composer.isComposing) {
            finishWord(appending = " ")
            lastSpace = now
            updateAutoCaps()
            return
        }

        if (ic != null && now - lastSpace < DOUBLE_SPACE_MS && endsWithLetter(ic)) {
            try {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
            } catch (e: Exception) {
                return
            }
            lastSpace = 0L
            updateAutoCaps()
            return
        }
        commit(" ")
        lastSpace = now
        updateAutoCaps()
        returnFromSymbols()
    }

    /**
     * A space ends the detour to the symbol page.
     *
     * The `&123` page is somewhere you go for one or two characters - a bracket, a currency
     * sign, an ampersand - and then carry on with the sentence. Staying there after a space
     * means every such trip ends with pressing `abc` as well, which is a keystroke spent on
     * telling the keyboard something it could already tell.
     */
    private fun returnFromSymbols() {
        val view = keyboard ?: return
        if (view.currentLayout().id.startsWith("symbols")) showLetters()
    }

    /** True when what is behind the cursor is a space with a letter before it. */
    private fun endsWithLetter(ic: InputConnection): Boolean {
        val before = ask { ic.getTextBeforeCursor(2, 0) } ?: return false
        return before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
    }

    /**
     * Backspace.
     *
     * @param repeating true while the key is being held down and firing many times a second.
     *
     * The distinction matters a great deal, and getting it wrong locked the phone up. Every
     * question put to the application being typed into is a **synchronous round trip** - the
     * keyboard asks and then blocks its own main thread until the answer comes back, or until
     * the system gives up waiting on the application two seconds later. Twenty-five times a
     * second that is a frozen phone, so a held backspace asks nothing and tells: it deletes,
     * and it defers the questions and the dictionary work until the finger comes off. See
     * [finishRepeating].
     *
     * A single press is not free either, which is the half that had to be learned twice. It
     * used to ask three questions in a row - what is around the caret, what is selected,
     * should the next letter be a capital - and three two-second waits is past the five
     * seconds the system allows a keyboard to answer a touch in before it decides the
     * keyboard has hung. Whether anything is selected is now read from the field's own last
     * report instead of asked for, and the two questions left go through [ask], which stops
     * asking a field that has stopped answering.
     */
    private fun backspace(repeating: Boolean = false) {
        phantomSpace = false
        val ic = currentInputConnection ?: return

        // Backspace straight after a correction means "no, I meant what I typed" - so it puts
        // the typed word back rather than nibbling a character off the keyboard's guess. A
        // keyboard that gets this wrong makes every unwanted correction into a fight.
        composer.takeUndo()?.let { (applied, original) ->
            try {
                ic.deleteSurroundingText(applied.length + 1, 0)
                ic.commitText("$original ", 1)
            } catch (e: Exception) {
                return
            }
            // Learned, because the user has now said twice that this is a word.
            if (!privateField) learned?.learn(original)
            previousWord = original
            refreshCandidates()
            return
        }

        // Inside the word being typed, the deletion is of composing text.
        if (composer.backspace()) {
            try {
                ic.setComposingText(composer.typed, 1)
                if (!composer.isComposing) ic.finishComposingText()
            } catch (e: Exception) {
                return
            }
            // Suggestions for every character of a word being torn down are suggestions
            // nobody reads; they are worked out once, when the tearing down stops.
            if (!repeating) refreshCandidates()
            return
        }

        // Nothing was being composed, so the cursor is in text the field already owns. One
        // question, and its answer is used twice: first to see whether the caret is sitting
        // at the end of a word, which is picked back up rather than having a character
        // quietly shaved off it (see [resumeWordBeforeCursor]), and failing that to see what
        // is actually behind the caret, which is what the deletion below needs to know.
        //
        // Not asked while repeating - that path stays the one-way one it was made into - and
        // not of a password field, which has no word to resume and should never be asked to
        // hand its contents back. A selection is deleted whole, so there is nothing to look
        // at there either; that is read from what the field last reported rather than asked
        // for, because [onUpdateSelection] is told about every selection there ever is.
        var before: String? = null
        if (!repeating && !privateField && selStart == selEnd) {
            val around = around(ic)
            if (around != null) {
                if (resumeWordBeforeCursor(ic, around)) return
                before = around.first
            }
        }

        try {
            if (repeating) {
                // One-way, and no questions. `deleteSurroundingText` is dispatched and
                // forgotten where a question waits for a reply.
                ic.deleteSurroundingText(1, 0)
            } else if (selStart != selEnd) {
                // Something is selected, and backspace takes the whole of it.
                ic.commitText("", 1)
            } else if (!before.isNullOrEmpty()) {
                // Deleted as text, not as a key event.
                //
                // A key event is the older way of saying this and it is not a reliable one:
                // a field that handles its own keys - and the newer ones built on Compose
                // often do - can drop a soft keyboard's KEYCODE_DEL on the floor. The
                // symptom is peculiar enough to be worth writing down, because it is what
                // gave this away: in such a field letters delete fine and a full stop cannot
                // be deleted at all, since letters are composing text and never go anywhere
                // near a key event - and *holding* backspace works, because the repeat above
                // has always deleted as text.
                //
                // A whole grapheme rather than a single `char`, because that is what the key
                // event would have taken. An emoji, a flag, a letter with a combining accent
                // on it are each one thing to the person pressing the key and two or more
                // chars underneath, and a keyboard that leaves half an emoji behind is worse
                // than one that cannot delete a comma.
                ic.deleteSurroundingText(lastGrapheme(before), 0)
            } else {
                // Nothing behind the caret to take, so the press is worth passing on as the
                // key itself: fields that watch for it - a search box that closes on an empty
                // backspace, a chip field that gives up a chip - only ever see the key. This
                // is also where a field that would not say what is behind the caret lands: a
                // password box, or one that has stopped answering.
                sendKey(ic, KeyEvent.KEYCODE_DEL)
            }
        } catch (e: Exception) {
            return
        }
        lastSpace = 0L
        if (!repeating) updateAutoCaps()
    }

    /** The catching up a held backspace put off: done once, when the finger comes off. */
    private fun finishRepeating() {
        refreshCandidates()
        updateAutoCaps()
    }

    private fun enter() {
        phantomSpace = false
        // Enter ends the word, but takes no correction with it: pressing send or search is a
        // commitment to what is on screen, and changing it at that moment would be the worst
        // possible time to be clever.
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }
        val info = currentInputEditorInfo
        // The same answer the key's own face was painted from. See [enterJob].
        val job = enterJob(info)
        val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val ic = currentInputConnection ?: return
        try {
            if (job.actionId != null) {
                // Nothing left composing when the text goes. An app that clears its box on
                // send does not tell the keyboard, so a region left open here is one the next
                // keystroke would be written into.
                ic.finishComposingText()
                ic.performEditorAction(job.actionId)
                // A sent message is the end of what was being said. The next word does not
                // follow the last one, so nothing should be predicted from it.
                composer.reset()
                previousWord = null
                offer(emptyList())
                offeredFor = ""
                host?.bar?.clear()
            } else if (multiLine) {
                // A new line put in as text rather than as a key event. In a box that takes
                // more than one line and has no action for the key, there is nothing else it
                // can mean,
                // and the key event is the unreliable way of saying it - the same fields
                // that drop a soft KEYCODE_DEL drop KEYCODE_ENTER, and then pressing return
                // in a chat box simply does nothing at all. See [backspace].
                ic.commitText("\n", 1)
            } else {
                // One line, and no action to perform on it. The key goes through as a key,
                // because whatever this field does with return is the field's business.
                sendKey(ic, KeyEvent.KEYCODE_ENTER)
            }
        } catch (e: Exception) {
            return
        }
        updateAutoCaps()
    }

    /**
     * A key, pressed and released, as though there were a keyboard there.
     *
     * How the event is built matters and the short constructor gets it wrong. `KeyEvent(
     * action, code)` carries no timestamps, claims device 0 - the built-in keyboard - and
     * sets no flags, and applications do look at all three: one that sees what appears to be
     * a hardware keypress is entitled to answer it differently from a soft one, and one that
     * does any filtering of its own on event time throws away an event stamped zero. So it
     * is built the way the platform's own keyboards build it: the virtual device, the two
     * flags that say a soft key sent this, and a real clock.
     *
     * Callers wrap this: the field can go away between the down and the up.
     */
    private fun sendKey(ic: InputConnection, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            ic.sendKeyEvent(
                KeyEvent(
                    now, now, action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
                )
            )
        }
    }

    /**
     * How many `char`s the last visible thing in [text] is made of.
     *
     * One press of backspace takes one *thing* off the end, and a thing is not a `char`: a
     * flag is two, an emoji with a skin tone on it is four, a family is a dozen or more, and
     * an `e` with a combining acute is two that look like one. Deleting a fixed number of
     * chars off the end of any of those leaves a stump on screen - half a flag, an emoji
     * with its modifier orphaned - which is why the platform's own key handling breaks text
     * this way rather than counting chars, and why anything deleting text by hand has to do
     * the same.
     *
     * [text] is only the tail of the field, so a boundary can in principle be misjudged at
     * its far end. It cannot here: what is looked at is the *last* break in a window
     * [RESUME_LOOKBACK] chars wide, and nothing that reads as one character is that long.
     */
    private fun lastGrapheme(text: String): Int = try {
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        val start = breaks.preceding(text.length)
        // A start of 0 is an answer, not a failure: it means the whole of what was looked at
        // reads as one character, which is what a field holding nothing but an emoji says.
        if (start == BreakIterator.DONE || start >= text.length) 1 else text.length - start
    } catch (e: Exception) {
        1
    }

    /**
     * Shift, tapped.
     *
     * One tap arms it for a single letter, a second tap soon after locks it on, and a tap
     * while it is on in either form turns it off. The double tap is measured rather than
     * counted so that two deliberate taps a second apart are two decisions rather than one
     * lock, which is the difference between the phone's shift and a fussy one.
     */
    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        val next = when {
            shiftState == ShiftState.ONCE && now - lastShiftTap < DOUBLE_TAP_MS -> ShiftState.LOCKED
            shiftState == ShiftState.OFF -> ShiftState.ONCE
            else -> ShiftState.OFF
        }
        lastShiftTap = now
        setShift(next)
    }

    private fun setShift(state: ShiftState) {
        shiftState = state
        keyboard?.setShiftState(
            on = state != ShiftState.OFF,
            locked = state == ShiftState.LOCKED
        )
    }

    // ---------------------------------------------------------------- dictation

    /** The recogniser in use, built on first use and rebuilt when the language changes. */
    private var voice: VoiceInput? = null

    /** Which language [voice] was built for, so a switch does not dictate in the wrong one. */
    private var voiceFor: String? = null

    /** The offline models: what has been downloaded, and fetching what has not. */
    private val voskModels by lazy { VoskModels(this) }

    /** Whether to dictate on the phone rather than through the platform. See the setting. */
    private var offlineVoice = true

    /** How much of the current dictation has been put in the field, so it can be replaced. */
    private var dictated = ""

    /**
     * What goes in front of the next dictated words - a space, or nothing.
     *
     * A recogniser settles a sentence at a time. Pause mid-thought and what has been said so
     * far is final and gets committed; the words after the pause arrive as a fresh hypothesis
     * that knows nothing about them, and composing text goes in at the caret - hard against
     * the last committed letter. Without this, a pause between two words reads as "twowords".
     */
    private var dictationLead = ""

    /**
     * The microphone, pressed.
     *
     * A toggle rather than a hold: dictating a sentence takes longer than anyone wants to
     * keep a thumb down for, and the button has to be able to say "still listening" - which
     * is what the accent on it means while this is running.
     */
    private fun toggleVoice() {
        val engine = voice
        if (engine != null && engine.isListening) {
            engine.stop()
            return
        }

        // An input method cannot ask for a permission itself. See [VoicePermissionActivity].
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(
                    Intent(this, VoicePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Nothing to be done, and not worth taking the keyboard down over.
            }
            return
        }

        // The word in progress is finished first, without a correction: what is dictated next
        // is a new thought, and leaving a half-typed word composing underneath it would mean
        // the first thing said replaced it.
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }

        dictated = ""
        dictationLead = leadForDictation()
        val listener = object : VoiceInput.Listener {
            override fun onPartial(text: String) = showDictation(text)

            override fun onFinal(text: String) {
                showDictation(text)
                // Committed, so that what was said becomes ordinary text rather than staying
                // provisional, and so a following word starts cleanly.
                try {
                    currentInputConnection?.finishComposingText()
                } catch (e: Exception) {
                    // The field can go away mid-sentence.
                }
                dictated = ""
                // Asked of the field rather than assumed to be a space: the committed words
                // are behind the caret now, and this is the one place that knows whether the
                // application moved it somewhere else in the meantime.
                dictationLead = leadForDictation()
                previousWord = text.substringAfterLast(' ').ifBlank { null }
            }

            override fun onStopped(error: String?) {
                host?.bar?.preparing = false
                host?.bar?.preparing = false
        host?.bar?.listening = false
                dictated = ""
                if (error != null) {
                    // Said on the bar rather than swallowed. A microphone that does nothing
                    // and explains nothing is the worst of the possible outcomes.
                    say(error)
                } else {
                    refreshCandidates()
                }
            }
        }

        host?.bar?.setMessage(null)
        startListening(listener)
    }

    /**
     * Chooses an engine and starts it, fetching the offline model if that is what is missing.
     *
     * The order is the whole policy. Vosk if it is wanted and ready; the model downloaded if
     * it is wanted and merely absent; and the platform recogniser when Vosk has nothing to
     * offer for this language, which is the case for about half of the ones that ship.
     */
    /**
     * Turns the microphone the accent once the engine is actually up.
     *
     * The engine is started first and this waits on it, rather than the other way round:
     * the point of the pause is to give the model somewhere to load, so the wait runs
     * alongside the work instead of before it.
     *
     * The ring shows for at least [VOICE_SETTLE_MS] whatever the engine says, because an
     * engine that is ready instantly still leaves a button that flickered - and if it
     * stopped during the wait, nothing is turned on at the end of it.
     */
    private fun settleVoiceButton(engine: VoiceInput) {
        val bar = host?.bar ?: return
        if (!engine.isListening) {
            bar.preparing = false
            bar.listening = false
            return
        }
        bar.preparing = true
        bar.postDelayed({
            bar.preparing = false
            bar.listening = engine.isListening
        }, VOICE_SETTLE_MS)
    }

    private fun startListening(listener: VoiceInput.Listener) {
        val code = language.language

        if (offlineVoice && voskModels.supports(code)) {
            if (voskModels.isDownloaded(code)) {
                val engine = engineFor(code, offline = true)
                engine.start(listener)
                settleVoiceButton(engine)
                return
            }
            fetchModelThenListen(code, listener)
            return
        }

        // Either the platform was asked for, or Vosk cannot help with this language.
        val engine = engineFor(code, offline = false)
        engine.start(listener)
        settleVoiceButton(engine)
    }

    /**
     * Downloads the model, saying so, and starts dictating when it arrives.
     *
     * Forty megabytes takes long enough that doing it silently would look like the microphone
     * being broken again, so the bar carries the progress. It happens once.
     */
    private fun fetchModelThenListen(code: String, listener: VoiceInput.Listener) {
        if (voskModels.isBusy) {
            host?.bar?.setMessage("still downloading the speech model")
            return
        }
        host?.bar?.setMessage("downloading speech model...")
        voskModels.fetch(
            language = code,
            onProgress = { percent -> host?.bar?.setMessage("downloading speech model $percent%") },
            onDone = { directory, error ->
                host?.bar?.setMessage(null)
                if (directory == null) {
                    // Falls back rather than giving up: no network is a reason to use the
                    // other engine, not a reason to have no dictation.
                    val engine = engineFor(code, offline = false)
                    engine.start(listener)
                    settleVoiceButton(engine)
                    if (error != null) host?.bar?.setMessage(error)
                    return@fetch
                }
                val engine = engineFor(code, offline = true)
                engine.start(listener)
                settleVoiceButton(engine)
            }
        )
    }

    /** The engine for this language, kept between uses and rebuilt when either changes. */
    private fun engineFor(code: String, offline: Boolean): VoiceInput {
        val wanted = if (offline) "vosk:$code" else "platform:$code"
        voice?.let { if (voiceFor == wanted) return it }
        voice?.destroy()
        val engine: VoiceInput =
            if (offline) VoskVoiceInput(this, voskModels, code) else PlatformVoiceInput(this)
        voice = engine
        voiceFor = wanted
        return engine
    }

    /**
     * Puts what has been heard so far into the field, as composing text.
     *
     * Composing rather than committed for the same reason a typed word is: a recogniser
     * revises what it thought it heard as the sentence goes on, and provisional text can be
     * replaced whole instead of being deleted a character at a time.
     */
    private fun showDictation(text: String) {
        dictated = text
        try {
            // The lead is part of the composing text, not committed ahead of it, so that a
            // revised hypothesis replaces the space along with the words it belonged to.
            currentInputConnection?.setComposingText(dictationLead + text, 1)
        } catch (e: Exception) {
            // The field can go away mid-sentence.
        }
    }

    /**
     * Whether the next dictated words need a space in front of them.
     *
     * Only the character behind the caret decides it. Nothing there, whitespace, or a bracket
     * something was about to be said inside of, and the words go straight in; anything else -
     * a letter, a full stop, the end of the last thing dictated - and they need separating.
     */
    private fun leadForDictation(): String {
        // Nothing said back means nothing to butt against.
        val before = ask { currentInputConnection?.getTextBeforeCursor(1, 0) }
        return if (before.isNullOrEmpty() || before[0].isWhitespace() || before[0] in OPENING) {
            ""
        } else {
            " "
        }
    }

    private fun stopVoice() {
        voice?.let { if (it.isListening) it.stop() }
        host?.bar?.preparing = false
        host?.bar?.listening = false
    }

    // ---------------------------------------------------------------- emoji

    /**
     * Swaps the keys for the emoji panel.
     *
     * The word in progress is finished first, without a correction and without a trailing
     * space: reaching for an emoji is the end of a word but not the end of a sentence, and a
     * space the user did not ask for would have to be deleted before the emoji went in.
     */
    private fun showEmoji() {
        val stack = host ?: return
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }
        val panel = emoji ?: EmojiPanel(this, palette).also { built ->
            built.onEmojiPicked = { glyph ->
                // Committed and left open. Emoji arrive in runs, and a panel that closed
                // after each one would have to be reopened for the next.
                commit(glyph)
                previousWord = null
            }
            built.onBackToLetters = { hideEmoji() }
            built.onBackspace = { backspace() }
            built.onSearchTapped = { startEmojiSearch() }
            built.onGifPicked = { sendGif(it) }
            emoji = built
        }
        // Always opens browsing, never in whatever search was left behind. The panel outlives
        // any one use of it - it is built once and kept - so without this, opening emoji after
        // having once searched for something reopened those results instead of the categories.
        searchingEmoji = false
        panel.query = ""
        panel.searchMode = false
        // And on the emoji rather than on whatever the last visit left open, for the same
        // reason: the emoji key was tapped, so the emoji are what it should show.
        panel.mode = EmojiPanel.Mode.EMOJI

        panel.applyPalette(palette)
        // The panel stands in the keyboard's place, so it is made of what the keyboard is
        // made of. Set here rather than in [applySettings] because the panel is built on
        // first use and may not exist when that runs.
        panel.theme = KeyboardAppearance.theme(themeManager)
        val keys = stack.keyboard
        panel.setMetrics(
            keys.unitWidth(), keys.keyHeight(), keys.gutter(),
            keys.bottomReserved(), keys.contentHeight()
        )
        stack.showPanel(panel, withKeys = false)
        // The bar belongs to the letters; the panel has its own control row.
        host?.bar?.clear()
    }

    /**
     * Opens the keyboard's settings, which are a page of their own.
     *
     * A whole screen rather than a panel down here - see [KeyboardSettingsActivity]. An input
     * method has no task of its own to start an activity in, so it needs `NEW_TASK`; and the
     * keyboard is dismissed first, because leaving it up over the page it just opened would
     * cover the thing the user went there to change.
     */
    private fun showSettings() {
        if (composer.isComposing) {
            composer.replaceWith(composer.typed)
            finishWord(appending = "")
        }
        requestHideSelf(0)
        try {
            startActivity(
                Intent(this, KeyboardSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Nothing to be done, and not worth taking the keyboard down over.
        }
    }

    private fun hideEmoji() {
        searchingEmoji = false
        emoji?.let {
            it.query = ""
            it.searchMode = false
            // Writes the recents and re-reads them, so the row is up to date the next time
            // the panel opens rather than shifting about while it is in use.
            it.onClosed()
        }
        host?.hidePanel()
        refreshCandidates()
    }

    /**
     * Puts a GIF into whatever is being typed into.
     *
     * Two paths, and both are needed. A field that says it takes pictures - a messaging app's
     * box, most of the time - is handed the file itself, through the app's own [FileProvider],
     * with a read grant that Android hands over with it and takes back afterwards. Everything
     * else, which is every plain text box on the phone, is handed the address: it is what the
     * GIF actually is, it pastes, and at the other end most things unfurl it into the picture.
     *
     * The address is also the fallback for both of the ways the first path can fail - the
     * fetch not completing, and the receiving app refusing the content after saying it would
     * take it - because a tap that produces nothing at all reads as the keyboard being broken.
     */
    private fun sendGif(gif: Gif) {
        val info = currentInputEditorInfo
        val accepts = info != null && EditorInfoCompat.getContentMimeTypes(info).any {
            ClipDescription.compareMimeTypes(GIF_MIME, it)
        }
        if (!accepts) {
            commit(gif.sendUrl)
            previousWord = null
            return
        }
        GifSearch.download(this, gif) { file ->
            if (file == null || !commitGif(file)) commit(gif.sendUrl)
            previousWord = null
        }
    }

    /** @return whether the field took it. */
    private fun commitGif(file: File): Boolean {
        val ic = currentInputConnection ?: return false
        val info = currentInputEditorInfo ?: return false
        return try {
            InputConnectionCompat.commitContent(
                ic,
                info,
                InputContentInfoCompat(
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file),
                    ClipDescription(file.name, arrayOf(GIF_MIME)),
                    null
                ),
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (e: Exception) {
            // A provider that cannot serve the file, or a field that has gone away since the
            // GIF was tapped. Either way the caller falls back to the address.
            false
        }
    }

    /**
     * The search bar was tapped: from here, letters build a query rather than a word.
     *
     * Whatever is already in the box stays in it. The tap used to clear it, which made the
     * box the one piece of text on the keyboard that a touch destroyed - and the query is
     * exactly what somebody is looking at when they reach for it, either to carry on typing
     * it or because a tap on the results scrolled the search out from under them. Backspace
     * takes it back a letter at a time, the way it does everywhere else; see
     * [routeToEmojiSearch]. Opening the panel afresh is what starts an empty search, and
     * [showEmoji] already clears it for that.
     */
    private fun startEmojiSearch() {
        val panel = emoji ?: return
        searchingEmoji = true
        // The keys come back underneath, and the panel shrinks to leave room for them. An
        // input method cannot type into a text box of its own, so without this the search bar
        // is a picture of a search bar.
        panel.searchMode = true
        val keys = host?.keyboard ?: return
        panel.setMetrics(
            keys.unitWidth(), keys.keyHeight(), keys.gutter(),
            keys.bottomReserved(), keys.contentHeight()
        )
        host?.showPanel(panel, withKeys = true)
    }

    /**
     * A key was pressed while the emoji panel's search box was active.
     *
     * @return true when the keystroke was consumed by the search rather than the text field.
     */
    private fun routeToEmojiSearch(view: KeyView): Boolean {
        if (!searchingEmoji) return false
        val panel = emoji ?: return false
        when (view.key.action) {
            null -> panel.query += view.output()
            Action.SPACE -> panel.query += " "
            Action.BACKSPACE -> {
                if (panel.query.isEmpty()) {
                    // Backspacing out of an empty search is how one leaves it, rather than it
                    // being a dead key with nothing to delete. The panel goes back to its
                    // full height and the keys go away with it.
                    searchingEmoji = false
                    panel.searchMode = false
                    host?.showPanel(panel, withKeys = false)
                } else {
                    panel.query = panel.query.dropLast(1)
                }
            }
            // Enter, or anything that changes the page, ends the search rather than being
            // swallowed by it.
            else -> {
                searchingEmoji = false
                return false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- pages

    private fun toggleSymbols() {
        val view = keyboard ?: return
        if (view.currentLayout().id.startsWith("symbols")) showLetters() else showSymbols()
    }

    /**
     * Puts a page up, with the `&123` key saying what it now does.
     *
     * The four calls below are the same three lines with a different layout in the middle,
     * and one of them is reached from the slide, which has a layout rather than a direction:
     * it is putting back whichever of the four was showing before the finger landed, and
     * there is no `showWhateverWasThereBefore`.
     */
    private fun showPage(next: KeyboardLayout) {
        val view = keyboard ?: return
        view.setLayout(next)
        view.setSymbolsLabel(
            if (next.id.startsWith("symbols")) Layouts.LETTERS_LABEL else Layouts.SYMBOLS_LABEL
        )
        refreshEnterKey()
    }

    private fun showSymbols() = showPage(Layouts.SYMBOLS_1)

    private fun flipSymbolPage() {
        val view = keyboard ?: return
        showPage(
            if (view.currentLayout().id == Layouts.SYMBOLS_1.id) Layouts.SYMBOLS_2
            else Layouts.SYMBOLS_1
        )
    }

    private fun showLetters() {
        showPage(language)
        // The grid was rebuilt, so the shift state is sitting on the old set of key views and
        // has to be put back onto the new one.
        setShift(shiftState)
    }

    // ---------------------------------------------------------------- sliding

    /**
     * What the keyboard was before the key under the finger was pressed.
     *
     * Filled on every press of shift or `&123` - see [onKeyPress] - and read only if that
     * press turns into a hold. Both halves are recorded whichever of the two keys it was,
     * because at the moment of the press it is not yet known which gesture this is going to
     * be; [finishSlide] puts back the half that its own key had changed.
     */
    private class Page(val shift: ShiftState, val layout: KeyboardLayout)

    private var pageBeforeSlide: Page? = null

    /** Whether a slide is actually running: the hold has fired and the page is up. */
    private var slidingKeys = false

    /**
     * A hold on shift or `&123`, which turns the page it opens into something to slide across.
     *
     * The whole of the gesture is delivered to the key it started on - Android sends a stream
     * of touches to whichever view claimed the press, wherever the finger goes afterwards -
     * which is what makes this possible at all: the keys being slid across are never touched,
     * so the key that *was* touched passes the movement on and the grid works out what is
     * under it. See `KeyboardView.moveSlide`.
     *
     * @return whether the hold was taken, so the caller can leave the rest of it alone.
     */
    private fun startSlide(view: KeyView): Boolean {
        if (!view.slides()) return false
        if (pageBeforeSlide == null) return false
        when (view.key.action) {
            // Shift has done nothing yet - a tap on it is read on the way up - so the hold is
            // what brings the capitals up. Locked rather than one-shot, because the letter
            // this slide types must not spend a shift the slide put on itself; what happens
            // to shift afterwards is [finishSlide]'s to decide, and it decides by putting
            // back what was there before.
            Action.SHIFT -> setShift(ShiftState.LOCKED)
            // `&123` has already changed the page, on the way down, and that is not an
            // accident of ordering - it is what makes the page available to slide across at
            // the moment the hold fires rather than a frame later. See [onKeyPress].
            Action.SYMBOLS -> Unit
            else -> return false
        }
        slidingKeys = true
        keyboard?.startSlide(view)
        return true
    }

    /**
     * The finger has come off, somewhere out on the page the hold brought up.
     *
     * Types whatever it was over and puts the keyboard back. A key that would itself change
     * the page or the shift state is not typed: this gesture's promise is that both go back
     * to where they were, and a key that argues with that would leave the keyboard in a state
     * nobody asked for.
     */
    private fun finishSlide(view: KeyView) {
        val from = pageBeforeSlide
        slidingKeys = false
        pageBeforeSlide = null
        val target = keyboard?.takeSlide()
        keyboard?.endSlide()
        if (from == null) return
        // Never left the key it started on - a slow press rather than a slide, which is most
        // of what a hold on either of these keys has always been. So it means what it has
        // always meant: shift is locked, or the symbol page is up, and stays that way.
        if (target == null || target === view || target.key.action == view.key.action) return
        val typed = when (target.key.action) {
            null -> {
                // Through the same path a tap takes, so the letter arrives capitalised, joins
                // the word being composed, and is learned from, exactly as a typed one is.
                commitLetter(target)
                true
            }
            Action.SPACE -> {
                space()
                true
            }
            Action.BACKSPACE -> {
                backspace()
                true
            }
            Action.ENTER -> {
                enter()
                true
            }
            else -> false
        }
        // Only the slide that put the capitals up takes them down again, and it does it
        // before the page moves, so what comes back is painted in the state it is coming back
        // to. A slide from `&123` never touched shift, and what the key it just typed did to
        // it - a full stop arming the next capital - is the field's answer, not this
        // gesture's to overrule.
        if (view.key.action == Action.SHIFT) {
            // A one-shot that was already armed is spent by the letter this slide typed, the
            // same way a tapped letter would have spent it: one capital was asked for and one
            // was had. A locked shift, or none at all, is neither and goes back untouched.
            setShift(if (typed && from.shift == ShiftState.ONCE) ShiftState.OFF else from.shift)
            // The keys that end a sentence had nothing to say while the slide held shift
            // locked - see [updateAutoCaps], which leaves a locked shift alone and is right to
            // - so they are asked again now that it is not.
            if (typed && target.key.action != null) updateAutoCaps()
        }
        if (from.layout.id != keyboard?.currentLayout()?.id) showPage(from.layout)
    }

    /**
     * Abandons a slide without acting on it.
     *
     * For the keyboard going away underneath one, which is not the finger lifting and must
     * not type anything. What is left behind otherwise is a key painted as though a finger
     * were on it, on a grid that will be shown again.
     */
    private fun cancelSlide() {
        slidingKeys = false
        pageBeforeSlide = null
        keyboard?.endSlide()
    }

    /**
     * The system has switched which language this keyboard currently is.
     *
     * Android's model for a keyboard that speaks several languages is that each is a
     * *subtype* of the one input method, and the globe in the navigation bar - the system's
     * own, which belongs to no keyboard - is what moves between them. So there is no
     * language key here and there should not be: the keyboard is never asked to choose, it
     * is told, and its whole job is to lay itself out accordingly.
     *
     * The subtypes themselves are declared in `res/xml/method.xml`; this is the other half.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        adoptSubtype(newSubtype)
    }

    /**
     * Reads the current subtype and becomes that language.
     *
     * Called on every showing as well as on the change itself, because a subtype can be
     * switched while the keyboard is hidden - the switch is made from the navigation bar,
     * and the keyboard is not necessarily up when it happens.
     */
    private fun adoptSubtype(subtype: InputMethodSubtype? = currentSubtype()) {
        val tag = subtype?.let {
            @Suppress("DEPRECATION")
            it.languageTag.ifEmpty { it.locale }
        }
        // What the system says, held to what the user has actually turned on. Disabling the
        // language you are currently typing in is allowed, and has to land somewhere.
        val allowed = KeyboardLanguages.enabled(themeManager)
        val fromSystem = Layouts.forLanguageTag(tag)
        become(if (allowed.any { it.id == fromSystem.id }) fromSystem else allowed.first())
    }

    /**
     * Becomes [next], whichever of the three ways it was chosen: the globe in the navigation
     * bar, the hold on the space bar, or a language being turned off in the settings under a
     * keyboard that was currently speaking it.
     */
    private fun become(next: KeyboardLayout) {
        if (next.id == language.id) return
        language = next
        // The space bar names the language, so it changes here too - including when the
        // symbol page is up and the keys themselves are left alone.
        keyboard?.setSpaceLabel(next.name)
        // And its hold offers the others, which are now a different set: the one in use is
        // where that list opens and what it comes back to.
        keyboard?.setLanguages(KeyboardLanguages.enabled(themeManager), next)
        // A different language is a different dictionary, and the first word typed after
        // reaching for the globe should not be the one that pays for loading it.
        warmSuggester()
        // Only redraw if letters are what is on screen. Switching language underneath the
        // symbol page would throw away the page the user is looking at.
        if (keyboard?.currentLayout()?.language?.isNotEmpty() == true) showLetters()
    }

    /**
     * A hold on the space bar has chosen a language.
     *
     * The system is asked first and the keyboard changes either way. Asking is what keeps the
     * globe, the settings page and this keyboard's own next showing agreeing with each other
     * - see [KeyboardLanguages.switchTo] - and changing anyway is what makes the gesture
     * answer at once rather than a frame later, or not at all on a phone that refuses. If the
     * system does come back with an answer of its own, [adoptSubtype] takes it: by then this
     * is already that language, so it costs nothing.
     *
     * Picking the language already in use is the ordinary outcome of this gesture rather than
     * an odd one - it is what opening the list and thinking better of it comes to - so it is
     * not a case to be handled but simply nothing to do.
     */
    private fun switchLanguage(next: KeyboardLayout) {
        if (next.id == language.id) return
        KeyboardLanguages.switchTo(this, next)
        become(next)
    }

    private fun currentSubtype(): InputMethodSubtype? = try {
        getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
    } catch (e: Exception) {
        null
    }

    internal companion object {
        /**
         * How long the microphone shows a turning ring before it goes the accent.
         *
         * A second: long enough for the engine to open the microphone and, the first time,
         * to bring a model up, and short enough that it reads as the button responding
         * rather than as the keyboard having stalled.
         */
        private const val VOICE_SETTLE_MS = 1000L

        /** What the bar says when the clipboard mark is tapped and there is nothing on it. */
        const val NOTHING_COPIED = "Nothing on the clipboard"

        /** What a GIF is, for the field that is asked whether it takes one. See [sendGif]. */
        const val GIF_MIME = "image/gif"

        /** How many the bar shows. See [CandidateBar]. */
        /**
         * How many suggestions are worked out for the bar.
         *
         * The bar's own limit rather than a second number here, because the two have to agree
         * and there is nothing this end could sensibly decide: how many words fit is a
         * question about a strip of screen, and the strip is what answers it. See
         * [CandidateBar.MAX_WORDS].
         */
        const val BAR_SLOTS = CandidateBar.MAX_WORDS

        /**
         * How many emoji one typed word may put on the bar.
         *
         * Two. `kiss` names three or four things and `heart` names a dozen, and past the
         * second the bar stops being suggestions with an emoji in them and becomes an emoji
         * picker that has pushed the words off the end. Anyone who wanted the third is one tap
         * from the panel that has all of them, which is what the panel is for.
         */
        const val EMOJI_SLOTS = 2

        /** How long a message stays on the bar before the suggestions come back. */
        const val MESSAGE_MS = 2_500L


        /**
         * How long an answer may take before the field counts as not answering. See [ask].
         *
         * A field that is keeping up answers in single figures of milliseconds, because the
         * work behind the question is reading a few characters out of a string it already
         * holds. A quarter of a second is not a slow answer, it is a stalled one, and there
         * is no third case to be careful of.
         */
        const val SLOW_ANSWER_MS = 250L

        /**
         * How long the keyboard goes without asking, once an answer has taken too long.
         *
         * Long enough to cover the burst of keystrokes that would otherwise queue up behind
         * the same wait one after another, and short enough that a moment's stutter does not
         * cost the automatic capital at the start of the next sentence.
         */
        const val NOT_ASKING_MS = 3_000L

        /**
         * And the longest that silence ever grows to, however many stalls there have been.
         *
         * Half a minute. Everything given up while a field is not answering degrades to the
         * plain behaviour rather than to a broken one - no automatic capital, no word picked
         * back up by a backspace, no full stop from a double space - so a long silence costs
         * cleverness and nothing else, where each probe that breaks one costs two seconds of
         * a phone that will not type. The cap exists at all so that an application which
         * recovers quietly is eventually noticed. See [quietFor].
         */
        const val NOT_ASKING_MAX_MS = 30_000L

        const val DOUBLE_TAP_MS = 400L
        /**
         * Punctuation that belongs against the word before it, with no space between.
         *
         * Closing marks and sentence enders only. An opening bracket is deliberately absent -
         * `he said (quietly)` wants its space - and so is the hyphen, which is a word joiner
         * as often as it is a dash and guessing wrong either way is worse than leaving it.
         * `@` and `/` are here because the contextual key produces them in address fields,
         * where a space in front of one is never right.
         */
        /**
         * How far back to look for a word when backspacing into one.
         *
         * Longer than any word anybody is typing, and short enough that it is not a request
         * for the paragraph. The dictionary builder caps words at 32 characters.
         */
        const val RESUME_LOOKBACK = 48

        /**
         * And how far to look for the line the caret is on, in either direction.
         *
         * Two or three lines of a message rather than the whole of one, because that is all
         * an up-and-down push ever needs: it moves one line at a time and each move asks
         * again. A paragraph longer than this is one the caret cannot see the start of, and
         * the push stops rather than landing somewhere invented. See [onCursorLines].
         */
        const val LINE_LOOK = 512

        /**
         * How many unanswered moves it takes to conclude the caret is at the end of the text.
         *
         * Only reached in a field that is not answering questions at all, where a move is
         * made on spec and the field's silence afterwards is the only evidence there is. Small
         * because the evidence is weak in both directions: too many and the caret goes on
         * pretending to move for a noticeable moment after it has stopped, too few and an
         * ordinary report arriving a frame late is mistaken for the end of the text. Three is
         * a tenth of a second at the fastest the caret ever runs. See [onCursorSlide].
         */
        const val SLIDE_SLACK = 3

        const val ATTACHING = ".,!?:;)]}%\u2026\"'\u2019@/"

        /** What ends a sentence, for [atSentenceStart]. */
        const val SENTENCE_END = ".!?\u2026"

        /** Punctuation that a dictated phrase follows without a space. See [leadForDictation]. */
        const val OPENING = "([{\u201c\u2018"

        const val DOUBLE_SPACE_MS = 900L
    }
}

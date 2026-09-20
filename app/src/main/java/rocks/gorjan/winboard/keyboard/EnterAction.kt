package rocks.gorjan.winboard.keyboard

import android.view.inputmethod.EditorInfo

/**
 * What the enter key does in the field being typed into.
 *
 * One answer, asked for in two places - the service paints the key from it and presses the
 * key with it - and that is the point of the type. The two used to work it out separately
 * from the same [EditorInfo], which is exactly how they came to disagree: the key said
 * `search` and the press put in a line break.
 */
data class EnterJob(
    /**
     * What to hand `InputConnection.performEditorAction`, or null when enter is not an action
     * in this field and is a line break instead.
     */
    val actionId: Int?,
    /**
     * Which of the standard actions this is, which is what the key's face is chosen from.
     *
     * Not always [actionId]: an application that supplies its own label for the key supplies
     * its own id with it, and that id means nothing outside the application that issued it.
     */
    val standard: Int,
    /** The application's own word for the key, when it has offered one. */
    val label: CharSequence?
) {

    /** Whether pressing enter performs an action at all. */
    val acts: Boolean get() = actionId != null

    /**
     * The application's label, if it will fit on a key.
     *
     * Lowercased, because every other word on this keyboard is - `send`, `next`, `abc` - and
     * a key reading `Post` among them is a key from a different keyboard. Dropped when it is
     * too long to be read at the size a key sets a word in: the action still happens, and the
     * key simply goes back to saying what it always said. A label is an application's
     * suggestion about a key that is not its own.
     */
    fun ownWord(): String? = label?.toString()?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ENTER_LABEL }
}

/**
 * Reads that answer out of what the field declared.
 *
 * **[EditorInfo.IME_FLAG_NO_ENTER_ACTION] decides this, and not the multi-line flag.** That
 * was the bug this was extracted to fix. A field declaring both an action and
 * `TYPE_TEXT_FLAG_MULTI_LINE` had its action thrown away, on the reasoning that a field which
 * can hold more than one line must want enter to make one. It is not the keyboard's call to
 * make: the framework has a flag for exactly this question, its documentation is that "the
 * editor will handle the enter key itself", and `TextView` sets it on every multi-line field
 * it creates - so across the whole of the platform's own furniture the two tests agreed and
 * the multi-line one was invisible.
 *
 * They stop agreeing outside it. A Compose text field is single-line only when it was written
 * `singleLine = true`; one written `maxLines = 1` instead - which looks the same and is at
 * least as common - declares itself multi-line to the keyboard while still asking for
 * `search` or `send`. On those the old test won: the key came up as a plain return key, and
 * pressing it put a line break into a box one line tall, where a line break is drawn as a
 * space. Play Store's search box is one of them.
 *
 * An application's own [EditorInfo.actionLabel] is honoured too, with the
 * [EditorInfo.actionId] that belongs to it. A field that has gone to the trouble of labelling
 * the keyboard's own key has something specific in mind, and a plain return key is the one
 * response guaranteed not to be it.
 *
 * @param imeOptions the field's [EditorInfo.imeOptions], or zero if there is no field.
 * @param actionLabel its [EditorInfo.actionLabel]: a word of the application's own, or null.
 * @param actionId the id that goes with that label, and only with it.
 */
fun enterJobFor(imeOptions: Int, actionLabel: CharSequence?, actionId: Int): EnterJob {
    val standard = imeOptions and EditorInfo.IME_MASK_ACTION
    if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
        return EnterJob(null, standard, null)
    }
    if (actionLabel != null) return EnterJob(actionId, standard, actionLabel)
    // Unspecified is not an action, it is the absence of one, and a field that declares it
    // gets the key itself - which the platform's own editors turn into whatever they were
    // going to do with return anyway. None is a field saying the same thing out loud.
    if (standard == EditorInfo.IME_ACTION_NONE ||
        standard == EditorInfo.IME_ACTION_UNSPECIFIED
    ) {
        return EnterJob(null, standard, null)
    }
    return EnterJob(standard, standard, null)
}

/**
 * The longest word an application may put on the enter key. See [EnterJob.ownWord].
 *
 * `send`, `next` and `done` are the keyboard's own and are four; `continue` is eight and is
 * about as much as the key can set at the size it sets a word. Past that the label is dropped
 * rather than shrunk or cut off - a key reading `confirm pu` says less than the arrow it
 * replaced, and the action happens either way.
 */
const val MAX_ENTER_LABEL = 8

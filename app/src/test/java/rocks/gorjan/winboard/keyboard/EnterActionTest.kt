package rocks.gorjan.winboard.keyboard

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the enter key does in a given field.
 *
 * Every one of these is a real field on a real phone rather than a combination of flags
 * chosen to fill a table: the cases that matter here are the ones applications actually
 * declare, and the bug that produced this file was a combination nobody had thought to try.
 *
 * `EditorInfo`'s action and flag values are compile-time constants, so none of this needs a
 * device or an `EditorInfo` to run against.
 */
class EnterActionTest {

    /**
     * The bug. A field that declares an action **and** says it can hold more than one line.
     *
     * Play Store's search box, and every other Compose text field written `maxLines = 1`
     * rather than `singleLine = true`. The keyboard used to let the multi-line flag veto the
     * action, put a line break in - which a one-line box draws as a space - and never search.
     */
    @Test
    fun `an action on a multi-line field is still an action`() {
        val job = enterJobFor(EditorInfo.IME_ACTION_SEARCH, null, 0)
        assertTrue(job.acts)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, job.actionId)
    }

    /**
     * And the flag that genuinely means "leave enter alone", which is the one `TextView` sets
     * on every multi-line field it makes. A chat box: the action is elsewhere, on a button.
     */
    @Test
    fun `no-enter-action means the key is a line break, whatever else is declared`() {
        val job = enterJobFor(
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION, null, 0
        )
        assertFalse(job.acts)
        assertNull(job.actionId)
    }

    /** A field that has said it wants nothing done. */
    @Test
    fun `none is not an action`() {
        assertFalse(enterJobFor(EditorInfo.IME_ACTION_NONE, null, 0).acts)
    }

    /**
     * Nor is the absence of a declaration.
     *
     * The key goes through as a key there, which is what the platform's own editors expect
     * and turn into whatever they were going to do with return.
     */
    @Test
    fun `unspecified is not an action`() {
        assertFalse(enterJobFor(EditorInfo.IME_ACTION_UNSPECIFIED, null, 0).acts)
    }

    /** The ordinary cases, which have always worked and are here so they go on working. */
    @Test
    fun `the named actions are performed`() {
        for (action in intArrayOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_DONE
        )) {
            val job = enterJobFor(action, null, 0)
            assertTrue("action $action", job.acts)
            assertEquals(action, job.actionId)
            assertEquals(action, job.standard)
        }
    }

    /**
     * An application's own label, with the id that belongs to it.
     *
     * The id is the application's and not one of the standard ones, so the two are kept
     * apart: [EnterJob.actionId] is what gets performed, [EnterJob.standard] is what the key
     * is painted from.
     */
    @Test
    fun `a custom label is performed under its own id`() {
        val job = enterJobFor(EditorInfo.IME_ACTION_UNSPECIFIED, "Post", 42)
        assertTrue(job.acts)
        assertEquals(42, job.actionId)
        assertEquals(EditorInfo.IME_ACTION_UNSPECIFIED, job.standard)
        assertEquals("post", job.ownWord())
    }

    /** But not when the field has said to leave the key alone. The label is not permission. */
    @Test
    fun `a custom label under no-enter-action is still a line break`() {
        assertFalse(enterJobFor(EditorInfo.IME_FLAG_NO_ENTER_ACTION, "Post", 42).acts)
    }

    /** A label too long to read on a key is dropped, and the action is not. */
    @Test
    fun `an oversized label is dropped but still acts`() {
        val job = enterJobFor(EditorInfo.IME_ACTION_UNSPECIFIED, "Confirm purchase", 7)
        assertTrue(job.acts)
        assertEquals(7, job.actionId)
        assertNull(job.ownWord())
    }

    /** Whitespace and capitals are the application's; the key's voice is the keyboard's. */
    @Test
    fun `a label is trimmed and lowercased`() {
        assertEquals("go now", enterJobFor(0, "  Go Now  ", 1).ownWord())
    }

    /** A label of nothing at all is no label. */
    @Test
    fun `a blank label is dropped`() {
        assertNull(enterJobFor(0, "   ", 1).ownWord())
    }

    /** No field at all - between one box being left and the next being tapped. */
    @Test
    fun `nothing declared is a line break`() {
        assertFalse(enterJobFor(0, null, 0).acts)
    }
}

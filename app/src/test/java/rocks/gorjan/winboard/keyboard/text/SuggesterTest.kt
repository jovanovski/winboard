package rocks.gorjan.winboard.keyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import rocks.gorjan.winboard.keyboard.Layouts
import java.io.File

/**
 * The prediction engine, against the real shipped dictionary.
 *
 * These read `app/src/main/assets/keyboard/en.trie` off disk rather than building a toy trie,
 * because the things that go wrong here go wrong at scale: a format that round-trips six words
 * and misreads the hundred-thousandth, a search whose pruning is subtly too aggressive and
 * quietly stops finding long words, a scoring rule that looks sensible until it is asked to
 * choose between two real English words. A fixture of a dozen made-up entries would pass while
 * the keyboard was unusable.
 *
 * Every expectation below is a sentence about behaviour a person would notice, not about an
 * implementation detail.
 */
class SuggesterTest {

    @Test
    fun theDictionaryLoads() {
        assertNotNull("no dictionary - is app/src/main/assets/keyboard/en.trie missing?", english)
        assertTrue("suspiciously few words: ${english!!.wordCount}", english!!.wordCount > 100_000)
    }

    /** The commonest words in the language are present and ranked as such. */
    @Test
    fun frequenciesAreOrderedSensibly() {
        val dict = english!!
        assertTrue(
            "'the' should be commoner than 'keyboard'",
            dict.frequencyOf("the") > dict.frequencyOf("keyboard")
        )
        assertTrue(
            "'keyboard' should be commoner than 'antidisestablishmentarianism'",
            dict.frequencyOf("keyboard") > dict.frequencyOf("antidisestablishmentarianism")
        )
        assertEquals("a word that is not there must score zero", 0, dict.frequencyOf("qqqqqqzz"))
    }

    /**
     * Contractions exist.
     *
     * The corpus splits every one of them at the apostrophe - it contains `don` and a separate
     * `'t`, never `don't` - so the builder rebuilds them. Without that step the keyboard cannot
     * type some of the commonest words in English and actively fights anyone who tries.
     */
    @Test
    fun contractionsSurvivedTheCorpus() {
        val dict = english!!
        for (word in listOf("don't", "can't", "won't", "i'm", "it's", "that's", "you're", "isn't", "didn't")) {
            assertTrue("$word is missing from the dictionary", dict.frequencyOf(word) > 0)
        }
    }

    /** And the non-words the tokenizer left behind do not. */
    @Test
    fun contractionStemsWereThrownAway() {
        val dict = english!!
        for (stem in listOf("doesn", "isn", "wasn", "couldn", "wouldn", "aren", "weren")) {
            assertEquals("'$stem' is not a word and must not be offered", 0, dict.frequencyOf(stem))
        }
        // But the two that really are words stayed.
        assertTrue(dict.frequencyOf("can") > 0)
        assertTrue(dict.frequencyOf("won") > 0)
    }

    /** Typing a prefix completes it, best-known word first. */
    @Test
    fun aPrefixIsCompleted() {
        assertTopCandidate("hel", "hello", "help", "held")
        assertTopCandidate("keyb", "keyboard")
        assertTopCandidate("wor", "world", "work", "words", "word", "worry")
    }

    /**
     * The classic typos are corrected.
     *
     * `teh` is a transposition, `hte` another, and `helol` is one in the middle of a longer
     * word. All three are what fast typing produces and all three have to work, or the feature
     * is decoration.
     */
    @Test
    fun transpositionsAreCorrected() {
        assertOffers("teh", "the")
        assertOffers("hte", "the")
        assertOffers("helol", "hello")
        assertOffers("becuase", "because")
    }

    /**
     * The apostrophe left out, which is not a slip but a shortcut.
     *
     * Reaching an apostrophe means holding a key, so people skip it and expect the keyboard
     * to know what they meant. Every one of these is also a real word on its own - `shell`,
     * `ill`, `were`, `cant` - so the keyboard must *offer* the contraction without correcting
     * to it, which is what keeping the literal in the first slot is for.
     */
    @Test
    fun aMissingApostropheIsOffered() {
        assertOffers("shell", "she'll")
        assertOffers("dont", "don't")
        assertOffers("cant", "can't")
        assertOffers("shes", "she's")
        assertOffers("were", "we're")
        // `ill` and `im` are the same shortcut and belong here too, but they come back with a
        // capital and are checked next door - see [thePronounIsOfferedWithItsCapital].
    }

    /**
     * And the pronoun comes back with its capital.
     *
     * `i'll` is not a word. Offering it as the correction for `ill` is the keyboard putting a
     * misspelling into somebody's sentence on their behalf, which is worse than offering
     * nothing at all - and the dictionary cannot help here, because it is lowercase by
     * construction so that the search can fold the case of everything typed into it.
     */
    @Test
    fun thePronounIsOfferedWithItsCapital() {
        assertOffers("ill", "I'll")
        assertOffers("im", "I'm")
        assertOffers("ive", "I've")
        assertOffers("id", "I'd")
        // However it was typed: a sentence's first word arrives here already capitalised.
        assertOffers("Ill", "I'll")
    }

    /** Everything else keeps the spelling the dictionary has for it. */
    @Test
    fun ordinaryWordsAreNotCapitalised() {
        assertOffers("teh", "the")
        assertOffers("shell", "she'll")
        assertTrue(
            "a search for 'ill' capitalised something that was not the pronoun",
            suggester.candidates("ill").none { it.word.first().isUpperCase() && it.word != "I'll" }
        )
    }

    /**
     * A capital the user typed is a capital the suggestion keeps.
     *
     * The dictionary is lowercase by construction - that is what lets the search fold the
     * case of everything typed into it - so every word that comes back out of it arrives in
     * lower case, and handing that straight back undoes a shift somebody pressed on purpose.
     * `Skop` being corrected to `skopje` is the case that makes it plain: the keyboard was
     * told the word was a name in the only way a keyboard can be told, and corrected the
     * capital away.
     */
    @Test
    fun typedCapitalsSurviveTheCorrection() {
        assertOffers("Teh", "The")
        assertOffers("Hel", "Hello")
        assertTrue(
            "a word typed with a capital must not be offered back without one",
            suggester.candidates("Teh").all { it.word.first().isUpperCase() }
        )
    }

    /** Block capitals come back shouting, pronoun and all. */
    @Test
    fun blockCapitalsSurviveTheCorrection() {
        assertOffers("TEH", "THE")
        assertOffers("IM", "I\'M")
    }

    /**
     * But one capital in a two-letter word is a sentence starting, not somebody shouting.
     *
     * `Hi` is both at once as far as the letters go, and reading it as shouting would put a
     * bar full of block capitals in front of anybody who simply began a sentence.
     */
    @Test
    fun aCapitalisedShortWordIsNotShouting() {
        assertTrue(
            "'Th' was read as shouting",
            suggester.candidates("Th").none { it.word == it.word.uppercase() }
        )
    }

    /** But a word with one already is not given a second. */
    @Test
    fun aWordThatHasItsApostropheIsLeftAlone() {
        assertTrue(suggester.candidates("don't").none { it.word.count { c -> c == '\'' } > 1 })
    }

    /**
     * Two words run together, with or without a stray letter between them.
     *
     * The space that did not register, and the space bar that was reached for and missed -
     * the thumb catching the `n` above it, which is the oldest mis-hit on a touch keyboard.
     * Neither is reachable by the correction walk, which corrects one word and has no notion
     * that there might be two.
     */
    @Test
    fun wordsRunTogetherAreSeparated() {
        assertOffers("helloworld", "hello world")
        assertOffers("inthe", "in the")
        assertOffers("hellonworld", "hello world")
        assertOffers("thenend", "the end")
    }

    /**
     * But a word that is a word is never broken up.
     *
     * Half the language cuts into two shorter words, and offering to split one somebody has
     * correctly typed is worse than useless. This is the single rule that makes the whole
     * feature usable rather than a nuisance.
     */
    @Test
    fun aRealWordIsNotSplit() {
        for (word in listOf("therein", "anytime", "into", "nowhere", "become", "already")) {
            assertTrue(
                "'$word' is a word and must not be offered as two",
                suggester.candidates(word).none { it.word.contains(' ') }
            )
        }
    }

    /** A finger that landed on the neighbouring key. */
    @Test
    fun nearMissesAreCorrected() {
        assertOffers("keyboatd", "keyboard")   // r -> t, adjacent
        assertOffers("wprld", "world")         // o -> p, adjacent
        assertOffers("helli", "hello")         // o -> i, adjacent
    }

    /**
     * A key across the keyboard is not treated as a near miss.
     *
     * The whole point of weighting substitutions by distance. `sad` and `pad` are both ordinary
     * words one edit apart, and the only thing that can tell them apart is that `s` and `p` are
     * nowhere near each other - so typing one must not confidently offer the other ahead of
     * everything else.
     */
    @Test
    fun distantKeysAreNotTreatedAsNearMisses() {
        val forSad = suggester.candidates("sad").map { it.word }
        val forPad = suggester.candidates("pad").map { it.word }
        assertTrue(
            "'sad' should not be the first thing offered for 'pad', got $forPad",
            forPad.indexOf("sad").let { it != 0 }
        )
        assertTrue(
            "'pad' should not be the first thing offered for 'sad', got $forSad",
            forSad.indexOf("pad").let { it != 0 }
        )
    }

    /**
     * One and two characters are never corrected.
     *
     * At that length nearly every short word is within one edit of every other, so a correction
     * is a coin toss dressed up as help. A keyboard that turns `hi` into `he` because `he` is
     * commoner is a keyboard people switch off.
     */
    @Test
    fun veryShortInputIsLeftAlone() {
        for (typed in listOf("h", "a", "hi", "no", "ok")) {
            val corrections = suggester.candidates(typed)
                .filter { !it.word.startsWith(typed, ignoreCase = true) }
            assertTrue(
                "'$typed' should not be corrected, but got ${corrections.map { it.word }}",
                corrections.isEmpty()
            )
        }
    }

    /** What the user typed is never handed back as a suggestion; the caller offers it. */
    @Test
    fun theLiteralTextIsNotOfferedAsACandidate() {
        for (typed in listOf("hello", "the", "keyboard")) {
            assertTrue(
                "'$typed' should not suggest itself",
                suggester.candidates(typed).none { it.word == typed }
            )
        }
    }

    /** Nonsense produces nothing rather than something arbitrary. */
    @Test
    fun nonsenseIsNotForced() {
        assertTrue(suggester.candidates("").isEmpty())
        assertTrue(suggester.candidates("xqzjvkw").isEmpty())
    }

    /**
     * Fast enough to run under a thumb.
     *
     * The budget that matters is a frame: if a keystroke's suggestions take longer than about
     * sixteen milliseconds the keyboard visibly lags behind typing. Measured warm and given
     * generous headroom, because a unit test on a laptop is not a phone - what this really
     * guards against is a change that makes the search an order of magnitude slower, which is
     * easy to do by weakening the pruning.
     */
    @Test
    fun aKeystrokeIsFast() {
        val words = listOf("a", "he", "hel", "keyb", "teh", "becuase", "wprld", "thanks", "somethin")
        repeat(5) { words.forEach { suggester.candidates(it) } }   // warm up
        var total = 0.0
        for (word in words) {
            val started = System.nanoTime()
            val rounds = 30
            repeat(rounds) { suggester.candidates(word) }
            val each = (System.nanoTime() - started) / 1e6 / rounds
            total += each
            println("  %-10s %6.2f ms".format(word, each))
        }
        val each = total / words.size
        println("mean %.2f ms per keystroke".format(each))
        assertTrue("a keystroke took %.1f ms, which is too slow".format(each), each < 16.0)
    }

    /**
     * A search nobody wants any more stops, and stops *immediately*.
     *
     * The keyboard searches off the main thread and a thumb is quicker than a search, so on
     * any fast run of keys most searches are about a word that has already grown another
     * letter by the time they finish. Those have to be dropped where they stand: left
     * running, a burst costs a full search for every key in it, all but the last thrown away,
     * and the thread falls further behind the further you type - which is exactly what the
     * lag felt like.
     *
     * Measured rather than asserted on the result, because the way to get this wrong is to
     * stop *slowly*. The walk is recursive, and a plain `return` unwinds one level of it and
     * leaves the node above carrying on to its next edge - a search that has been called off
     * and goes on visiting nodes at nearly full speed, which no test of the answer would
     * notice. The margin here is wide: what it is catching is a stop that does not propagate,
     * which costs whole milliseconds, not tens of microseconds.
     */
    @Test
    fun anOvertakenSearchIsDroppedAtOnce() {
        val words = listOf("constellatio", "different", "somethin", "keyboa")
        repeat(5) { words.forEach { suggester.candidates(it, null) { false } } }   // warm up

        var whole = 0.0
        var calledOff = 0.0
        for (word in words) {
            var running = System.nanoTime()
            repeat(20) { suggester.candidates(word, null) { false } }
            whole += (System.nanoTime() - running) / 1e6 / 20

            running = System.nanoTime()
            repeat(20) { suggester.candidates(word, null) { true } }
            calledOff += (System.nanoTime() - running) / 1e6 / 20
        }
        println("whole %.3f ms, called off %.3f ms".format(whole / words.size, calledOff / words.size))
        assertTrue(
            "a search called off cost %.3f ms against %.3f ms run whole - it is not stopping"
                .format(calledOff / words.size, whole / words.size),
            calledOff * 4 < whole
        )
        assertTrue(
            "a search called off should offer nothing",
            suggester.candidates("keyboa", null) { true }.isEmpty()
        )
    }

    /** Learned words are offered, and learned pairs predict what comes next. */
    @Test
    fun whatIsLearnedIsOffered() {
        val file = File.createTempFile("learned", ".txt")
        file.delete()
        val learned = UserDictionary.openAt(file)
        val withLearning = Suggester(english, Layouts.EN_QWERTY, learned)

        assertTrue(withLearning.candidates("gorj").none { it.word == "gorjan" })
        repeat(3) { learned.learn("gorjan") }
        assertTrue(
            "a learned word should be offered",
            withLearning.candidates("gorj").any { it.word == "gorjan" }
        )

        learned.learnPair("windows", "launcher")
        assertEquals(listOf("launcher"), withLearning.following("windows").map { it.word })
        assertTrue("nothing has followed 'aardvark'", withLearning.following("aardvark").isEmpty())
        file.delete()
    }

    /**
     * A language with no word list still learns.
     *
     * Twenty-two layouts ship and two dictionaries do, so this is not an edge case - it is
     * what German, Polish, Greek and seventeen others do every time somebody types in them.
     * Such a keyboard has nothing to correct against and should offer nothing on its own, but
     * the words this person has actually typed are not language data that failed to ship, and
     * dropping them would make the learned dictionary a feature only English gets.
     */
    @Test
    fun aLanguageWithoutADictionaryStillOffersWhatItLearned() {
        val file = File.createTempFile("nodict", ".txt")
        file.delete()
        val learned = UserDictionary.openAt(file)
        val without = Suggester(null, Layouts.DE_QWERTZ, learned)

        // Nothing shipped, so nothing is offered - not a crash, and not a guess either.
        assertTrue(without.candidates("hel").isEmpty())

        repeat(3) { learned.learn("gorjan") }
        assertEquals(listOf("gorjan"), without.candidates("gorj").map { it.word })

        learned.learnPair("windows", "launcher")
        assertEquals(listOf("launcher"), without.following("windows").map { it.word })
        file.delete()
    }

    // ---------------------------------------------------------------- helpers

    private fun assertTopCandidate(typed: String, vararg acceptable: String) {
        val candidates = suggester.candidates(typed)
        assertTrue("'$typed' produced nothing", candidates.isNotEmpty())
        assertTrue(
            "'$typed' offered ${candidates.take(3).map { it.word }}, expected one of ${acceptable.toList()} first",
            candidates.first().word in acceptable
        )
    }

    private fun assertOffers(typed: String, expected: String) {
        val candidates = suggester.candidates(typed).map { it.word }
        assertTrue(
            "'$typed' should offer '$expected' in its first three, got ${candidates.take(5)}",
            expected in candidates.take(3)
        )
    }

    companion object {
        private var english: Dictionary? = null
        private lateinit var suggester: Suggester

        @BeforeClass
        @JvmStatic
        fun loadOnce() {
            // Unit tests run with the module root as the working directory.
            val file = File("src/main/assets/keyboard/en.trie")
            english = if (file.exists()) Dictionary.parse(file.readBytes()) else null
            suggester = Suggester(english, Layouts.EN_QWERTY)
        }
    }
}

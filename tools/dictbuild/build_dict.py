#!/usr/bin/env python3
"""
Turns a word-frequency list into the packed trie the keyboard reads.

Run by hand, output committed. Deliberately NOT a Gradle task: the word lists change
approximately never, and wiring a multi-hundred-megabyte download and a trie build into
`./gradlew assembleDebug` would tax every build of the app forever for the sake of a file
that gets regenerated once a year.

    ./build_dict.py en ../../app/src/main/assets/keyboard/en.trie

English is the one that ships, so that is where it goes. Any of the other twenty-one can be
built the same way - see build_all.sh, which builds all of them - but nothing in the app
reads those yet.

Source is hermitdave/FrequencyWords (OpenSubtitles). The generator code there is MIT; the
word lists themselves are CC BY-SA 3.0, which the app owes an attribution line for.

WHAT ELSE THIS READS, AND UNDER WHAT TERMS

Every language but English is checked against its Hunspell dictionary, fetched from
wooorm/dictionaries. Those files are read on the machine running this script and are never
committed or shipped: what ships is a list of words drawn from the corpus above, ordered by
that corpus's frequencies. Their licences vary and six are GPL - Czech, German, Italian,
Macedonian, Norwegian and Ukrainian - so they are named here rather than left implicit:

    MIT / BSD / MPL    en (MIT AND BSD), fr (MPL-2.0), nl (BSD-3 OR CC-BY-3.0),
                       ru (BSD-3), tr (MIT), pl (MPL-2.0 option), pt (MPL-2.0 option),
                       da, el, es, hu, ro, sk, sr (MPL-1.1 option)
    LGPL               sv (LGPL-3.0)
    GPL only           cs (GPL-2.0), de (GPL-2/3), it (GPL-3), mk (GPL-3),
                       nb (GPL-2.0), uk (GPL-3)

Finnish has no Hunspell dictionary in that collection - it is spell-checked with Voikko
instead - so Finnish alone still stands on frequency, at the old floor.

REQUIRES

    pip install spylls        # pure-Python Hunspell, MIT

FILE FORMAT (little-endian throughout)

    header, 16 bytes
        0  magic       4  b"WPKD"
        4  version     1  = 1
        5  reserved    1
        6  edgeSize    1  = 8, so a reader can reject a format it does not know
        7  reserved    1
        8  rootOffset  4  byte offset of the root node
       12  wordCount   4  how many words went in, for sanity checks

    node
        u16 childCount, then childCount edges

    edge, 8 bytes, FIXED so that a node's children can be binary-searched
        0  char        2  UTF-16 code unit
        2  flags       1  bit0 terminal, bit1 has children
        3  freq        1  1..255 log-scaled; 0 when not terminal
        4  childOffset 4  absolute byte offset, 0 when none

Edges within a node are sorted by `char`. Fixed-width edges are the whole reason: a
variable-length encoding would be a little smaller and would force a linear scan of every
node on every lookup, and lookups happen thousands of times per keystroke while walking the
trie under an edit-distance bound.

A plain trie rather than a suffix-merged DAWG. A DAWG's trick is that identical suffixes
share nodes, and per-word frequencies stop suffixes being identical - the merge either fails
or forces the frequencies out into a side table, which costs back what the merge saved.
"""

import math
import os
import struct
import sys
import tempfile
import urllib.request

MAGIC = b"WPKD"
VERSION = 1
EDGE_SIZE = 8

FLAG_TERMINAL = 1
FLAG_CHILDREN = 2

# ---------------------------------------------------------------- what counts as a word
#
# A frequency list alone will not do, and finding that out cost some time. The corpus is
# subtitles, and its long tail is misspellings, character names, OCR damage and fragments of
# other languages - but *frequency cannot separate those from real words*, because plenty of
# the junk is common. `jel` appears 42 times, which a floor would catch; `hel` appears 1,376
# times, which no floor sensible enough to keep `antidisestablishmentarianism` (17) ever will.
#
# That matters beyond a stray suggestion. A keyboard does not correct a word it believes in, so
# every piece of junk in here is a typo that will never be fixed - and the junk is exactly the
# near-misses of real words, because that is what mistyping produces.
#
# English answers it with a word list, below. Every other language answers it with its own
# Hunspell dictionary - see HUNSPELL - which is the same rule arriving by a better route: a
# real lexicon, with affixes, maintained by people who speak the language. Where that exists
# the frequency floor stops mattering and comes all the way down to 1, because the question
# "is this a word" is no longer being answered by "does it appear often".
#
# So three sources, and a word needs one of them:
#
#   1. It is in a real English word list. `dwyl/english-words` (public domain), 370,105 words,
#      which does the heavy lifting: on its own it accounts for about 139,000 of what the
#      corpus offers and discards essentially all of the noise.
#   2. It is common enough in the corpus to be real whatever a word list thinks. This is what
#      keeps names and slang that no dictionary has caught up with.
#   3. It is on the short list below, for the words a phone needs and a word list compiled
#      before phones cannot have.
WORDLIST = "https://raw.githubusercontent.com/dwyl/english-words/master/words_alpha.txt"

# Frequent enough that the corpus itself vouches for it. Low, because rule 1 has already done
# most of the work and this only has to catch what a word list would not know about.
CORPUS_VOUCHES = 500

# Words a 2015 word list cannot contain, which anyone typing on a phone will want on the first
# day. Not a general escape hatch - everything here is either a thing that did not exist when
# the word list was compiled, or a proper noun common enough to be worth the space.
MODERN = """
emoji wifi smartphone selfie app apps blog vlog podcast hashtag username login logout
bluetooth android iphone ipad google youtube instagram whatsapp netflix spotify uber
facebook twitter tweet retweet unfollow streaming webcam laptop usb pdf url gif jpeg
smartwatch airpods bitcoin crypto wikipedia reddit tiktok snapchat telegram zoom
skopje ohrid macedonian
"""

MIN_COUNT = 3

# ---------------------------------------------------------------- the other twenty
#
# Which Hunspell dictionary answers for which layout. The names are wooorm/dictionaries';
# where a language has regional variants the plain one is taken, since a keyboard's word list
# is not the place to pick a side between pt-PT and pt-BR.
#
# Serbian is the Cyrillic dictionary deliberately: the corpus is Latin and is transliterated
# before it gets here, so checking it against Cyrillic also checks the transliteration - a
# mis-transliterated word is not a word and is dropped, which is a better outcome than the
# comment in `to_cyrillic` promises.
HUNSPELL = {
    "cs": "cs", "da": "da", "de": "de", "el": "el", "es": "es", "fr": "fr", "hu": "hu",
    "it": "it", "mk": "mk", "nb": "nb", "nl": "nl", "pl": "pl", "pt": "pt", "ro": "ro",
    "ru": "ru", "sk": "sk", "sr": "sr", "sv": "sv", "tr": "tr", "uk": "uk",
}

HUNSPELL_SOURCE = "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/{0}/index.{1}"

# Kept between runs: a dictionary is a few megabytes and building twenty-one languages should
# not fetch each of them twice. The system temp directory rather than anywhere in the project,
# because these are emphatically not ours to commit.
HUNSPELL_CACHE = os.path.join(tempfile.gettempdir(), "wp81-hunspell")

# The floor for a language checked against a real dictionary.
#
# One. A word that appears once and is in the lexicon is a word - that is the entire point of
# having a lexicon, and it is what rescues the vocabulary that the old floor was cutting off:
# Ukrainian's corpus is a thirtieth the size of Russian's, so `клавіатура` appears twice there
# and was being dropped for it.
MIN_COUNT_CHECKED = 1

# The floor for a language with no word list to check against.
#
# Low, and deliberately no higher than English's, even though frequency is the only evidence
# there is here. The instinct is to raise it to compensate - that was tried at 40, and it took
# the language apart. Macedonian is heavily inflected, so its vocabulary is spread across many
# forms of each word with correspondingly small counts per form, and the corpus is a hundredth
# the size of the English one to begin with. At 40, `тастатура` - the Macedonian word for
# *keyboard* - was gone, and so was `скопје`. Both appear about a dozen times.
#
# So this admits some noise, knowingly. A keyboard that occasionally believes in a word that is
# not one occasionally fails to correct a typo. A keyboard missing the capital city and the
# word for itself is not usable in the language at all. If a permissively-licensed Macedonian
# word list turns up, that is the better fix.
MIN_COUNT_NO_WORDLIST = 3

MAX_WORDS = 150_000

# What letters a language is allowed to be spelled with. Anything else - stray Latin in a
# Cyrillic list, digits, punctuation beyond the apostrophe - is corpus dirt.
#
# One entry per keyboard layout, and the two have to agree: a word the dictionary believes in
# but the layout cannot type is a word the keyboard will offer and then refuse to let anybody
# spell. So each set below is the letters on that layout's three rows, plus the diacritics that
# are genuinely part of the language's orthography.
#
# Deliberately *not* the layout's `alternates` wholesale. Those are a mix of two different
# things - Polish `a` -> `ą` is Polish, German `e` -> `é` is a convenience for typing foreign
# names - and admitting the second kind is how a corpus's French contamination gets into a
# German word list. English keeps ASCII alone for exactly that reason, which is why `café` is
# not in it.
LATIN = "abcdefghijklmnopqrstuvwxyz"

ALPHABETS = {
    "en": set(LATIN + "'"),
    "cs": set(LATIN + "áčďéěíňóřšťúůýž"),
    "da": set(LATIN + "æøåé"),
    "de": set(LATIN + "äöüß"),
    "es": set(LATIN + "áéíóúüñ"),
    "fi": set(LATIN + "äöå"),
    "fr": set(LATIN + "àâäæçéèêëîïôöœùûüÿ'"),
    "hu": set(LATIN + "áéíóöőúüű"),
    "it": set(LATIN + "àèéìíòóù'"),
    # Dutch keeps the apostrophe: `'t`, `zo'n` and `'s-gravenhage` are all ordinary words.
    "nl": set(LATIN + "ëïéèáäöüíóú'"),
    "nb": set(LATIN + "æøåé"),
    "pl": set(LATIN + "ąćęłńóśźż"),
    "pt": set(LATIN + "áàâãçéêíóôõúü"),
    # Comma-below only. The corpus is mostly cedilla; see NORMALISE.
    "ro": set(LATIN + "ăâîșț"),
    "sk": set(LATIN + "áäčďéíĺľňóôŕšťúýž"),
    "sv": set(LATIN + "åäöé"),
    # q, w and x are not Turkish letters but arrive with loanwords, and `ı` is a letter in its
    # own right rather than a damaged `i`.
    "tr": set(LATIN + "çğıöşü"),
    "el": set("αβγδεζηθικλμνξοπρστυφχψωςάέήίόύώϊϋΐΰ"),
    "ru": set("абвгдеёжзийклмнопрстуфхцчшщъыьэюя"),
    "sr": set("абвгдђежзијклљмнњопрстћуфхцчџш"),
    "uk": set("абвгґдеєжзиіїйклмнопрстуфхцчшщьюя'\u02bc"),
    "mk": set("абвгдѓежзѕијклљмнњопрстќуфхцчџш"),
}

# Where a layout's language is not what FrequencyWords calls the corpus.
#
# One case: the Norwegian layout is Bokmål and is named `nb`, which is the code Android wants
# for the subtype. OpenSubtitles has it under plain `no`.
CORPUS = {"nb": "no"}


# ---------------------------------------------------------------- normalisation
#
# Two corpora are not spelled the way their keyboard types, and both would otherwise produce a
# dictionary that is technically correct and completely useless.

# Romanian: the corpus overwhelmingly uses the *cedilla* forms, which are a Windows-1250 era
# mistake - `şi` rather than `și`. The layout types the comma-below letters, which is what
# Romanian actually uses, so without this every word containing them fails the alphabet check
# and the language loses most of its vocabulary. Both spellings fold onto the correct one and
# their counts are added together.
RO_FOLD = str.maketrans({"ş": "ș", "ţ": "ț", "Ş": "ș", "Ţ": "ț"})

# Serbian: the layout is Cyrillic and the corpus is Latin, so there is no overlap at all -
# filtering one through the other yields an empty dictionary.
#
# The two scripts are a strict transliteration of each other and Cyrillic is the direction with
# the ambiguity: `nj` is almost always `њ`, but across a morpheme boundary (`injekcija`) it is
# two letters. Those cases are rare enough to be worth the trade - a handful of wrong entries
# against having no Serbian dictionary - but they are wrong entries, and this is where they
# come from if one turns up.
SR_DIGRAPHS = (("dž", "џ"), ("lj", "љ"), ("nj", "њ"))
SR_LETTERS = str.maketrans({
    "a": "а", "b": "б", "c": "ц", "č": "ч", "ć": "ћ", "d": "д", "đ": "ђ", "e": "е",
    "f": "ф", "g": "г", "h": "х", "i": "и", "j": "ј", "k": "к", "l": "л", "m": "м",
    "n": "н", "o": "о", "p": "п", "r": "р", "s": "с", "š": "ш", "t": "т", "u": "у",
    "v": "в", "z": "з", "ž": "ж",
})


def to_cyrillic(word):
    """Serbian Latin to Cyrillic. Digraphs first, or `nj` comes out as two letters."""
    for latin, cyrillic in SR_DIGRAPHS:
        word = word.replace(latin, cyrillic)
    return word.translate(SR_LETTERS)


NORMALISE = {
    "ro": lambda word: word.translate(RO_FOLD),
    "sr": to_cyrillic,
}


# ---------------------------------------------------------------- French elisions
#
# The same wreckage `NT_STEMS` rebuilds for English, in the language where it does the most
# damage. The tokenizer splits at the apostrophe, so the corpus does not contain `c'est` - it
# contains `c'` 4,184,576 times and `est` separately. `c'est` itself appears exactly **once**,
# which is below the floor and would be dropped anyway.
#
# A French dictionary that does not believe in `c'est` is not a French dictionary. It is also
# actively harmful rather than merely lacking: the keyboard corrects what it does not
# recognise, so every elided form anybody types would be corrected into something else.
#
# English's case is easier - `doesn` *is* `doesn't` with the tail cut off, so the stem's own
# count is a good estimate. French elides onto the *following* word, and which word that was
# is exactly what a frequency list threw away. So the stem's count is split evenly between the
# forms it mostly is, which is an estimate and is meant to be read as one: it puts these words
# in roughly the right region of the frequency order, which is all the suggester wants from a
# frequency. What it cannot do is tell `qu'il` from `qu'elle`, and it does not pretend to.
#
# Only stems where the listed forms genuinely account for most of the usage. `l'` and `d'`
# take an arbitrary noun and are left out of the reconstruction for that reason.
ELISIONS = {
    "c'": ["c'est", "c'était"],
    "j'": ["j'ai", "j'étais", "j'aime"],
    "n'": ["n'est", "n'ai", "n'a"],
    "qu'": ["qu'il", "qu'elle", "qu'on"],
    "s'": ["s'il", "s'est"],
    "m'": ["m'a", "m'appelle"],
    "t'": ["t'ai", "t'aime"],
}

# Words whose apostrophe is *inside* them rather than joining two words. The tokenizer splits
# these too and neither half is a word, so there is no stem to rebuild them from - they are
# simply named, the way MODERN names what a 2015 word list cannot know. The count is the
# smallest a rebuilt elision gets, so they sit among ordinary common words rather than at the
# top of the language.
FRENCH_INTACT = ["aujourd'hui", "quelqu'un", "jusqu'à", "presqu'île", "d'accord", "d'abord"]


def restore_elisions(words):
    """
    Puts the elided forms back, and clears away the stems they were rebuilt from.

    Mirrors `restore_contractions`, including the second half: `c'` and `l'` are not French
    words and would otherwise sit near the top of the frequency order being offered
    constantly. Every stem goes, including the ones with no reconstruction - `l'` and `d'`
    take an arbitrary noun, so there is nothing to rebuild, but they are still not words.

    Where the corpus happens to contain the intact form as well - it is inconsistently
    tokenized, so `aujourd'hui` survives with a real count while `c'est` does not - the
    larger of the two is kept. An estimate should not be able to talk down evidence.
    """
    counts = dict(words)
    smallest = None
    for stem, forms in ELISIONS.items():
        count = counts.get(stem, 0)
        if count <= 0:
            continue
        share = max(1, count // len(forms))
        smallest = share if smallest is None else min(smallest, share)
        for form in forms:
            counts[form] = max(counts.get(form, 0), share)
    for stem in [word for word in counts if word.endswith("'")]:
        del counts[stem]
    for word in FRENCH_INTACT:
        counts.setdefault(word, smallest or MIN_COUNT)
    return sorted(counts.items(), key=lambda pair: -pair[1])

# ---------------------------------------------------------------- contractions
#
# The corpus splits every contraction at the apostrophe. It does not contain `don't`; it
# contains `don` 4,158,644 times and a separate fragment `'t`. Two things follow, and both
# are bad enough on their own to make an English keyboard feel broken:
#
#   1. `don't`, `can't`, `I'm`, `it's` - some of the commonest words in the language - are
#      absent entirely, so the keyboard would refuse to believe in them and try to correct
#      them into something else.
#   2. `don`, `doesn`, `isn`, `wasn` are left behind as extremely high-frequency entries.
#      They are not words. `don` would rank around tenth in the whole language and would be
#      offered constantly.
#
# So the -n't family is rebuilt from the wreckage and the wreckage is thrown away. The stem's
# own count is a genuinely good estimate of the contraction's frequency - `doesn` appeared
# 471,037 times and essentially every one of those was `doesn't`.
NT_STEMS = {
    "don": "don't", "doesn": "doesn't", "didn": "didn't", "isn": "isn't",
    "wasn": "wasn't", "aren": "aren't", "weren": "weren't", "couldn": "couldn't",
    "wouldn": "wouldn't", "shouldn": "shouldn't", "haven": "haven't",
    "hasn": "hasn't", "hadn": "hadn't", "ain": "ain't", "mustn": "mustn't",
    "needn": "needn't", "shan": "shan't", "mightn": "mightn't", "oughtn": "oughtn't",
}

# `won` and `can` are the exceptions: both are ordinary English words as well as contraction
# stems, so their counts are a blend and the stem must be kept rather than replaced. The
# contraction is added alongside at a share of it.
BLENDED_STEMS = {"won": ("won't", 0.9), "can": ("can't", 0.35)}

# The pronoun contractions cannot be rebuilt the same way, because there the stem *is* a real
# word: `i`, `you` and `it` carry their own counts and reveal nothing about how often they
# were followed by an apostrophe. These are therefore estimates - a share of the base word's
# count - and are marked as such. What matters is that they exist and land in roughly the
# right part of the ordering; the suggester weighs edit distance alongside frequency, so
# being out by a factor of two here changes nothing anyone can notice.
PRONOUN_CONTRACTIONS = [
    ("i", "i'm", 0.30), ("i", "i've", 0.05), ("i", "i'll", 0.06), ("i", "i'd", 0.05),
    ("it", "it's", 0.35), ("that", "that's", 0.25), ("he", "he's", 0.15),
    ("she", "she's", 0.15), ("there", "there's", 0.25), ("what", "what's", 0.20),
    ("let", "let's", 0.30), ("who", "who's", 0.10), ("here", "here's", 0.15),
    ("you", "you're", 0.10), ("you", "you've", 0.03), ("you", "you'll", 0.04),
    ("you", "you'd", 0.02), ("we", "we're", 0.12), ("we", "we've", 0.04),
    ("we", "we'll", 0.05), ("we", "we'd", 0.02), ("they", "they're", 0.12),
    ("they", "they've", 0.03), ("they", "they'll", 0.03), ("they", "they'd", 0.02),
    ("he", "he'd", 0.03), ("she", "she'd", 0.03), ("he", "he'll", 0.03),
    ("she", "she'll", 0.03), ("would", "would've", 0.04),
    ("could", "could've", 0.04), ("should", "should've", 0.04),
]


def restore_contractions(kept):
    """
    Rebuilds the contractions the corpus tokenizer destroyed. See the notes above.

    Returns a fresh list, still ordered by count, with the stems that are not words removed
    and the contractions they imply put in their place.
    """
    counts = dict(kept)
    out = [(w, c) for w, c in kept if w not in NT_STEMS]

    for stem, contraction in NT_STEMS.items():
        if stem in counts:
            out.append((contraction, counts[stem]))
    for stem, (contraction, share) in BLENDED_STEMS.items():
        if stem in counts:
            out.append((contraction, int(counts[stem] * share)))
    for base, contraction, share in PRONOUN_CONTRACTIONS:
        if base in counts:
            out.append((contraction, int(counts[base] * share)))

    # Anything added twice keeps its largest estimate, and the whole list is reordered
    # because the new entries were appended rather than inserted.
    best = {}
    for word, count in out:
        if count > best.get(word, 0):
            best[word] = count
    return sorted(best.items(), key=lambda pair: -pair[1])

SOURCE = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{0}/{0}_full.txt"


def read_wordlist():
    """The English word list from rule 1 above, lowercased, as a set."""
    sys.stderr.write("fetching %s\n" % WORDLIST)
    with urllib.request.urlopen(WORDLIST) as response:
        raw = response.read().decode("utf-8", "ignore")
    return set(word.strip().lower() for word in raw.split() if word.strip())


def read_speller(language):
    """
    The Hunspell dictionary for [language], or None where there is not one.

    Fetched once into a cache outside the project and read with spylls, which implements
    Hunspell's affix machinery in Python - which is the part that matters for the Slavic
    languages here, where a lexicon of base forms alone would reject most of the inflections
    the corpus is actually made of.
    """
    name = HUNSPELL.get(language)
    if name is None:
        return None
    try:
        from spylls.hunspell import Dictionary
    except ImportError:
        sys.stderr.write(
            "spylls is not installed, so %s cannot be spell-checked and would be built to a\n"
            "lower standard than the rest. Install it with `pip install spylls`.\n" % language
        )
        raise SystemExit(1)

    os.makedirs(HUNSPELL_CACHE, exist_ok=True)
    base = os.path.join(HUNSPELL_CACHE, name)
    for extension in ("dic", "aff"):
        target = "%s.%s" % (base, extension)
        if os.path.exists(target):
            continue
        url = HUNSPELL_SOURCE.format(name, extension)
        sys.stderr.write("fetching %s\n" % url)
        with urllib.request.urlopen(url) as response:
            data = response.read()
        with open(target, "wb") as handle:
            handle.write(data)
    return Dictionary.from_files(base)


def read_words(language):
    """Yields (word, count), most frequent first, already filtered."""
    allowed = ALPHABETS[language]
    # Only English has a word list to check against. Every other language stands on frequency
    # alone, at the lower floor - see MIN_COUNT_NO_WORDLIST.
    known = read_wordlist() | set(MODERN.split()) if language == "en" else None
    speller = read_speller(language) if known is None else None
    fold = NORMALISE.get(language)
    stems = ELISIONS if language == "fr" else ()
    url = SOURCE.format(CORPUS.get(language, language))
    sys.stderr.write("fetching %s\n" % url)
    with urllib.request.urlopen(url) as response:
        raw = response.read().decode("utf-8")

    # A dict rather than a list because normalisation makes words collide: `şi` and `și` are
    # the same Romanian word and their counts belong together. Without a normaliser nothing
    # collides and this is the same list it always was, in the same order.
    kept = {}
    for line in raw.splitlines():
        parts = line.split(" ")
        if len(parts) != 2:
            continue
        word, count = parts[0], parts[1]
        if not count.isdigit():
            continue
        count = int(count)
        floor = MIN_COUNT if known is not None else (
            MIN_COUNT_CHECKED if speller is not None else MIN_COUNT_NO_WORDLIST
        )
        if count < floor:
            # The list is ordered, so the first word below the floor ends the useful part.
            break
        # Length one is allowed, and that is not an oversight. `a` and `i` are words, and `i`
        # is also the stem of four of the commonest contractions in the language - filtering
        # single characters out to be rid of the corpus's stray letters quietly took `i'm`,
        # `i've`, `i'll` and `i'd` with them. The word list is what rejects `b` and `q`; the
        # suggester refuses to offer anything under two characters anyway, so the only thing a
        # one-letter entry is used for is knowing the word exists.
        if not word or len(word) > 32:
            continue
        if fold is not None:
            word = fold(word)
        if not set(word) <= allowed:
            continue
        # A leading apostrophe is always a tokenizer artefact. A trailing one usually is too -
        # except in French, where those stems are precisely what the elided forms are rebuilt
        # from further down, so they are carried this far and discarded there instead.
        if word.startswith("'"):
            continue
        if word.endswith("'") and word not in stems:
            continue
        # Rules 1 to 3. See the note at the top.
        if known is not None and word not in known and count < CORPUS_VOUCHES:
            continue
        # The same question for every other language, asked of a real lexicon - and asked
        # without an appeal to frequency, deliberately. English lets a common-enough unknown
        # word through because a 2015 word list cannot have heard of `selfie`; doing that here
        # would readmit exactly what the dictionary is for. Ukrainian's corpus carries Russian
        # in its commonest few thousand words - `что` is the fifth most frequent thing in it -
        # and any rule that keeps frequent unknowns keeps those. A name the dictionary has
        # never heard of is a word this list does not offer, which costs a suggestion; a
        # foreign word it believes in is a word it will never correct, which costs the typo.
        if speller is not None and word not in stems and not speller.lookup(word):
            continue
        kept[word] = kept.get(word, 0) + count
        if len(kept) >= MAX_WORDS:
            break
    # Sorted rather than left in corpus order: two folded spellings added together can outrank
    # the word that was above both of them.
    return sorted(kept.items(), key=lambda pair: -pair[1])


def scale(count, largest):
    """
    Squashes a raw occurrence count into one byte.

    Logarithmic, because the raw counts span seven orders of magnitude - `you` appears
    28 million times and the last word kept appears three - and a linear scale would round
    all but the commonest few thousand words to zero. What the suggester needs from this is
    the *order*, plus enough resolution to tell a common word from a rare one.
    """
    if count <= 0:
        return 1
    value = 1 + int(254 * math.log(count) / math.log(largest))
    return max(1, min(255, value))


class Node:
    __slots__ = ("children", "terminal", "freq", "offset")

    def __init__(self):
        self.children = {}
        self.terminal = False
        self.freq = 0
        self.offset = 0


def build_trie(words, largest):
    root = Node()
    for word, count in words:
        node = root
        for ch in word:
            node = node.children.setdefault(ch, Node())
        node.terminal = True
        node.freq = scale(count, largest)
    return root


def pack(root):
    """
    Lays the trie out depth-first, writing each node once its children are placed.
    
    A node's edges carry absolute offsets to their children, so a child has to be at a known
    position before its parent can be written - which means writing the deepest nodes first
    and the root last. The root's own offset goes in the header.
    """
    out = bytearray()

    def emit(node):
        # Place every child subtree first, so their offsets are known.
        for ch in sorted(node.children):
            child = node.children[ch]
            if child.children:
                emit(child)
            else:
                child.offset = 0
        offset = len(out)
        out.extend(struct.pack("<H", len(node.children)))
        for ch in sorted(node.children):
            child = node.children[ch]
            flags = 0
            if child.terminal:
                flags |= FLAG_TERMINAL
            if child.children:
                flags |= FLAG_CHILDREN
            out.extend(struct.pack("<HBBI", ord(ch), flags, child.freq, child.offset))
        node.offset = offset

    emit(root)
    return out, root.offset


def main():
    if len(sys.argv) != 3:
        sys.stderr.write("usage: build_dict.py <language> <output>\n")
        return 1
    language, destination = sys.argv[1], sys.argv[2]
    if language not in ALPHABETS:
        sys.stderr.write("no alphabet defined for %r\n" % language)
        return 1

    words = read_words(language)
    if not words:
        sys.stderr.write("nothing survived filtering\n")
        return 1
    if language == "fr":
        before = len(words)
        words = restore_elisions(words)
        sys.stderr.write("rebuilt elisions: %d words -> %d\n" % (before, len(words)))
    if language == "en":
        before = len(words)
        words = restore_contractions(words)
        sys.stderr.write(
            "contractions: %d words in, %d out\n" % (before, len(words))
        )
    largest = words[0][1]
    sys.stderr.write("kept %d words, commonest %r at %d\n" % (len(words), words[0][0], largest))

    root = build_trie(words, largest)
    body, root_offset = pack(root)

    header = struct.pack(
        "<4sBBBBII", MAGIC, VERSION, 0, EDGE_SIZE, 0, root_offset + 16, len(words)
    )
    # Offsets were computed against the body alone, so every one of them shifts by the
    # header's length. Done here rather than during packing so the packer stays unaware of
    # the header entirely.
    body = shift_offsets(body, 16)

    with open(destination, "wb") as handle:
        handle.write(header)
        handle.write(body)
    sys.stderr.write("wrote %s, %.1f MB\n" % (destination, (len(body) + 16) / 1e6))
    return 0


def shift_offsets(body, delta):
    """Adds [delta] to every non-zero child offset, now that the header is in front."""
    view = memoryview(body)
    position = 0
    total = len(body)
    while position < total:
        (count,) = struct.unpack_from("<H", view, position)
        position += 2
        for _ in range(count):
            (offset,) = struct.unpack_from("<I", view, position + 4)
            if offset:
                struct.pack_into("<I", view, position + 4, offset + delta)
            position += EDGE_SIZE
    return body


if __name__ == "__main__":
    sys.exit(main())

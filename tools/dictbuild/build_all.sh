#!/bin/sh
#
# Builds a trie for every language the keyboard has a layout for: English into the app's
# assets, where it ships, and the other twenty-one into `wordlists/`, where nothing reads
# them yet.
#
# Only English is in the APK. The rest are built here because the hard part - the alphabets,
# the corpus quirks, the spell-checking - is done and worth keeping done, and because
# whatever eventually delivers a word list to a phone will want them. `wordlists/` is not
# committed; it is a few tens of megabytes of output waiting for a use.
#
# Nothing here is wired into the Gradle build. Around 250 MB of
# corpora are fetched and thrown away in the process, along with a Hunspell dictionary per
# language; the tries themselves are what remains. Needs `pip install spylls`.
#
# The languages are Layouts.ALL_LANGUAGES, in the same order. Adding one there means adding
# it here and giving it an alphabet in build_dict.py.
#
# Run in parallel, because the slow part is spell-checking a few hundred thousand words
# against a Hunspell dictionary and that is one core's work per language - Czech alone takes
# five minutes, and twenty-two of those in a row is most of an afternoon. Each language is an
# independent process writing its own file, so they simply run at once; `JOBS=1 ./build_all.sh`
# puts it back in order if a run needs to be read as it happens. Each language's output is
# kept aside and printed when everything is done, so the summaries stay readable rather than
# interleaving six at a time.
set -e
cd "$(dirname "$0")"

JOBS="${JOBS:-6}"
OUT="../../wordlists"
mkdir -p "$OUT"

# English is the one carried in the app, so it goes to the assets uncompressed - the APK
# deflates its own assets, and gzipping it here would only mean storing it deflated twice.
# Everything else is stored gzipped, at about 40% of the size, for whenever it is wanted.
BUNDLED="../../app/src/main/assets/keyboard"

# `-n` keeps the name and timestamp out of the gzip header, so rebuilding an unchanged list
# produces an unchanged file rather than a fresh diff every time this is run.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export WORK OUT BUNDLED

LANGUAGES="en cs da de el es fi fr hu it mk nb nl pl pt ro ru sk sr sv tr uk"

printf '%s\n' $LANGUAGES | xargs -P "$JOBS" -I{} sh -c '
    LANGUAGE="$1"
    if [ "$LANGUAGE" = en ]; then
        TARGET="$BUNDLED/en.trie"
    else
        TARGET="$WORK/$LANGUAGE.trie"
    fi
    if python3 build_dict.py "$LANGUAGE" "$TARGET" > "$WORK/$LANGUAGE.log" 2>&1; then
        [ "$LANGUAGE" = en ] || gzip -9 -n -c "$TARGET" > "$OUT/$LANGUAGE.trie.gz"
        printf "  %s\n" "$LANGUAGE"
    else
        printf "  %s FAILED\n" "$LANGUAGE"
    fi
' _ {}

for LANGUAGE in $LANGUAGES; do
    printf '\n=== %s\n' "$LANGUAGE"
    cat "$WORK/$LANGUAGE.log"
done

printf '\n'
ls -l "$OUT"

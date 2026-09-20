package rocks.gorjan.winboard.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import rocks.gorjan.winboard.KeyboardSettings

/**
 * The click a key makes, and whether it makes one.
 *
 * The companion to [KeyboardHaptics], and deliberately a much smaller thing than it. The
 * vibration setting has a strength because where the line falls between "felt" and "the phone
 * shaking in your hand" is a property of the motor and of the hand holding it. A click has no
 * such problem, so this is a switch and nothing more.
 *
 * **It plays through the media stream rather than through [AudioManager.playSoundEffect].**
 * That is the whole of what is unusual here and it is a deliberate choice with a real cost, so
 * it is worth writing down. `playSoundEffect` is the ordinary way to make this noise and it
 * mixes on the *system* stream, which the ringer profile silences - so a keyboard using it is
 * a keyboard that goes quiet the moment the phone is put on silent, which is exactly what was
 * not wanted. There is no stream that ignores every volume setting, and there should not be;
 * the media stream is the one the ringer profile leaves alone. So the click follows the volume
 * keys - turn media right down and it is silent - and it survives silent mode, which is the
 * distinction being asked for.
 *
 * The sounds themselves are still the platform's own, read off the device rather than shipped:
 * `KeypressStandard.ogg` and its three companions are the files `playSoundEffect` would have
 * played, so a key here clicks like a key everywhere else on the phone - which is the whole of
 * what a keypress sound is for. A device that keeps them somewhere unexpected falls back to
 * `playSoundEffect`, which is quieter and silenceable but is better than nothing.
 *
 * **Off unless asked for.** A vibration is felt by the person holding the phone; a sound is
 * heard by everybody in the room, and one that plays on silent is more so, not less. Windows
 * Phone shipped with a click, and this is the switch that gives it back.
 *
 * [enabled] is a field rather than a preference read, for the same reason [KeyboardHaptics]
 * keeps its strength in one: these fire on the touch path, and nothing here goes near
 * `SharedPreferences` with a finger down.
 */
internal object KeyboardSounds {

    @Volatile
    var enabled: Boolean = false

    /** The four sounds, by the effect id they stand in for. Empty until they have loaded. */
    private val sounds = HashMap<Int, Int>(4)

    /** Which of them are actually playable - [SoundPool.load] answers long before the file is. */
    private val ready = HashSet<Int>(4)

    private var pool: SoundPool? = null

    /** For the devices whose UI sounds are not where they are expected to be. */
    private var audio: AudioManager? = null

    /**
     * Takes the setting from [KeyboardSettings] and, the first time it is on, opens the pool.
     *
     * Nothing is loaded until somebody switches the click on, and it is all let go the moment
     * they switch it off: a keyboard that is not clicking has no business holding an audio
     * resource open for the life of the process.
     */
    fun refresh(context: Context, themeManager: KeyboardSettings) {
        enabled = themeManager.getSound()
        if (enabled) open(context) else close()
    }

    private fun open(context: Context) {
        if (audio == null) {
            audio = try {
                context.getSystemService(AudioManager::class.java)
            } catch (e: Exception) {
                null
            }
        }
        if (pool != null) return

        val built = try {
            SoundPool.Builder()
                .setMaxStreams(STREAMS)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Media, which is the part that survives silent mode. Sonification is
                        // the honest description of what this is and it maps to the system
                        // stream, which is silenced - see the note on the object.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            return
        }

        // A file is not playable when `load` returns, only when this says so. A key pressed in
        // between falls through to `playSoundEffect` rather than to silence.
        built.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(ready) { ready.add(sampleId) }
        }
        pool = built

        for ((effect, name) in FILES) {
            val file = find(name) ?: continue
            val id = try {
                built.load(file.absolutePath, 1)
            } catch (e: Exception) {
                continue
            }
            if (id != 0) sounds[effect] = id
        }
    }

    private fun close() {
        pool?.let {
            try {
                it.release()
            } catch (e: Exception) {
                // Nothing useful to do about a pool that will not close.
            }
        }
        pool = null
        sounds.clear()
        synchronized(ready) { ready.clear() }
    }

    /**
     * Where the platform keeps its interface sounds.
     *
     * Probed rather than assumed. There is no public API that names these files - the
     * framework reads its own list out of a private resource - so the path is knowledge about
     * how Android is laid out rather than a promise, and it has moved: `/product` on devices
     * that use the product partition, `/system` on the ones that do not. A device that keeps
     * them somewhere else simply has no files here and takes the fallback.
     */
    private fun find(name: String): File? {
        for (directory in DIRECTORIES) {
            val file = File(directory, name)
            try {
                if (file.canRead()) return file
            } catch (e: SecurityException) {
                // Not readable here. Try the next.
            }
        }
        return null
    }

    /**
     * A key, in whichever of the platform's four voices suits it.
     *
     * @param action what the key does, or null for one that types a character.
     */
    fun key(action: Action?) {
        play(
            when (action) {
                Action.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                Action.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                Action.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
        )
    }

    /** Anything tapped that is not a key: a suggestion, a glyph on the bar, an emoji. */
    fun tap() = play(AudioManager.FX_KEYPRESS_STANDARD)

    private fun play(effect: Int) {
        if (!enabled) return
        val id = sounds[effect]
        val mixer = pool
        if (mixer != null && id != null && synchronized(ready) { id in ready }) {
            try {
                mixer.play(id, VOLUME, VOLUME, PRIORITY, 0, 1f)
                return
            } catch (e: Exception) {
                // Fall through to the platform's own path.
            }
        }
        try {
            // No file, or it has not finished loading. Quieter and silenceable, and still a
            // click - see the note on the object.
            audio?.playSoundEffect(effect, VOLUME)
        } catch (e: Exception) {
            // A keyboard that cannot click still types.
        }
    }

    /**
     * How loud, as a share of full scale.
     *
     * Half, which is what "quieter" means here in the only unit available: these are played
     * at whatever the media volume is, and this is the gain applied on top of it. It has to be
     * a cut of some size rather than none, because a keypress sound written to be mixed at the
     * system stream's own modest level is played rather louder than intended when it is put on
     * the media stream instead - which is the price of it surviving silent mode.
     */
    private const val VOLUME = 0.5f

    /**
     * How many clicks may overlap.
     *
     * Two. Fast typing lands the next key before the last click has finished, and a pool of
     * one cuts the first off to start the second - which turns a run of keys into a stutter.
     * More than two is inaudible: a third simultaneous click is not a sound anybody can pick
     * out of the two already playing.
     */
    private const val STREAMS = 2

    /** Every click matters the same amount, so the number is arbitrary and constant. */
    private const val PRIORITY = 1

    /** The four the platform ships, named as `config_soundEffects` names them. */
    private val FILES = mapOf(
        AudioManager.FX_KEYPRESS_STANDARD to "KeypressStandard.ogg",
        AudioManager.FX_KEYPRESS_SPACEBAR to "KeypressSpacebar.ogg",
        AudioManager.FX_KEYPRESS_DELETE to "KeypressDelete.ogg",
        AudioManager.FX_KEYPRESS_RETURN to "KeypressReturn.ogg"
    )

    private val DIRECTORIES = listOf(
        "/product/media/audio/ui",
        "/system/product/media/audio/ui",
        "/system/media/audio/ui"
    )
}

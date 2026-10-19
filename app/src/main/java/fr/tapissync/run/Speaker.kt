package fr.tapissync.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.tapissync.AppLog
import java.util.Locale

/** Annonces vocales et bips. Baisse le son de la vidéo pendant l'annonce. */
class Speaker(context: Context) {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private var tone: ToneGenerator? = null
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focus: AudioFocusRequest? = null

    var voiceEnabled = true
    var beepEnabled = true

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale.FRANCE)
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = releaseFocus()
                    @Deprecated("API ancienne")
                    override fun onError(utteranceId: String?) = releaseFocus()
                })
                if (!ready) AppLog.add("Voix", "Voix française indisponible (code $r)")
            } else {
                AppLog.add("Voix", "Synthèse vocale indisponible ($status)")
            }
        }
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (_: RuntimeException) {
            null
        }
    }

    fun say(text: String) {
        if (!voiceEnabled || !ready) return
        requestFocus()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}")
    }

    fun beep(long: Boolean = false) {
        if (!beepEnabled) return
        tone?.startTone(
            if (long) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP,
            if (long) 400 else 150,
        )
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        val f = focus ?: return
        focus = null
        audio.abandonAudioFocusRequest(f)
    }

    fun shutdown() {
        releaseFocus()
        tts?.shutdown()
        tts = null
        tone?.release()
        tone = null
    }
}

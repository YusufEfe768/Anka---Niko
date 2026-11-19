package app.niko

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Sürekli dinleme döngüsü. Android'in konuşma tanıması her cümleden sonra durur; bu sınıf onu sürekli
 * yeniden başlatır, takılırsa (bekçi) sıfırlar, hata olursa bekleyip tekrar dener — hiç pes etmez.
 */
class VoiceLoop(private val ctx: Context, private val onHeard: (List<String>) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("niko", Context.MODE_PRIVATE)
    private var rec: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var fails = 0
    private var muted = false
    private val startRunnable = Runnable { listen() }
    private val watchdog = Runnable { restart(300) }      // 25 sn boyunca hiçbir olay gelmezse takılmıştır

    fun start() {
        if (running) return
        restoreVolumesIfCrashed()
        running = true; paused = false; fails = 0
        schedule(100)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        destroy()
        unmuteBeep()
        Bus.mode = 0
    }

    fun pause() {
        paused = true
        handler.removeCallbacks(startRunnable)
        destroy()
        unmuteBeep()
    }

    fun resume() {
        if (!running) return
        paused = false
        schedule(350)
    }

    private fun schedule(ms: Long) {
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, ms)
    }

    private fun restart(ms: Long) { destroy(); unmuteBeep(); schedule(ms) }

    private fun destroy() {
        handler.removeCallbacks(watchdog)
        try { rec?.cancel(); rec?.destroy() } catch (e: Exception) { }
        rec = null
    }

    private fun listen() {
        if (!running || paused) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Bus.log("⚠️", "Mikrofon izni yok."); running = false; return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Bus.log("⚠️", "Bu telefonda konuşma tanıma yok (Google uygulaması gerekli)."); running = false; return
        }
        destroy()
        muteBeep()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Bus.mode = 1 }
            override fun onBeginningOfSpeech() { fails = 0; handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 30000) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                handler.removeCallbacks(watchdog)
                fails = 0
                unmuteBeep()
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                if (list.isNotEmpty()) onHeard(list)
                if (running && !paused) schedule(250)
            }

            override fun onError(error: Int) {
                handler.removeCallbacks(watchdog)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(300)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(1000)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Bus.log("⚠️", "Mikrofon izni yok."); running = false; unmuteBeep()
                    }
                    else -> {
                        fails++
                        if (fails == 3 || fails % 20 == 0) Bus.log("⚠️", "Dinleme hatası ($error). İnternet bağlantısını kontrol et.")
                        restart(minOf(1000L * fails, 8000L))
                    }
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        try { r.startListening(i) } catch (e: Exception) { restart(1000); return }
        handler.postDelayed(watchdog, 25000)
    }

    // ── Başlangıç/bitiş "bip" sesini kısma (en iyi çaba; kapanırsa ses geri verilir) ──
    private val streams = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)

    private fun muteBeep() {
        if (muted || !prefs.getBoolean("mute_beep", true)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ed = prefs.edit()
        var any = false
        for (s in streams) {
            try {
                val v = am.getStreamVolume(s)
                am.setStreamVolume(s, 0, 0)
                ed.putInt("vol_$s", v); any = true
            } catch (e: Exception) { }
        }
        if (any) { ed.putBoolean("vol_muted", true).apply(); muted = true }
    }

    private fun unmuteBeep() {
        if (!muted) return
        restoreVolumes()
        muted = false
    }

    private fun restoreVolumes() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in streams) {
            val v = prefs.getInt("vol_$s", -1)
            if (v >= 0) try { am.setStreamVolume(s, v, 0) } catch (e: Exception) { }
        }
        prefs.edit().putBoolean("vol_muted", false).apply()
    }

    private fun restoreVolumesIfCrashed() {
        if (prefs.getBoolean("vol_muted", false)) restoreVolumes()
    }
}

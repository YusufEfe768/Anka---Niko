package app.niko

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** Konuşmayı anlayıp telefona eylem yaptıran çekirdek. Ekrana bağımlı değil (serviste çalışır). */
class Assistant(private val ctx: Context, private val prefs: SharedPreferences) {
    val phone = PhoneActions(ctx)
    var voice: VoiceLoop? = null
    var onStopRequested: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsInit = false
    private var ttsReady = false
    private val queued = ArrayList<String>()
    private var awakeUntil = 0L
    private var pendingAction: (() -> Unit)? = null
    private var pendingRunnable: Runnable? = null
    private var awaiting: ((String) -> Unit)? = null
    private var endAfter = false
    private val afterTasks = ArrayList<() -> Unit>()

    init {
        tts = TextToSpeech(ctx) { status -> handler.post { onTtsInit(status) } }
    }

    private fun onTtsInit(status: Int) {
        ttsInit = true
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("tr", "TR")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { handler.post { Bus.mode = 2 } }
                override fun onDone(utteranceId: String?) { handler.post { afterSpeech() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { handler.post { afterSpeech() } }
            })
            if (!ttsReady) Bus.log("⚠️", "Telefonda Türkçe ses motoru yok. Ayarlar > Metin okuma bölümünden Türkçe sesi indir.")
        } else {
            Bus.log("⚠️", "Konuşma motoru başlatılamadı.")
        }
        val q = ArrayList(queued)
        queued.clear()
        q.forEach { speakNow(it) }
    }

    // ── Uyandırma / filtre ──
    private fun isAwake() = prefs.getBoolean("chat_mode", false) || System.currentTimeMillis() < awakeUntil

    /** VoiceLoop'tan gelen alternatif cümleler. Ortam sesini "Niko" demedikçe yok sayar. */
    fun onTranscripts(list: List<String>) {
        if (list.isEmpty()) return
        val pick = list.firstOrNull { Wake.extract(it).first } ?: list[0]
        val (woke, rest) = Wake.extract(pick)
        if (!woke && !isAwake()) return
        awakeUntil = System.currentTimeMillis() + 12000
        if (woke && rest.isBlank()) { Bus.log("🗣", pick); say("Efendim?"); return }
        handle(if (woke) rest else pick)
    }

    private fun handle(text: String) {
        Bus.log("🗣", text)
        val cb = awaiting
        if (cb != null) {
            awaiting = null
            if (CmdParser.parse(text) == Cmd.Cancel) { say("Tamam, vazgeçtim."); return }
            cb(text)
            return
        }
        dispatch(CmdParser.parse(text))
    }

    // ── Konuşma ──
    fun say(text: String, log: Boolean = true) {
        if (log) Bus.log("🤖", text)
        if (!ttsInit) { queued.add(text); return }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val t = Brain.cleanForSpeech(text)
        if (!prefs.getBoolean("voice", true) || !ttsReady || t.isBlank()) { handler.post { afterSpeech() }; return }
        voice?.pause()
        val r = tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "niko")
        if (r != TextToSpeech.SUCCESS) handler.post { afterSpeech() }
    }

    private fun afterSpeech() {
        Bus.mode = 0
        val tasks = ArrayList(afterTasks)
        afterTasks.clear()
        tasks.forEach { try { it() } catch (e: Exception) { } }
        awakeUntil = if (endAfter) 0L else System.currentTimeMillis() + 12000
        endAfter = false
        if (pendingAction != null && pendingRunnable == null) {
            val r = Runnable { firePending() }
            pendingRunnable = r
            handler.postDelayed(r, 3500)          // "iptal" diyebilme penceresi
        }
        voice?.resume()
    }

    private fun setPending(a: () -> Unit) { cancelPending(); pendingAction = a }

    private fun cancelPending() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        pendingAction = null
    }

    private fun firePending() {
        val a = pendingAction
        pendingAction = null
        pendingRunnable = null
        try { a?.invoke() } catch (e: Exception) { }
    }

    // ── Komutlar ──
    private fun notFound(name: String) =
        if (!phone.hasContactsPermission()) "Rehbere erişemiyorum, izin vermen gerek." else "$name adlı kişiyi rehberde bulamadım."

    private fun dispatch(c: Cmd) {
        when (c) {
            is Cmd.Cancel -> { if (pendingAction != null) { cancelPending(); say("Tamam, iptal ettim.") } else say("Tamam.") }
            is Cmd.EndCall -> { endAfter = true; say("Görüşürüz! Beni istediğin zaman çağır.") }
            is Cmd.StopListening -> {
                say("Tamam, dinlemeyi bırakıyorum. Beni tekrar uygulamadan başlatırsın.")
                afterTasks.add { onStopRequested?.invoke() }
            }
            is Cmd.Sos -> doSos()
            is Cmd.Help -> say("Ara, mesaj at, alarm ya da zamanlayıcı kur, uygulama aç, yol tarifi al, el fenerini aç, sesi ayarla, pil durumunu söyle, konumunu gönder. Ya da sadece sohbet edelim. Başına Niko demeyi unutma.")
            is Cmd.Battery -> say("Pil yüzde ${phone.batteryPercent()}.")
            is Cmd.TimeNow -> say(timeText())
            is Cmd.DateNow -> say(dateText())
            is Cmd.Flashlight -> say(if (phone.flashlight(c.on)) (if (c.on) "El feneri açık." else "El feneri kapandı.") else "El fenerini kullanamadım.")
            is Cmd.Volume -> { phone.volume(c.dir); say("Tamam.") }
            is Cmd.Timer -> {
                if (c.seconds < 0) {
                    say("Kaç dakikaya kurayım?")
                    awaiting = { t -> dispatch(CmdParser.parse("zamanlayıcı kur " + (if (t.trim().all { it.isDigit() }) "$t dakika" else t))) }
                } else {
                    phone.setTimer(c.seconds, c.label)
                    say("${fmtDur(c.seconds)} için zamanlayıcı kuruldu.")
                }
            }
            is Cmd.Alarm -> {
                if (c.hour < 0) {
                    say("Saat kaça kurayım?")
                    awaiting = { t -> dispatch(CmdParser.parse("alarm kur " + (if (t.trim().all { it.isDigit() }) "${t.trim()}:00" else t))) }
                } else {
                    phone.setAlarm(c.hour, c.minute, c.label)
                    say(String.format(Locale("tr"), "Alarm %02d:%02d için kuruldu.", c.hour, c.minute))
                }
            }
            is Cmd.OpenApp -> {
                val label = phone.openApp(c.name)
                say(if (label != null) "$label açılıyor." else "${c.name} adlı bir uygulama bulamadım.")
            }
            is Cmd.OpenSettings -> { phone.openSettings(c.kind); say("Ayarlar açılıyor.") }
            is Cmd.Navigate -> { phone.navigate(c.query, c.nearby); say(if (c.nearby) "Yakındaki ${c.query} aranıyor." else "${c.query} için yol tarifi açılıyor.") }
            is Cmd.Search -> { phone.webSearch(c.query); say("${c.query} aranıyor.") }
            is Cmd.YouTube -> { phone.youtube(c.query); say("YouTube'da ${c.query} açılıyor.") }
            is Cmd.Call -> {
                val contact = if (c.number == null) phone.findContact(c.name) else null
                val number = c.number ?: contact?.number
                if (number == null) { say(notFound(c.name)); return }
                val who = contact?.name ?: c.name
                say("$who aranıyor. İptal etmek için iptal de.")
                setPending { if (!phone.call(number)) say("Arama başlatılamadı.") }
            }
            is Cmd.Sms -> {
                val contact = if (c.number == null) phone.findContact(c.name) else null
                val number = c.number ?: contact?.number
                val who = contact?.name ?: c.name
                if (number == null) { say(notFound(c.name)); return }
                if (c.body.isBlank()) {
                    say("$who için ne yazayım?")
                    awaiting = { t -> dispatch(Cmd.Sms(c.name, number, t, c.whatsapp)) }
                    return
                }
                if (c.whatsapp) {
                    phone.openWhatsApp(number, c.body)
                    say("WhatsApp'ta $who için mesajı hazırladım. Gönder tuşuna sen bas.")
                    return
                }
                say("$who için mesaj gönderiyorum: ${c.body}. İptal etmek için iptal de.")
                setPending { say(if (phone.sendSms(number, c.body)) "Mesaj gönderildi." else "Mesaj gönderilemedi. SMS iznini kontrol et.") }
            }
            is Cmd.SendLocation -> {
                val contact = if (c.name.isNotBlank()) phone.findContact(c.name) else null
                val emergency = prefs.getString("emergency", "")?.ifBlank { null }
                val number = contact?.number ?: (if (c.name.isBlank()) emergency else null)
                if (number == null) {
                    say(if (c.name.isBlank()) "Acil durum kişisi ayarlı değil. Ayarlardan ekle." else notFound(c.name))
                    return
                }
                val loc = phone.lastLocation()
                if (loc == null) { say("Konumu alamadım. Konum açık mı? Haritalar'ı bir kez açıp tekrar dene."); return }
                say("Konumunu ${contact?.name ?: "acil kişine"} gönderiyorum. İptal etmek için iptal de.")
                setPending { say(if (phone.sendSms(number, "Konumum: " + mapsLink(loc))) "Konum gönderildi." else "Konum gönderilemedi.") }
            }
            is Cmd.Reply -> say(c.text)
            is Cmd.Chat -> chat(c.text)
        }
    }

    private fun doSos() {
        val emergency = prefs.getString("emergency", "")?.ifBlank { null }
        val loc = phone.lastLocation()
        val msg = "Acil yardım lazım! " + (if (loc != null) "Konumum: " + mapsLink(loc) else "Konumu alamadım, beni arayın.")
        say("Acil durum! " + (if (emergency != null) "Acil kişine konumunu gönderiyorum ve " else "") + "112'yi arıyorum. İptal etmek için iptal de.")
        setPending {
            if (emergency != null) phone.sendSms(emergency, msg)
            phone.call("112")
        }
    }

    private fun chat(text: String) {
        val url = prefs.getString("server", "") ?: ""
        val pin = prefs.getString("pin", "") ?: ""
        executor.execute {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val reply = Brain.local(text, hour)
                ?: (if (url.isNotBlank()) Brain.askServer(url, pin, text, TimeZone.getDefault().id) else null)
                ?: Brain.fallback(url.isNotBlank())
            handler.post { say(reply) }
        }
    }

    // ── Yardımcılar ──
    private fun mapsLink(l: Location) = String.format(Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", l.latitude, l.longitude)

    private fun fmtDur(s: Int): String = when {
        s >= 3600 && s % 3600 == 0 -> "${s / 3600} saatlik"
        s >= 3600 -> "${s / 3600} saat ${(s % 3600) / 60} dakikalık"
        s >= 60 && s % 60 == 0 -> "${s / 60} dakikalık"
        s >= 60 -> "${s / 60} dakika ${s % 60} saniyelik"
        else -> "$s saniyelik"
    }

    private fun timeText(): String {
        val c = Calendar.getInstance()
        return String.format(Locale("tr"), "Saat %02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private val days = arrayOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
    private val months = arrayOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")

    private fun dateText(): String {
        val c = Calendar.getInstance()
        return "Bugün ${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}, ${days[c.get(Calendar.DAY_OF_WEEK) - 1]}."
    }

    fun shutdown() {
        cancelPending()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        executor.shutdownNow()
    }
}

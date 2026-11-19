package app.niko

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/** Niko'nun kişiliği (özgün replikler) + isteğe bağlı sunucu beyni. */
object Brain {
    private val rnd = Random()
    private fun pick(l: List<String>) = l[rnd.nextInt(l.size)]

    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> pick(listOf("Günaydın! Kahveni aldıysan başlayabiliriz.", "Günaydın! Bugün hangi işi birlikte hallediyoruz?"))
        in 12..17 -> pick(listOf("Selam! Günün ortasında yakaladım seni. Ne lazım?", "Merhaba! Enerjim tam, sen söyle."))
        in 18..22 -> pick(listOf("İyi akşamlar! Günün nasıl geçti, anlat bakalım.", "Selam! Akşam modundayım, sohbet de olur iş de."))
        else -> pick(listOf("Hâlâ ayakta mısın? Ben hep buradayım, ama uykuyu da ihmal etme.", "Gece kuşu seni! Ne yapıyoruz?"))
    }

    fun cleanForSpeech(t: String): String =
        t.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "").replace(Regex("\\s+"), " ").trim().take(400)

    private val GREETS = setOf("merhaba", "selam", "hey", "slm", "mrb", "gunaydin", "iyi aksamlar", "iyi geceler", "naber", "ne haber", "hi", "hello")
    private val CRISIS = Regex("kendime zarar|intihar|olmek istiyorum|yasamak istemiyorum|kendimi oldur")
    private val RED = Regex("gogus agri|nefes alam|bayildim|bayiliyorum|siddetli kanama|zehirlen|felc|kalp krizi")
    private val MED = Regex("atesim|ates cikt|hastayim|basim agri|karnim agri|bogazim agri|oksur|mide bulan")
    private val VOICE = Regex("sesimi taklit|ses taklidi|ses klon|birinin sesini|sesini taklit")

    fun safety(f: String): String? = when {
        CRISIS.containsMatchIn(f) ->
            "Bunu bana söylediğin için teşekkürler; yanındayım. Şu an yalnız kalma: güvendiğin birine hemen yaz ya da yanına git. " +
                "Kendini tehlikede hissediyorsan 112'yi ara. İstersen 'acil durum' de, konumunu acil kişine göndereyim."
        RED.containsMatchIn(f) ->
            "Bu belirtiler acil olabilir. Beklemeden 112'yi ara ya da en yakın acile git. 'Acil durum' dersen ben arayayım."
        MED.containsMatchIn(f) ->
            "Teşhis koyamam, doktor değilim. Dinlen, su iç, belirtileri not et. Ateş yüksek ya da uzun sürüyorsa, nefes darlığı ya da şiddetli ağrı varsa hemen sağlık kuruluşuna git ya da 112'yi ara."
        else -> null
    }

    /** Sunucuya gitmeden verilebilen cevaplar. */
    fun local(text: String, hour: Int): String? {
        val f = CmdParser.fold(text).trim(' ', '?', '!', '.')
        safety(f)?.let { return it }
        if (VOICE.containsMatchIn(f)) return "Ses taklidi yapmıyorum; başkasının sesini taklit etmek kötüye kullanılabilir. Ama kendi sesimle istediğin her şeyi okurum."
        if (f in GREETS) return greeting(hour)
        return when {
            Regex("nasilsin|naber|iyi misin").containsMatchIn(f) -> pick(listOf("İyiyim, sen konuşunca daha da iyi oluyorum! Sen nasılsın?", "Harikayım! Bugün neler yapıyoruz?"))
            Regex("tesekkur|sag ol|eyvallah").containsMatchIn(f) -> pick(listOf("Rica ederim!", "Ne demek, her zaman!"))
            Regex("kimsin|adin ne").containsMatchIn(f) -> "Ben Niko, senin telefondaki sesli asistanınım. Ara, mesaj at, alarm kur, uygulama aç; sadece söyle."
            Regex("saka|fikra|guldur").containsMatchIn(f) -> pick(listOf(
                "Programcı markete gider. Eşi der ki: iki ekmek al, süt varsa altı tane al. Programcı altı ekmekle döner.",
                "Telefonum bana neden küsmüş? Çünkü hep şarjını bekletiyorum, ilgi istiyor."))
            Regex("motive|moral ver|cesaret ver").containsMatchIn(f) -> pick(listOf(
                "Büyük işler küçük adımlarla başlar. Bugün bir adım at, ben de seninleyim.",
                "Zor günler geçer, sen kalırsın. Devam et."))
            Regex("uzgunum|mutsuzum|canim sikkin|moralim bozuk").containsMatchIn(f) -> "Yanındayım. İstersen içini dök, acele yok; ben dinlerim."
            Regex("yoruldum|bittim|tukendim|stresliyim").containsMatchIn(f) -> "Derin bir nefes al: dört saniye içeri, dört saniye tut, dört saniye dışarı. Sonra ne olduğunu anlat."
            Regex("mutluyum|basardim|kazandim").containsMatchIn(f) -> "Yaaa çok güzel! Bunu kutlamak lazım, anlat bakalım!"
            else -> null
        }
    }

    fun fallback(serverConfigured: Boolean): String =
        if (serverConfigured) "Bunu şu an bulamadım. Biraz sonra tekrar dener misin?"
        else "Bunu bilmiyorum. Ayarlardan sunucu adresini girersen çok daha fazla şey bilirim. Telefonla ilgili işleri ise şimdiden yapabilirim: ara, mesaj at, alarm kur, uygulama aç."

    /** Bloklayıcı: arka plan iş parçacığından çağır. */
    fun askServer(base: String, pin: String, text: String, tz: String): String? {
        return try {
            val con = URL(base.trim().trimEnd('/') + "/chat").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.connectTimeout = 8000
            con.readTimeout = 15000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("message", text).put("pin", pin).put("tz", tz).toString()
            con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            if (code == 401) return "Sunucu PIN'i kabul etmedi. Ayarlardan PIN'i kontrol et."
            if (code != 200) return null
            val s = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(s).optString("response").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}

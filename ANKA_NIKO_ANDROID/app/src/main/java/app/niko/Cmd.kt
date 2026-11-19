package app.niko

import java.util.Locale

/** Konuşmadan çıkarılan komutlar. Android'e bağımlı değil → JVM'de test edilebilir. */
sealed class Cmd {
    data class Call(val name: String, val number: String?) : Cmd()
    data class Sms(val name: String, val number: String?, val body: String, val whatsapp: Boolean) : Cmd()
    data class SendLocation(val name: String) : Cmd()
    data class Alarm(val hour: Int, val minute: Int, val label: String) : Cmd()      // hour<0: saat söylenmedi
    data class Timer(val seconds: Int, val label: String) : Cmd()                    // seconds<0: süre söylenmedi
    data class OpenApp(val name: String) : Cmd()
    data class OpenSettings(val kind: String) : Cmd()
    data class Navigate(val query: String, val nearby: Boolean) : Cmd()
    data class Search(val query: String) : Cmd()
    data class YouTube(val query: String) : Cmd()
    data class Flashlight(val on: Boolean) : Cmd()
    data class Volume(val dir: Int) : Cmd()                                          // +1 aç, -1 kıs, 0 sessiz
    data class Reply(val text: String) : Cmd()
    data class Chat(val text: String) : Cmd()
    object Battery : Cmd()
    object TimeNow : Cmd()
    object DateNow : Cmd()
    object Sos : Cmd()
    object Cancel : Cmd()
    object EndCall : Cmd()
    object Help : Cmd()
    object StopListening : Cmd()
}

object CmdParser {
    private val TR = Locale("tr", "TR")

    fun fold(s: String): String {
        val l = s.lowercase(TR)
        val sb = StringBuilder(l.length)
        for (c in l) sb.append(
            when (c) {
                'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'
                'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'; else -> c
            }
        )
        return sb.toString()
    }

    fun normPhone(p: String): String {
        var d = p.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        else if (d.startsWith("0")) d = "90" + d.substring(1)
        else if (d.length == 10 && d[0] == '5') d = "90$d"
        return if (d.length in 10..15) d else ""
    }

    /** "anneyi", "ahmet'e" ↔ rehberdeki "Anne" / "Ahmet Yılmaz". 0 = eşleşme yok; büyük = daha iyi. */
    fun nameScore(spoken: String, contactName: String): Int {
        val w = fold(spoken).filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return 0
        val foldedName = fold(contactName)
        val full = foldedName.filter { it.isLetterOrDigit() }
        val first = foldedName.trim().split(Regex("\\s+")).firstOrNull().orEmpty().filter { it.isLetterOrDigit() }
        var best = 0
        for (n in listOf(full, first)) if (n.length >= 2 && (w == n || w.startsWith(n))) best = maxOf(best, n.length)
        return best
    }

    private val NUM_WORDS = listOf(
        "yirmi bes" to 25, "yirmi dort" to 24, "yirmi uc" to 23, "yirmi iki" to 22, "yirmi bir" to 21, "yirmi" to 20,
        "otuz bes" to 35, "otuz" to 30, "kirk bes" to 45, "kirk" to 40, "elli bes" to 55, "elli" to 50,
        "on dokuz" to 19, "on sekiz" to 18, "on yedi" to 17, "on alti" to 16, "on bes" to 15, "on dort" to 14,
        "on uc" to 13, "on iki" to 12, "on bir" to 11, "on" to 10,
        "dokuz" to 9, "sekiz" to 8, "yedi" to 7, "alti" to 6, "bes" to 5, "dort" to 4, "uc" to 3, "iki" to 2, "bir" to 1
    )

    private fun wordsToDigits(f: String): String {
        var s = f
        for ((w, n) in NUM_WORDS) s = s.replace(Regex("\\b$w\\b"), n.toString())
        return s
    }

    /** "10 dakika", "1 saat 30 dakika", "yarım saat", "bir buçuk saat" → saniye. */
    fun duration(f0: String): Int? {
        val f = wordsToDigits(f0)
        var total = 0
        var found = false
        if (Regex("\\byarim saat\\b").containsMatchIn(f)) { total += 1800; found = true }
        for (m in Regex("(\\d+)\\s*bucuk\\s*saat\\b").findAll(f)) { total += m.groupValues[1].toInt() * 3600 + 1800; found = true }
        for (m in Regex("(\\d+)\\s*(saat|dakika|dk|saniye|sn)\\b").findAll(f)) {
            val n = m.groupValues[1].toInt()
            total += when (m.groupValues[2]) { "saat" -> n * 3600; "dakika", "dk" -> n * 60; else -> n }
            found = true
        }
        return if (found && total in 1..86400) total else null
    }

    private fun parseAlarmTime(f0: String): Pair<Int, Int>? {
        val f = wordsToDigits(f0).replace('\'', ' ')
        var h: Int
        var mi = 0
        val m1 = Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").find(f)
        if (m1 != null) {
            h = m1.groupValues[1].toInt(); mi = m1.groupValues[2].toInt()
        } else {
            val half = Regex("\\b(\\d{1,2})\\s*bucu[kg]\\w*\\b").find(f)
            val m = half ?: Regex("\\b(?:saat\\s*)?(\\d{1,2})\\b(?!\\s*(?:dk|dakika|saniye|sn))").find(f) ?: return null
            h = m.groupValues[1].toInt()
            if (half != null) mi = 30
        }
        if (Regex("\\b(aksam|ogleden sonra|ikindi)\\b").containsMatchIn(f) && h < 12) h += 12
        if (Regex("\\bgece\\b").containsMatchIn(f) && h in 7..11) h += 12
        return if (h in 0..23 && mi in 0..59) h to mi else null
    }

    private fun rx(p: String) = Regex(p)

    fun parse(text: String): Cmd {
        val raw = text.trim()
        if (raw.isEmpty()) return Cmd.Chat(raw)
        val foldedFull = fold(raw)
        val f = foldedFull.trimEnd('?', '!', '.', ' ', ',')
        val foldOk = foldedFull.length == raw.length
        fun rawSlice(r: IntRange): String =
            if (foldOk && r.last < raw.length) raw.substring(r.first, r.last + 1).trim() else f.substring(r.first, r.last + 1)
        fun rawFrom(start: Int): String = (if (foldOk) raw.substring(start) else f.substring(start)).trim()
        fun leftover(patterns: List<Regex>): String {
            if (!foldOk) return ""
            val del = BooleanArray(raw.length)
            for (p in patterns) for (m in p.findAll(f)) for (i in m.range) if (i < del.size) del[i] = true
            val sb = StringBuilder()
            for (i in raw.indices) if (!del[i]) sb.append(raw[i])
            return sb.toString().replace(Regex("\\s+"), " ").trim(' ', ',', '.', '-', ':')
        }

        if (rx("^(iptal|vazgec|yapma|dur|bos ver|bosver)( lutfen)?( niko)?$").matches(f)) return Cmd.Cancel
        if (rx("^(dinlemeyi birak|dinlemeyi kapat|dinlemeyi durdur|niko uyu|uyu)$").matches(f)) return Cmd.StopListening
        if (rx("^(kapat|gorusuruz|hoscakal|hosca kal|konusmayi bitir|tamam kapat|bu kadar|bye)( niko)?$").matches(f)) return Cmd.EndCall
        if (rx("\\b(imdat|acil durum|tehlikedeyim|sos|kacirildim|acil yardim)\\b").containsMatchIn(f)) return Cmd.Sos
        if (rx("^(ne yapabilirsin|neler yapabilirsin|yardim|yardim et|komutlar|nasil kullanilir)$").matches(f)) return Cmd.Help
        if (rx("\\b(pil|sarj|batarya)\\w*\\b.*\\b(kac|ne kadar|yuzde|durum\\w*)\\b").containsMatchIn(f) ||
            rx("^(pil|sarj|batarya)$").matches(f)) return Cmd.Battery
        if (rx("^(saat kac|simdi saat kac|saat ne|saati soyle)$").matches(f)) return Cmd.TimeNow
        if (rx("^(bugun gunlerden ne|bugun ayin kaci|bugunun tarihi|tarih ne|hangi gundeyiz|bugun hangi gun|tarih)$").matches(f)) return Cmd.DateNow

        if (rx("\\b(el feneri\\w*|fener\\w*|flas\\w*|flash\\w*)\\b").containsMatchIn(f) &&
            rx("\\b(ac|yak|calistir|kapat|sondur)\\w*\\b").containsMatchIn(f))
            return Cmd.Flashlight(!rx("\\b(kapat|sondur)\\w*\\b").containsMatchIn(f))

        if (rx("\\bsessize\\b|^sessiz$|\\bsesi kapat\\b").containsMatchIn(f)) return Cmd.Volume(0)
        if (rx("\\bses\\w*\\s+(?:biraz\\s+)?(ac|arttir|artir|yukselt|yuksel)\\w*").containsMatchIn(f)) return Cmd.Volume(1)
        if (rx("\\bses\\w*\\s+(?:biraz\\s+)?(kis|azalt|dusur|alcalt)\\w*").containsMatchIn(f)) return Cmd.Volume(-1)

        // Zamanlayıcı / alarm / "10 dk sonra hatırlat"
        val dur = duration(f)
        val alarmWord = rx("\\balarm\\w*\\b").containsMatchIn(f)
        val timerTrig = rx("zamanlayici|sayac|geri sayim|sonra\\s+.*(hatirlat|uyar|haber ver)|hatirlat\\w*.*sonra|beni ara|beni uyar|sonra\\s+(?:bana\\s+)?(?:ara|uyar)\\w*")
            .containsMatchIn(f)
        if (dur != null && (timerTrig || (alarmWord && f.contains("sonra")))) {
            val label = leftover(listOf(
                rx("\\b(?:\\d+|bir|iki|uc|dort|bes|alti|yedi|sekiz|dokuz|on|yirmi|otuz|kirk|elli|yarim)(?:\\s+(?:bes|bucuk))?\\s*(?:saat|dakika|dk|saniye|sn)\\b"),
                rx("\\b(zamanlayici\\w*|sayac\\w*|geri sayim\\w*|alarm\\w*|sonra|kur\\w*|ayarla\\w*|hatirlat\\w*|uyar\\w*|haber ver\\w*|beni|bana|ara|lutfen|icin)\\b")
            ))
            return Cmd.Timer(dur, label.ifBlank { "Niko" })
        }
        if (rx("\\bbeni ara\\w*\\b").containsMatchIn(f))
            return Cmd.Reply("Ben seni arayamam ama '10 dakika sonra hatırlat' dersen zamanlayıcı kurarım.")
        if (rx("zamanlayici|sayac|geri sayim").containsMatchIn(f)) return Cmd.Timer(-1, "Niko")
        if (alarmWord) {
            if (rx("\\b(iptal|sil|kapat)\\w*").containsMatchIn(f)) return Cmd.Reply("Alarmı Saat uygulamasından silebilirsin.")
            val t = parseAlarmTime(f)
            return if (t != null) Cmd.Alarm(t.first, t.second, "Niko") else Cmd.Alarm(-1, -1, "Niko")
        }

        if (rx("\\b(wifi|wi fi|bluetooth|ayarlar\\w*)\\b").containsMatchIn(f) && rx("\\b(ac|git)\\w*").containsMatchIn(f)) {
            return Cmd.OpenSettings(if (f.contains("bluetooth")) "bluetooth" else if (f.contains("wifi") || f.contains("wi fi")) "wifi" else "settings")
        }

        rx("^konum\\w*\\s+(?:(\\S+)\\s+)?(?:gonder|at|yolla|paylas)\\w*$").find(f)?.let { m ->
            return Cmd.SendLocation(m.groups[1]?.let { rawSlice(it.range) } ?: "")
        }

        // Mesaj: "anneye mesaj at geliyorum", "ahmet'e whatsapp yaz selam"
        rx("^(?:whatsapp|vatsap)(?:'?tan)?\\s+(\\S+)\\s+(?:mesaj\\s+)?(?:at|gonder|yaz|yolla)\\s*(.*)$").find(f)?.let { m ->
            val w = m.groupValues[1]
            val num = if (w[0].isDigit() || w[0] == '+') normPhone(w).ifEmpty { null } else null
            return Cmd.Sms(rawSlice(m.groups[1]!!.range), num?.let { "+$it" }, rawFrom(m.groups[2]!!.range.first), true)
        }
        rx("^(\\S+)\\s+(mesaj|sms|whatsapp(?:'?tan)?|vatsap(?:'?tan)?|wp)\\s*(?:at|gonder|yaz|yolla)?\\s*[:,\\-]?\\s*(.*)$").find(f)?.let { m ->
            val ch = m.groupValues[2]
            val wa = ch.startsWith("whatsapp") || ch.startsWith("vatsap") || ch == "wp"
            val w = m.groupValues[1]
            val num = if (w[0].isDigit() || w[0] == '+') normPhone(w).ifEmpty { null } else null
            return Cmd.Sms(rawSlice(m.groups[1]!!.range), num?.let { "+$it" }, rawFrom(m.groups[3]!!.range.first), wa)
        }

        // YouTube / müzik
        rx("^youtube\\w*(?:'?(?:da|de))?\\s+(.+?)\\s+(?:ac|cal|ara|izle)\\w*$").find(f)?.let { return Cmd.YouTube(rawSlice(it.groups[1]!!.range)) }
        rx("^youtube\\w*(?:'?(?:da|de))?\\s+(?:ara|cal|ac|izle)\\w*\\s+(.+)$").find(f)?.let { return Cmd.YouTube(rawSlice(it.groups[1]!!.range)) }
        rx("^(muzik\\w*)\\s+(?:ac|cal)\\w*$").find(f)?.let { return Cmd.YouTube("müzik") }
        rx("^(.+?)\\s+(?:cal|calsana|calar misin|dinlet)$").find(f)?.let { return Cmd.YouTube(rawSlice(it.groups[1]!!.range)) }

        // İnternette ara
        rx("^(?:google\\w*|internet\\w*)(?:'?(?:da|de))?\\s+ara\\w*\\s+(.+)$").find(f)?.let { return Cmd.Search(rawSlice(it.groups[1]!!.range)) }
        rx("^(?:google\\w*|internet\\w*)(?:'?(?:da|de))?\\s+(.+?)\\s+ara\\w*$").find(f)?.let { return Cmd.Search(rawSlice(it.groups[1]!!.range)) }

        // Yol tarifi
        rx("^(?:en yakin|yakinimdaki|yakinimda|yakinlarda|yakin)\\s+(.+)$").find(f)?.let { return Cmd.Navigate(rawSlice(it.groups[1]!!.range), true) }
        rx("^yol tarifi\\s+(.+)$").find(f)?.let { return Cmd.Navigate(rawSlice(it.groups[1]!!.range), false) }
        rx("^(.+?)\\s+yol tarifi$").find(f)?.let { return Cmd.Navigate(rawSlice(it.groups[1]!!.range), false) }
        rx("^(.+?)'?(?:e|a|ye|ya)\\s+(?:git|gotur|navigasyon\\w*|rota\\w*|gitmek istiyorum)$").find(f)?.let {
            return Cmd.Navigate(rawSlice(it.groups[1]!!.range), false)
        }

        // Arama: "anneyi ara", "112'yi ara"
        rx("^(?:bana\\s+)?(?:bir\\s+)?(.+?)\\s+(?:ara|arasana|arar misin|arayabilir misin|arayalim)$").find(f)?.let { m ->
            val nameF = m.groupValues[1].removePrefix("lutfen ").trim()
            if (nameF.startsWith("niko")) return Cmd.Reply("Ben zaten buradayım 😄")
            val nameRaw = rawSlice(m.groups[1]!!.range)
            if (nameF.isNotEmpty() && (nameF[0].isDigit() || nameF[0] == '+')) {
                val d = nameF.filter { it.isDigit() || it == '+' }
                if (d == "112") return Cmd.Call("112", "112")
                val n = normPhone(d)
                return if (n.isNotEmpty()) Cmd.Call(nameRaw, "+$n") else Cmd.Reply("Numarayı anlayamadım: $nameRaw")
            }
            if (nameF.split(' ').size <= 3) return Cmd.Call(nameRaw, null)
        }

        // Uygulama aç: "youtube'u aç", "kamerayı aç"
        rx("^(.+?)'?\\s*(?:u|i|yi|yu|si|su|ni|nu)?\\s*(?:uygulamasini\\s+|uygulamasi\\s+)?(?:ac|baslat|calistir)$").find(f)?.let { m ->
            val n = rawSlice(m.groups[1]!!.range)
            if (n.split(' ').size <= 3) return Cmd.OpenApp(n)
        }
        return Cmd.Chat(raw)
    }
}

/** Sürekli dinlemede yalnızca "Niko" denince tepki vermek için. */
object Wake {
    private val rx = Regex("\\b(?:hey |hay |ey |he )?(niko|nico|nikko|neko|nigo)\\b")

    /** (uyandırma kelimesi geçti mi, kelimeden önceki/sonraki komut) */
    fun extract(raw: String): Pair<Boolean, String> {
        val f = CmdParser.fold(raw)
        val m = rx.find(f) ?: return false to raw.trim()
        val src = if (f.length == raw.length) raw else f
        val before = src.substring(0, m.range.first)
        var after = src.substring(m.range.last + 1)
        val hadApostrophe = after.startsWith("'") || after.startsWith("’")
        after = after.trimStart('\'', '’')
        if (hadApostrophe) after = after.replace(Regex("^(?:yu|yi|ya|ye|nu|ni|na|ne|u|i|a|e)\\b\\s*", RegexOption.IGNORE_CASE), "")
        val cmd = (if (after.isBlank()) before else after).trim(' ', ',', '.', '!', '?', '-')
        return true to cmd
    }
}

package app.niko

/** Servis ↔ ekran arasında paylaşılan durum (aynı süreç). */
object Bus {
    private val lines = ArrayList<String>()
    @Volatile var mode = 0            // 0 boşta, 1 dinliyor, 2 konuşuyor
    @Volatile var running = false
    @Volatile var appVisible = false
    @Volatile var version = 0

    fun log(who: String, text: String) {
        synchronized(lines) {
            lines.add("$who $text")
            if (lines.size > 120) lines.removeAt(0)
            version++
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n\n") }
}

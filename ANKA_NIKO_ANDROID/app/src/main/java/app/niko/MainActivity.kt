package app.niko

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var orb: OrbView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var mainBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var lastVersion = -1
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 400) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("niko", MODE_PRIVATE)
        buildUi()
        if (!prefs.getBoolean("first_done", false)) {
            prefs.edit().putBoolean("first_done", true).apply()
            Bus.log("🤖", "Merhaba! Ben Niko. Dinlemeye başlamak için izinleri vermen gerek; birazdan soracağım.")
            startFlow()
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.appVisible = true
        handler.post(tick)
        if (!Bus.running && prefs.getBoolean("auto", true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        }
    }

    override fun onPause() {
        Bus.appVisible = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ── Arayüz ──
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070B16.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(16))
            fitsSystemWindows = true
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "Niko"; textSize = 24f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply { text = "⚙"; textSize = 18f; setOnClickListener { showSettings() } })
        root.addView(top)

        scroll = ScrollView(this)
        logView = TextView(this).apply { textSize = 15f; setTextColor(0xFFE8EEFC.toInt()); setPadding(dp(4), dp(8), dp(4), dp(8)) }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        orb = OrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(170)))
        statusView = TextView(this).apply { textSize = 14f; setTextColor(0xFF8AA0C8.toInt()); gravity = Gravity.CENTER }
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        mainBtn = Button(this).apply {
            textSize = 17f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xFF7B5CFF.toInt()) }
            setOnClickListener { if (Bus.running) stopListening() else startFlow() }
        }
        root.addView(mainBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
        setContentView(root)
    }

    private fun refresh() {
        if (Bus.version != lastVersion) {
            lastVersion = Bus.version
            logView.text = Bus.text()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        orb.mode = Bus.mode
        mainBtn.text = if (Bus.running) "⏹  Dinlemeyi durdur" else "🎙  Sürekli dinlemeyi başlat"
        statusView.text = when {
            !Bus.running -> "Kapalı"
            Bus.mode == 2 -> "Konuşuyor…"
            Bus.mode == 1 -> if (prefs.getBoolean("chat_mode", false)) "Dinliyorum — konuşabilirsin" else "Dinliyorum — başına “Niko” de"
            else -> "Hazır"
        }
    }

    // ── İzinler / servis ──
    private fun perms(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun startFlow() {
        val missing = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startListening() else requestPermissions(missing.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else Bus.log("⚠️", "Mikrofon izni olmadan dinleyemem. Ayarlar > Uygulamalar > Niko > İzinler.")
    }

    private fun startListening() {
        prefs.edit().putBoolean("auto", true).apply()
        try { startForegroundService(Intent(this, ListenService::class.java)) }
        catch (e: Exception) { Bus.log("⚠️", "Servis başlatılamadı: ${e.message}") }
    }

    private fun stopListening() {
        prefs.edit().putBoolean("auto", false).apply()
        stopService(Intent(this, ListenService::class.java))
    }

    // ── Ayarlar ──
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun field(hint: String, key: String, pw: Boolean = false, phone: Boolean = false): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                setText(prefs.getString(key, ""))
                inputType = when { pw -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; phone -> InputType.TYPE_CLASS_PHONE; else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
            }
            box.addView(e); return e
        }
        fun check(label: String, key: String, def: Boolean): CheckBox {
            val c = CheckBox(this).apply { text = label; isChecked = prefs.getBoolean(key, def) }
            box.addView(c); return c
        }
        val server = field("Sunucu adresi (isteğe bağlı) https://…", "server")
        val pin = field("Sunucu PIN'i", "pin", pw = true)
        val emergency = field("Acil durum kişisi (05xx…)", "emergency", phone = true)
        val voice = check("Sesli cevap ver", "voice", true)
        val chat = check("Sohbet modu: “Niko” demeden de dinle", "chat_mode", false)
        val beep = check("Dinleme “bip” sesini kıs", "mute_beep", true)
        box.addView(Button(this).apply {
            text = "🔋 Pil optimizasyonunu kapat (arka planda dinlesin)"
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) { } }
        })
        box.addView(Button(this).apply {
            text = "🪟 Üstte göster izni (arka planda uygulama açabilsin)"
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { } }
        })
        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Ayarlar").setView(sv)
            .setPositiveButton("Kaydet") { _, _ ->
                prefs.edit().putString("server", server.text.toString().trim()).putString("pin", pin.text.toString())
                    .putString("emergency", emergency.text.toString().trim()).putBoolean("voice", voice.isChecked)
                    .putBoolean("chat_mode", chat.isChecked).putBoolean("mute_beep", beep.isChecked).apply()
            }
            .setNegativeButton("Kapat", null).show()
    }
}

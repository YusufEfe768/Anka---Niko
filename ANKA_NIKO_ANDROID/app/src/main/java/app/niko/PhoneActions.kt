package app.niko

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager

/** Telefona erişen eylemler. Arka plandayken Android başka uygulama açmayı engelleyebilir → bildirimle dokunma yolu. */
class PhoneActions(private val ctx: Context) {
    data class Contact(val name: String, val number: String)

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun launch(label: String, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Bus.appVisible || Settings.canDrawOverlays(ctx)) {
            return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
        }
        return notifyTap(label, intent)
    }

    private fun notifyTap(label: String, intent: Intent): Boolean {
        return try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("niko_action", "Niko eylemleri", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, label.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = android.app.Notification.Builder(ctx, "niko_action")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Niko")
                .setContentText("Açmak için dokun: $label")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(label.hashCode(), n)
            true
        } catch (e: Exception) { false }
    }

    // ── Rehber ──
    fun contacts(): List<Contact> {
        if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Contact>()
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(proj[0])
                val pi = c.getColumnIndex(proj[1])
                while (c.moveToNext()) {
                    val n = c.getString(ni)
                    val p = c.getString(pi)
                    if (!n.isNullOrBlank() && !p.isNullOrBlank()) out.add(Contact(n, p))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun hasContactsPermission() = granted(Manifest.permission.READ_CONTACTS)

    fun findContact(spoken: String): Contact? {
        var best: Contact? = null
        var bestScore = 0
        for (c in contacts()) {
            val s = CmdParser.nameScore(spoken, c.name)
            if (s > bestScore) { best = c; bestScore = s }
        }
        return best
    }

    // ── Arama / mesaj ──
    fun call(number: String): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        if (granted(Manifest.permission.CALL_PHONE)) {
            try {
                ctx.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return true
            } catch (e: Exception) { }
        }
        return launch("Arama", Intent(Intent.ACTION_DIAL, uri))
    }

    @Suppress("DEPRECATION")
    fun sendSms(number: String, body: String): Boolean {
        if (!granted(Manifest.permission.SEND_SMS)) return false
        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
            true
        } catch (e: Exception) { false }
    }

    fun openWhatsApp(number: String, body: String): Boolean {
        val n = CmdParser.normPhone(number)
        return launch("WhatsApp", Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=${Uri.encode(body)}")))
    }

    // ── Saat ──
    fun setAlarm(h: Int, m: Int, label: String): Boolean = launch("Alarm",
        Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))

    fun setTimer(sec: Int, label: String): Boolean = launch("Zamanlayıcı",
        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))

    // ── Uygulamalar ──
    @Suppress("DEPRECATION")
    fun openApp(spoken: String): String? {
        val pm = ctx.packageManager
        val w = CmdParser.fold(spoken).filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return null
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: ResolveInfo? = null
        var bestScore = 0
        var bestLabel = ""
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val l = CmdParser.fold(label).filter { it.isLetterOrDigit() }
            val score = when {
                l == w -> 1000
                l.length >= 3 && w.startsWith(l) -> l.length * 10
                w.length >= 3 && l.startsWith(w) -> w.length * 8
                w.length >= 4 && l.contains(w) -> w.length * 3
                else -> 0
            }
            if (score > bestScore) { best = ri; bestScore = score; bestLabel = label }
        }
        val b = best ?: return null
        val li = pm.getLaunchIntentForPackage(b.activityInfo.packageName) ?: return null
        return if (launch(bestLabel, li)) bestLabel else null
    }

    fun openSettings(kind: String): Boolean = launch("Ayarlar", Intent(when (kind) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }))

    fun navigate(query: String, nearby: Boolean): Boolean {
        val q = Uri.encode(query)
        val uri = if (nearby) Uri.parse("geo:0,0?q=$q") else Uri.parse("google.navigation:q=$q")
        if (launch("Harita", Intent(Intent.ACTION_VIEW, uri))) return true
        return launch("Harita", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")))
    }

    fun webSearch(query: String): Boolean {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        return launch("Arama", i)
    }

    fun youtube(query: String): Boolean =
        launch("YouTube", Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")))

    // ── Donanım ──
    fun flashlight(on: Boolean): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (e: Exception) { false }
    }

    fun volume(dir: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when {
                dir > 0 -> repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                dir < 0 -> repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                else -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            }
        } catch (e: Exception) { }
    }

    fun batteryPercent(): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    @SuppressLint("MissingPermission")
    fun lastLocation(): Location? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        return best
    }
}

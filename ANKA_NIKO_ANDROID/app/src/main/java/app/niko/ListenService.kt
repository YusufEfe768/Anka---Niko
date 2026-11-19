package app.niko

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Ön plan servisi: uygulama kapalı/ekran kilitli olsa da mikrofonu dinlemeye devam eder. */
class ListenService : Service() {
    private var assistant: Assistant? = null
    private var loop: VoiceLoop? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (assistant == null) {
            startAsForeground()
            val prefs = getSharedPreferences("niko", Context.MODE_PRIVATE)
            val a = Assistant(applicationContext, prefs)
            val l = VoiceLoop(applicationContext) { list -> a.onTranscripts(list) }
            a.voice = l
            a.onStopRequested = { stopSelf() }
            assistant = a
            loop = l
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "niko:listen").apply { setReferenceCounted(false); acquire() }
            Bus.running = true
            Bus.log("🤖", Brain.greeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) + " Başına 'Niko' diyerek konuş.")
            l.start()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Niko dinliyor", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Niko dinliyor")
            .setContentText("'Niko' diyerek konuş")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Durdur", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        loop?.stop()
        assistant?.shutdown()
        try { wake?.release() } catch (e: Exception) { }
        Bus.running = false
        Bus.mode = 0
        Bus.log("🤖", "Dinlemeyi durdurdum.")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.niko.STOP"
        private const val CHANNEL = "niko_listen"
        private const val NOTIF_ID = 42
    }
}

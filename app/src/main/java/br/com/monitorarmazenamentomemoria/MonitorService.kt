package br.com.monitorarmazenamentomemoria

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val updater = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
            val green = prefs.getInt("greenLimit", 89)
            val yellow = prefs.getInt("yellowLimit", 96)
            Monitor.showNotification(this@MonitorService, green, yellow)
            MonitorWidgetProvider.updateAll(this@MonitorService)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
        val green = prefs.getInt("greenLimit", 89)
        val yellow = prefs.getInt("yellowLimit", 96)
        try {
            startForeground(Monitor.NOTIFICATION_ID, Monitor.buildNotification(this, green, yellow))
            handler.post(updater)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(updater)
        handler.post(updater)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package br.com.monitorarmazenamentomemoria

import android.app.*
import android.content.*
import android.os.*
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PremiumStatusNotificationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        keepForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FROM_APP, ACTION_HIDE -> {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("top_monitor_enabled", false)
                    .apply()

                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH, ACTION_UPDATE, ACTION_RELOCK -> keepForeground()
            else -> keepForeground()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        val enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("top_monitor_enabled", true)

        if (enabled) keepForeground()
    }

    private fun keepForeground() {
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification())
            } catch (_: Throwable) {}
        }
    }

    private fun buildNotification(): Notification {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Remove configuração antiga problemática de Pequeno/Médio/Grande.
        prefs.edit().remove("notification_size").apply()

        val mode = prefs.getString("notification_mode", "detalhado") ?: "detalhado"

        val showStatus = prefs.getBoolean("show_status", true)
        val showFree = prefs.getBoolean("show_free", true)
        val showUsed = prefs.getBoolean("show_used", true)
        val showRam = prefs.getBoolean("show_ram", true)
        val showTime = prefs.getBoolean("show_time", true)

        val storage = storageInfo()
        val ram = ramInfo()
        val time = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())

        val greenLimit = readPanelLimit(listOf("verde", "green"), 89).coerceIn(1, 98)
        val yellowLimit = readPanelLimit(listOf("amarelo", "yellow"), 96).coerceIn(greenLimit + 1, 99)
        val status = storageStatusByPanel(storage.usedPercent, greenLimit, yellowLimit)

        val compact = buildCompactLine(status, storage, ram, time, showStatus, showFree, showUsed, showRam, showTime)

        val big = if (mode == "compacto") {
            buildCompactBody(status, storage, ram, time, showStatus, showFree, showUsed, showRam, showTime)
        } else {
            buildDetailedBody(status, storage, ram, time, showStatus, showFree, showUsed, showRam, showTime)
        }

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = PendingIntent.getActivity(this, 1, openIntent, flags())

        val refresh = Intent(this, PremiumStatusNotificationService::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPending = PendingIntent.getService(this, 2, refresh, flags())

        val relock = Intent(this, PremiumStatusNotificationService::class.java).apply {
            action = ACTION_RELOCK
        }
        val relockPending = PendingIntent.getService(this, 3, relock, flags())

        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Monitor ativo")
            .setContentText(compact)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(openPending)
            .setDeleteIntent(relockPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_dialog_info, "Atualizar", refreshPending)

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            } catch (_: Throwable) {}
        }

        val notification = builder.build()
        notification.flags = notification.flags or
            Notification.FLAG_NO_CLEAR or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_FOREGROUND_SERVICE

        return notification
    }

    private fun buildCompactLine(
        status: StatusInfo,
        storage: StorageData,
        ram: RamData,
        time: String,
        showStatus: Boolean,
        showFree: Boolean,
        showUsed: Boolean,
        showRam: Boolean,
        showTime: Boolean
    ): String {
        val parts = mutableListOf<String>()

        if (showStatus) parts.add("${status.icon} ${status.label}")
        if (showUsed) parts.add("Uso ${storage.usedPercent}%")
        if (showFree) parts.add("Livre ${storage.freeGb} GB")
        if (showRam) parts.add("RAM ${ram.usedGb}/${ram.totalGb} GB")
        if (showTime) parts.add(time)

        return if (parts.isEmpty()) "Monitor ativo" else parts.joinToString(" • ")
    }

    private fun buildCompactBody(
        status: StatusInfo,
        storage: StorageData,
        ram: RamData,
        time: String,
        showStatus: Boolean,
        showFree: Boolean,
        showUsed: Boolean,
        showRam: Boolean,
        showTime: Boolean
    ): String {
        val lines = mutableListOf<String>()

        if (showStatus) lines.add("${status.icon} ${status.label}")

        val data = mutableListOf<String>()
        if (showUsed) data.add("Uso ${storage.usedPercent}%")
        if (showFree) data.add("Livre ${storage.freeGb} GB")
        if (showRam) data.add("RAM ${ram.usedGb}/${ram.totalGb} GB")
        if (data.isNotEmpty()) lines.add(data.joinToString(" • "))

        if (showTime) lines.add("Atualizado às $time")
        lines.add("Fixo • desligue pelo app")

        return lines.joinToString("\n")
    }

    private fun buildDetailedBody(
        status: StatusInfo,
        storage: StorageData,
        ram: RamData,
        time: String,
        showStatus: Boolean,
        showFree: Boolean,
        showUsed: Boolean,
        showRam: Boolean,
        showTime: Boolean
    ): String {
        val lines = mutableListOf<String>()

        if (showStatus) {
            lines.add("${status.icon} ${status.label}")
            lines.add("")
        }

        if (showUsed || showFree) {
            lines.add("Armazenamento")
            if (showUsed) lines.add("Usado: ${storage.usedPercent}%")
            if (showFree) lines.add("Livre: ${storage.freeGb} GB de ${storage.totalGb} GB")
            lines.add("")
        }

        if (showRam) {
            lines.add("Memória RAM")
            lines.add("${ram.usedGb} GB / ${ram.totalGb} GB • ${ram.usedPercent}% em uso")
            lines.add("")
        }

        if (showTime) lines.add("Atualizado às $time")
        lines.add("Fixo • desligue somente pelo app")

        return lines.joinToString("\n")
    }

    private data class StatusInfo(val icon: String, val label: String)

    private fun storageStatusByPanel(usedPercent: Int, greenLimit: Int, yellowLimit: Int): StatusInfo {
        return when {
            usedPercent <= greenLimit -> StatusInfo("🟢", "OK")
            usedPercent <= yellowLimit -> StatusInfo("🟡", "ATENÇÃO")
            else -> StatusInfo("🔴", "CRÍTICO")
        }
    }

    private fun readPanelLimit(keyWords: List<String>, fallback: Int): Int {
        val prefsNames = listOf(
            PREFS,
            "monitor_premium",
            "settings",
            "config",
            "cleanup_settings",
            "alert_settings",
            "limits",
            "prefs"
        )

        for (prefsName in prefsNames) {
            try {
                val all = getSharedPreferences(prefsName, Context.MODE_PRIVATE).all
                val found = findLimitInMap(all, keyWords)
                if (found != null) return found
            } catch (_: Throwable) {}
        }

        try {
            val dir = File(applicationInfo.dataDir, "shared_prefs")
            val files = dir.listFiles()
            if (files != null) {
                for (file in files) {
                    val text = file.readText()
                    val found = findLimitInXml(text, keyWords)
                    if (found != null) return found
                }
            }
        } catch (_: Throwable) {}

        return fallback
    }

    private fun findLimitInMap(all: Map<String, *>, keyWords: List<String>): Int? {
        for ((key, value) in all) {
            val k = key.lowercase(Locale.ROOT)
            val matchesColor = keyWords.any { k.contains(it) }
            val matchesLimit = k.contains("limite") ||
                k.contains("limit") ||
                k.contains("max") ||
                k.contains("ate") ||
                k.contains("até") ||
                k.contains("threshold") ||
                k.contains("alerta")

            if (matchesColor && matchesLimit) {
                val number = when (value) {
                    is Int -> value
                    is Long -> value.toInt()
                    is Float -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }

                if (number != null && number in 1..99) return number
            }
        }

        return null
    }

    private fun findLimitInXml(xml: String, keyWords: List<String>): Int? {
        val regex = Regex("""name="([^"]+)".*?(?:value="([^"]+)"|>([^<]+)<)""")
        for (match in regex.findAll(xml)) {
            val key = match.groupValues.getOrNull(1)?.lowercase(Locale.ROOT) ?: continue
            val valueText = match.groupValues.getOrNull(2)?.ifBlank {
                match.groupValues.getOrNull(3) ?: ""
            } ?: ""

            val matchesColor = keyWords.any { key.contains(it) }
            val matchesLimit = key.contains("limite") ||
                key.contains("limit") ||
                key.contains("max") ||
                key.contains("ate") ||
                key.contains("até") ||
                key.contains("threshold") ||
                key.contains("alerta")

            if (matchesColor && matchesLimit) {
                val number = valueText.trim().toIntOrNull()
                if (number != null && number in 1..99) return number
            }
        }

        return null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor discreto no topo",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.setShowBadge(false)
            channel.enableVibration(false)
            channel.setSound(null, null)

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private data class StorageData(
        val freeGb: String,
        val totalGb: String,
        val freePercent: Int,
        val usedPercent: Int
    )

    private data class RamData(
        val usedGb: String,
        val totalGb: String,
        val usedPercent: Int
    )

    private fun storageInfo(): StorageData {
        val stat = StatFs(Environment.getDataDirectory().path)
        val free = stat.availableBytes
        val total = stat.totalBytes

        val freePercent = if (total > 0L) {
            Math.round((free.toDouble() / total.toDouble()) * 100.0).toInt()
        } else {
            0
        }

        val usedPercent = (100 - freePercent).coerceIn(0, 100)

        return StorageData(
            formatGb(free),
            formatGb(total),
            freePercent,
            usedPercent
        )
    }

    private fun ramInfo(): RamData {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)

        val total = info.totalMem
        val used = total - info.availMem
        val percent = if (total > 0L) {
            Math.round((used.toDouble() / total.toDouble()) * 100.0).toInt()
        } else {
            0
        }

        return RamData(formatGb(used), formatGb(total), percent)
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes.toDouble() / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f", gb)
    }

    companion object {
        const val CHANNEL_ID = "premium_status_monitor_discreto"
        const val NOTIFICATION_ID = 9201
        const val PREFS = "monitor_premium"

        const val ACTION_REFRESH = "br.com.monitorarmazenamentomemoria.REFRESH_MONITOR"
        const val ACTION_RELOCK = "br.com.monitorarmazenamentomemoria.RELOCK_MONITOR"
        const val ACTION_STOP_FROM_APP = "br.com.monitorarmazenamentomemoria.STOP_MONITOR_FROM_APP"

        const val ACTION_UPDATE = ACTION_REFRESH
        const val ACTION_HIDE = ACTION_STOP_FROM_APP

        fun start(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("top_monitor_enabled", true)
                .remove("notification_size")
                .apply()

            val i = Intent(context, PremiumStatusNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("top_monitor_enabled", false)
                .apply()

            val i = Intent(context, PremiumStatusNotificationService::class.java).apply {
                action = ACTION_STOP_FROM_APP
            }
            context.startService(i)
        }
    }
}

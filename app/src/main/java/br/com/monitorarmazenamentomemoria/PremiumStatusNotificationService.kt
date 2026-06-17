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

    private var forceNextCacheRefreshN05iFix4: Boolean = false

    private var forceNextCacheRefreshN05iFix3: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        keepForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val actionN05iFix4 = intent?.action
        if (actionN05iFix4 == ACTION_REFRESH || actionN05iFix4 == ACTION_UPDATE) {
            forceNextCacheRefreshN05iFix4 = true
        }
        val actionN05iFix3 = intent?.action
        if (actionN05iFix3 == ACTION_REFRESH || actionN05iFix3 == ACTION_UPDATE) {
            forceNextCacheRefreshN05iFix3 = true
        }
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
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildNotification(): Notification {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // AVISOS IMPORTANTES:
        // NÃO reativar Pequeno/Médio/Grande.
        // NÃO reativar notification_size.
        // NÃO usar RemoteViews.
        // NÃO usar Bitmap.
        // NÃO usar Spannable.
        // NÃO usar ForegroundColorSpan.
        // NÃO usar StyleSpan.
        // NÃO mexer na trava da notificação.
        prefs.edit().remove("notification_size").apply()

        val mode = prefs.getString("notification_mode", "detalhado") ?: "detalhado"

        val showStatus = prefs.getBoolean("show_status", true)
        val showFree = prefs.getBoolean("show_free", true)
        val showUsed = prefs.getBoolean("show_used", true)
        val showRam = prefs.getBoolean("show_ram", true)
        val showTime = prefs.getBoolean("show_time", true)
        val showCache = prefs.getBoolean("show_cache", true)

        val storage = storageInfo()
        val ram = ramInfo()
        val time = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())

        val greenLimit = readPanelLimit(listOf("verde", "green"), 89).coerceIn(1, 98)
        val yellowLimit = readPanelLimit(listOf("amarelo", "yellow"), 96).coerceIn(greenLimit + 1, 99)

        val status = storageStatusByPanel(storage.usedPercent, greenLimit, yellowLimit)

        val compact = buildCompactLine(
            status = status,
            storage = storage,
            ram = ram,
            time = time,
            showStatus = showStatus,
            showFree = showFree,
            showUsed = showUsed,
            showRam = showRam,
            showTime = showTime
        )

        val big = if (mode == "compacto") {
            buildCompactBody(
                status = status,
                storage = storage,
                ram = ram,
                time = time,
                showStatus = showStatus,
                showFree = showFree,
                showUsed = showUsed,
                showRam = showRam,
                showTime = showTime
            )
        } else {
            buildDetailedBody(
                status = status,
                storage = storage,
                ram = ram,
                time = time,
                showStatus = showStatus,
                showFree = showFree,
                showUsed = showUsed,
                showRam = showRam,
                showTime = showTime
            )
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent(this, CleanupSimpleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val openPending = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            flags()
        )

        val refreshIntent = Intent(this, PremiumStatusNotificationService::class.java).apply {
            action = ACTION_REFRESH
        }

        val refreshPending = PendingIntent.getService(
            this,
            2,
            refreshIntent,
            flags()
        )

        val relockIntent = Intent(this, PremiumStatusNotificationService::class.java).apply {
            action = ACTION_RELOCK
        }

        val relockPending = PendingIntent.getService(
            this,
            3,
            relockIntent,
            flags()
        )

        val openLimpezaIntent = Intent(this, CleanupSimpleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("open_tab", "limpeza")
            putExtra("open_screen", "limpeza")
            putExtra("source", "notification_action")
        }

        val openLimpezaPending = PendingIntent.getActivity(
            this,
            4,
            openLimpezaIntent,
            flags()
        )

        val cacheSummaryN05iFix3 = NotificationCacheSummaryHelper.getSummary(this, forceNextCacheRefreshN05iFix3)
        forceNextCacheRefreshN05iFix3 = false

        val compactFinalN05iFix3 = if (showCache && cacheSummaryN05iFix3.show) {
            val separator = if (compact.isBlank()) "" else " • "
            compact + separator + cacheSummaryN05iFix3.compactLine
        } else {
            compact
        }

        val bigFinalN05iFix3 = big

        val builder: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Monitor de armazenamento")
            .setContentText(compactFinalN05iFix3)
            .setStyle(Notification.BigTextStyle().bigText(bigFinalN05iFix3))
            .setContentIntent(openPending)
            .setDeleteIntent(relockPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_MIN)
            .addAction(android.R.drawable.ic_popup_sync, "Atualizar", refreshPending)
            .addAction(android.R.drawable.ic_menu_view, "Abrir app", openPending)
            .addAction(android.R.drawable.ic_menu_manage, "Limpeza", openLimpezaPending)

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            } catch (_: Throwable) {
            }
        }

        val notification = builder.build()

        notification.flags =
            notification.flags or
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
        val lines = mutableListOf<String>()

        if (showStatus) {
            lines.add("${status.icon} ${status.label}")
        }

        if (showFree) {
            val livreTexto = boldUnicode(storage.freeGb + " GB")
            lines.add("▸ $livreTexto LIVRE")
        }

        val bottomParts = mutableListOf<String>()

        if (showUsed) {
            bottomParts.add("Uso ${storage.usedPercent}%")
        }

        if (showRam) {
            val ramTexto = boldUnicode(ram.freeGb + "/" + ram.totalGb + " GB")
            bottomParts.add("RAM $ramTexto LIVRE")
        }

        if (showTime) {
            bottomParts.add(time)
        }

        if (bottomParts.isNotEmpty()) {
            lines.add(bottomParts.joinToString(" • "))
        }

        return if (lines.isEmpty()) {
            "Monitor ativo"
        } else {
            lines.joinToString("\n")
        }
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

        if (showStatus) {
            lines.add("${status.icon} ${status.label}")
        }

        if (showFree) {
            val livreTexto = boldUnicode(storage.freeGb + " GB")
            lines.add("▸ $livreTexto LIVRE")
        }

        val bottomParts = mutableListOf<String>()

        if (showUsed) {
            bottomParts.add("Uso ${storage.usedPercent}%")
        }

        if (showRam) {
            val ramTexto = boldUnicode(ram.freeGb + "/" + ram.totalGb + " GB")
            bottomParts.add("RAM $ramTexto LIVRE")
        }

        if (showTime) {
            bottomParts.add(time)
        }

        if (bottomParts.isNotEmpty()) {
            lines.add(bottomParts.joinToString(" • "))
        }

        return if (lines.isEmpty()) {
            "Monitor ativo"
        } else {
            lines.joinToString("\n")
        }
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

        lines.add("Monitor ativo")

        if (showStatus) {
            lines.add("")
            lines.add("${status.icon} ${status.label}")
        }

        if (showFree) {
            lines.add("")
            val livreTexto = boldUnicode(storage.freeGb + " GB")
            lines.add("▸ $livreTexto LIVRE")
        }

        if (showUsed) {
            lines.add("Uso: ${storage.usedPercent}%")
        }

        if (showRam) {
            val ramTexto = boldUnicode(ram.freeGb + "/" + ram.totalGb + " GB")
            lines.add("RAM: $ramTexto LIVRE")
        }

        // N05I_FIX9B: texto "Fixo" removido do corpo; a trava real continua nas flags da notificação.

        val showCacheN05iFix5 = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("show_cache", true)
        if (showCacheN05iFix5) {
            val cacheN05iFix4 = NotificationCacheSummaryHelper.getSummary(this, forceNextCacheRefreshN05iFix4)
            forceNextCacheRefreshN05iFix4 = false
            if (cacheN05iFix4.show) {
                lines.add("Cache: " + cacheN05iFix4.compactLine.removePrefix("Cache ").trim() + " • $time")
                // N05I_FIX9B_CACHE_COM_APPS_SEM_FIXO
                // N05I_FIX7_HORA_OBRIGATORIA_CACHE_TODOS_APPS
            }
        }
        // N05I_FIX5_CORRIGE_SHOWCACHE_VISIVEL_ANTES_ATUALIZADO
        if (showTime && !showCacheN05iFix5) {
            lines.add("")
            lines.add("Atualizado às $time")
        }

        return lines.joinToString("\n")
            .replace("\n\n\n", "\n\n")
            .trim()
    }

    private fun boldUnicode(value: String): String {
        val out = StringBuilder()

        for (ch in value) {
            when (ch) {
                in 'A'..'Z' -> out.append(String(Character.toChars(0x1D5D4 + (ch.code - 'A'.code))))
                in 'a'..'z' -> out.append(String(Character.toChars(0x1D5EE + (ch.code - 'a'.code))))
                in '0'..'9' -> out.append(String(Character.toChars(0x1D7EC + (ch.code - '0'.code))))
                else -> out.append(ch)
            }
        }

        return out.toString()
    }

    private data class StatusInfo(
        val icon: String,
        val label: String
    )

    private fun storageStatusByPanel(
        usedPercent: Int,
        greenLimit: Int,
        yellowLimit: Int
    ): StatusInfo {
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

                if (found != null) {
                    return found
                }
            } catch (_: Throwable) {
            }
        }

        try {
            val dir = File(applicationInfo.dataDir, "shared_prefs")
            val files = dir.listFiles()

            if (files != null) {
                for (file in files) {
                    val text = file.readText()
                    val found = findLimitInXml(text, keyWords)

                    if (found != null) {
                        return found
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return fallback
    }

    private fun findLimitInMap(
        all: Map<String, *>,
        keyWords: List<String>
    ): Int? {
        for ((key, value) in all) {
            val k = key.lowercase(Locale.ROOT)

            val matchesColor = keyWords.any { k.contains(it) }

            val matchesLimit =
                k.contains("limite") ||
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

                if (number != null && number in 1..99) {
                    return number
                }
            }
        }

        return null
    }

    private fun findLimitInXml(
        xml: String,
        keyWords: List<String>
    ): Int? {
        val regex = Regex("""name="([^"]+)".*?(?:value="([^"]+)"|>([^<]+)<)""")

        for (match in regex.findAll(xml)) {
            val key = match.groupValues.getOrNull(1)?.lowercase(Locale.ROOT) ?: continue

            val valueText =
                match.groupValues.getOrNull(2)?.ifBlank {
                    match.groupValues.getOrNull(3) ?: ""
                } ?: ""

            val matchesColor = keyWords.any { key.contains(it) }

            val matchesLimit =
                key.contains("limite") ||
                    key.contains("limit") ||
                    key.contains("max") ||
                    key.contains("ate") ||
                    key.contains("até") ||
                    key.contains("threshold") ||
                    key.contains("alerta")

            if (matchesColor && matchesLimit) {
                val number = valueText.trim().toIntOrNull()

                if (number != null && number in 1..99) {
                    return number
                }
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

    private fun flags(): Int {
        return if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
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
        val freeGb: String,
        val totalGb: String,
        val freePercent: Int,
        val usedPercent: Int
    )

    private fun storageInfo(): StorageData {
        val stat = StatFs(Environment.getDataDirectory().path)

        val free = stat.availableBytes
        val total = stat.totalBytes

        val freePercent =
            if (total > 0L) {
                Math.round((free.toDouble() / total.toDouble()) * 100.0).toInt()
            } else {
                0
            }

        val usedPercent = (100 - freePercent).coerceIn(0, 100)

        return StorageData(
            freeGb = formatGb(free),
            totalGb = formatGb(total),
            freePercent = freePercent,
            usedPercent = usedPercent
        )
    }

    private fun ramInfo(): RamData {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()

        manager.getMemoryInfo(info)

        val total = info.totalMem
        val free = info.availMem

        val freePercent =
            if (total > 0L) {
                Math.round((free.toDouble() / total.toDouble()) * 100.0).toInt()
            } else {
                0
            }

        val usedPercent = (100 - freePercent).coerceIn(0, 100)

        return RamData(
            freeGb = formatGb(free),
            totalGb = formatGb(total),
            freePercent = freePercent,
            usedPercent = usedPercent
        )
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

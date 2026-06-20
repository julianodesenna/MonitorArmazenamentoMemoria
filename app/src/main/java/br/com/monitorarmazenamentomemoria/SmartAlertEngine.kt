package br.com.monitorarmazenamentomemoria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale
import kotlin.math.roundToInt

/*
 * SINAL_04_alarme_real_storage_cache
 *
 * Detectores ativos nesta fase:
 * - armazenamento baixo
 * - cache alto
 *
 * Detectores futuros:
 * - arquivo grande novo
 * - crescimento rapido de uso
 *
 * Protecoes:
 * - usa repeticao individual;
 * - nao repete sem controle;
 * - cancela o estado ao normalizar;
 * - usa cache ja calculado, sem forcar nova varredura pesada.
 */
object SmartAlertEngine {

    private const val PREFS = "smart_alert_engine"
    private const val CHANNEL_SOUND_VIBRATE = "smart_alert_sound_vibrate"
    private const val CHANNEL_SOUND = "smart_alert_sound"
    private const val CHANNEL_VIBRATE = "smart_alert_vibrate"
    private const val CHANNEL_SILENT = "smart_alert_silent"

    private const val ID_STORAGE_LOW = 8041
    private const val ID_CACHE_HIGH = 8042

    fun checkAndNotify(context: Context) {
        try {
            createChannels(context)

            checkStorageLow(context)
            checkCacheHigh(context)
        } catch (_: Throwable) {
        }
    }

    private fun checkStorageLow(context: Context) {
        val setting = SmartAlertsManager.read(
            context,
            SmartAlertsManager.DETECTOR_STORAGE_LOW
        )

        val freeGb = storageFreeGb()

        val active = setting.enabled && freeGb < setting.limitGb

        processDetector(
            context = context,
            detector = SmartAlertsManager.DETECTOR_STORAGE_LOW,
            notificationId = ID_STORAGE_LOW,
            isActive = active,
            setting = setting,
            title = "Armazenamento baixo",
            text = "Livre: ${formatGb(freeGb)} GB • Limite: ${formatGb(setting.limitGb)} GB"
        )
    }

    private fun checkCacheHigh(context: Context) {
        val setting = SmartAlertsManager.read(
            context,
            SmartAlertsManager.DETECTOR_CACHE_HIGH
        )

        val cacheGb = readCachedCacheGb(context)

        val active = cacheGb != null &&
            setting.enabled &&
            cacheGb > setting.limitGb

        processDetector(
            context = context,
            detector = SmartAlertsManager.DETECTOR_CACHE_HIGH,
            notificationId = ID_CACHE_HIGH,
            isActive = active,
            setting = setting,
            title = "Cache alto",
            text = if (cacheGb != null) {
                "Cache: ${formatGb(cacheGb)} GB • Limite: ${formatGb(setting.limitGb)} GB"
            } else {
                "Cache ainda não disponível para leitura"
            }
        )
    }

    private fun processDetector(
        context: Context,
        detector: String,
        notificationId: Int,
        isActive: Boolean,
        setting: SmartAlertsManager.DetectorSettings,
        title: String,
        text: String
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val activeKey = "${detector}_active"
        val lastKey = "${detector}_last_alert"

        if (!isActive) {
            prefs.edit()
                .putBoolean(activeKey, false)
                .apply()

            notificationManager(context).cancel(notificationId)
            return
        }

        val now = System.currentTimeMillis()
        val wasActive = prefs.getBoolean(activeKey, false)
        val lastAlert = prefs.getLong(lastKey, 0L)
        val repeatMs = setting.repeatMinutes.toLong() * 60L * 1000L

        val canAlert = when {
            !wasActive -> true
            setting.repeatMinutes <= 0 -> false
            now - lastAlert >= repeatMs -> true
            else -> false
        }

        prefs.edit()
            .putBoolean(activeKey, true)
            .apply()

        if (!canAlert) return

        showAlertNotification(
            context = context,
            notificationId = notificationId,
            title = title,
            text = text,
            setting = setting
        )

        prefs.edit()
            .putLong(lastKey, now)
            .apply()
    }

    private fun showAlertNotification(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        setting: SmartAlertsManager.DetectorSettings
    ) {
        val openIntent = Intent(context, CleanupSimpleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("open_tab", "limpeza")
            putExtra("source", "smart_alert")
        }

        val openPending = PendingIntent.getActivity(
            context,
            notificationId + 100,
            openIntent,
            pendingFlags()
        )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelIdFor(setting))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ $title")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            when {
                setting.sound && setting.vibration -> {
                    builder.setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                }
                setting.sound -> builder.setDefaults(Notification.DEFAULT_SOUND)
                setting.vibration -> builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        }

        notificationManager(context).notify(notificationId, builder.build())
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return

        val manager = notificationManager(context)
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        fun channel(
            id: String,
            name: String,
            sound: Boolean,
            vibration: Boolean
        ) {
            val c = NotificationChannel(
                id,
                name,
                NotificationManager.IMPORTANCE_HIGH
            )

            c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            c.enableVibration(vibration)

            if (vibration) {
                c.vibrationPattern = longArrayOf(0L, 220L, 160L, 220L)
            }

            if (sound) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()

                c.setSound(soundUri, attrs)
            } else {
                c.setSound(null, null)
            }

            manager.createNotificationChannel(c)
        }

        channel(CHANNEL_SOUND_VIBRATE, "Alertas: som e vibração", true, true)
        channel(CHANNEL_SOUND, "Alertas: som", true, false)
        channel(CHANNEL_VIBRATE, "Alertas: vibração", false, true)
        channel(CHANNEL_SILENT, "Alertas: silencioso", false, false)
    }

    private fun channelIdFor(setting: SmartAlertsManager.DetectorSettings): String {
        return when {
            setting.sound && setting.vibration -> CHANNEL_SOUND_VIBRATE
            setting.sound -> CHANNEL_SOUND
            setting.vibration -> CHANNEL_VIBRATE
            else -> CHANNEL_SILENT
        }
    }

    private fun storageFreeGb(): Double {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val bytes = stat.availableBytes.coerceAtLeast(0L)
            bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (_: Throwable) {
            0.0
        }
    }

    private fun readCachedCacheGb(context: Context): Double? {
        return try {
            val summary = NotificationCacheSummaryHelper.getSummary(context, false)

            if (!summary.show) return null

            val match = Regex(
                """Cache\s+([0-9]+(?:[.,][0-9]+)?)\s*(GB|MB|KB|B)""",
                RegexOption.IGNORE_CASE
            ).find(summary.compactLine) ?: return null

            val number = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
            val unit = match.groupValues[2].uppercase(Locale.ROOT)

            when (unit) {
                "GB" -> number
                "MB" -> number / 1024.0
                "KB" -> number / (1024.0 * 1024.0)
                else -> number / (1024.0 * 1024.0 * 1024.0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun notificationManager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun formatGb(value: Double): String {
        val rounded = (value * 10.0).roundToInt() / 10.0

        return if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }
}

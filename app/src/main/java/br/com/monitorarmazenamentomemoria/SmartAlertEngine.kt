package br.com.monitorarmazenamentomemoria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.Locale
import kotlin.math.roundToInt

/*
 * PULSO_05_alarme_forte
 *
 * Alertas reais:
 * - armazenamento baixo
 * - cache alto
 *
 * Melhorias:
 * - canal novo V2 para evitar configuracao silenciosa antiga;
 * - teste direto de som e vibracao;
 * - repeticao corrigida;
 * - ao normalizar, libera novo disparo.
 */
object SmartAlertEngine {

    private const val PREFS = "smart_alert_engine_pulso_05"

    private const val CHANNEL_SOUND_VIBRATE = "smart_alarm_pulso_v2_sound_vibrate"
    private const val CHANNEL_SOUND = "smart_alarm_pulso_v2_sound"
    private const val CHANNEL_VIBRATE = "smart_alarm_pulso_v2_vibrate"
    private const val CHANNEL_SILENT = "smart_alarm_pulso_v2_silent"

    private const val ID_STORAGE_LOW = 9041
    private const val ID_CACHE_HIGH = 9042
    private const val ID_FAST_GROWTH = 9043
    private const val ID_LARGE_FILE = 9044
    private const val ID_TEST = 9099

    private const val FAST_GROWTH_WINDOW_MS = 24L * 60L * 60L * 1000L

    private var testRingtone: Ringtone? = null

    fun checkAndNotify(context: Context) {
        try {
            markCheck(context, manual = false)
            createChannels(context)
            checkStorageLow(context, forceNotify = false)
            checkCacheHigh(context, forceNotify = false)
            checkFastGrowth(context, forceNotify = false)
            checkLargeFile(context, forceNotify = false)
        } catch (_: Throwable) {
        }
    }

    /*
     * Chamado pelo botao Atualizar da notificacao principal.
     *
     * Reavalia os detectores imediatamente e ignora apenas
     * a trava de repeticao daquela verificacao manual.
     */
    fun checkAndNotifyNow(context: Context) {
        try {
            markCheck(context, manual = true)
            createChannels(context)
            checkStorageLow(context, forceNotify = true)
            checkCacheHigh(context, forceNotify = true)
            checkFastGrowth(context, forceNotify = true)
            checkLargeFile(context, forceNotify = true)
        } catch (_: Throwable) {
        }
    }

    fun testAlarm(context: Context) {
        try {
            createChannels(context)
            playDirectSoundAndVibration(context, true, true)

            showNotification(
                context = context,
                notificationId = ID_TEST,
                title = "⚠ TESTE DE ALARME",
                text = "Som, vibração e alerta visual em teste.",
                sound = true,
                vibration = true
            )
        } catch (_: Throwable) {
        }
    }


    fun testOverlayAlarm(context: Context) {
        try {
            createChannels(context)
            playDirectSoundAndVibration(context, true, true)

            SmartAlertOverlay.showIfAllowed(
                context = context,
                title = "⚠ TESTE DE ALERTA",
                message = "Alerta visual funcionando sobre outros aplicativos."
            )

            showNotification(
                context = context,
                notificationId = ID_TEST + 1,
                title = "⚠ TESTE DE ALERTA",
                text = "Som, vibração e alerta flutuante em teste.",
                sound = false,
                vibration = false
            )
        } catch (_: Throwable) {
        }
    }

    fun stopTestAlarm() {
        try {
            testRingtone?.stop()
        } catch (_: Throwable) {
        } finally {
            testRingtone = null
        }
    }

    private fun checkStorageLow(
        context: Context,
        forceNotify: Boolean
    ) {
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
            text = "Livre: ${formatGb(freeGb)} GB • Limite: ${formatGb(setting.limitGb)} GB",
            forceNotify = forceNotify
        )
    }

    private fun checkCacheHigh(
        context: Context,
        forceNotify: Boolean
    ) {
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
                "Cache indisponível para leitura"
            },
            forceNotify = forceNotify
        )
    }

    /*
     * RITMO_08_crescimento_rapido
     *
     * Mantém uma referência de espaço livre por até 24 horas.
     *
     * Regra:
     * - primeira leitura: apenas grava referência;
     * - se ganhar espaço: atualiza referência para o maior valor livre;
     * - se perder espaço acima do limite dentro de 24h: dispara alerta;
     * - depois de 24h: reinicia a janela de comparação.
     */
    private fun checkFastGrowth(
        context: Context,
        forceNotify: Boolean
    ) {
        val setting = SmartAlertsManager.read(
            context,
            SmartAlertsManager.DETECTOR_FAST_GROWTH
        )

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val currentFreeGb = storageFreeGb()

        val baselineTimeKey = "fast_growth_baseline_time"
        val baselineFreeKey = "fast_growth_baseline_free_bits"

        val previousTime = prefs.getLong(baselineTimeKey, 0L)
        val previousFreeGb = Double.fromBits(
            prefs.getLong(baselineFreeKey, 0L)
        )

        // Primeira medição ou janela vencida.
        if (previousTime <= 0L || now - previousTime > FAST_GROWTH_WINDOW_MS) {
            prefs.edit()
                .putLong(baselineTimeKey, now)
                .putLong(baselineFreeKey, currentFreeGb.toBits())
                .apply()

            processDetector(
                context = context,
                detector = SmartAlertsManager.DETECTOR_FAST_GROWTH,
                notificationId = ID_FAST_GROWTH,
                isActive = false,
                setting = setting,
                title = "Crescimento rápido de uso",
                text = "Aguardando histórico de armazenamento",
                forceNotify = false
            )
            return
        }

        // Se o aparelho ganhou espaço, usa o maior valor como nova referência.
        val baselineFreeGb =
            if (currentFreeGb > previousFreeGb) {
                prefs.edit()
                    .putLong(baselineFreeKey, currentFreeGb.toBits())
                    .apply()
                currentFreeGb
            } else {
                previousFreeGb
            }

        val lostGb = (baselineFreeGb - currentFreeGb).coerceAtLeast(0.0)

        val active =
            setting.enabled &&
            lostGb >= setting.limitGb

        val elapsedMinutes = ((now - previousTime) / 60000L).coerceAtLeast(1L)

        processDetector(
            context = context,
            detector = SmartAlertsManager.DETECTOR_FAST_GROWTH,
            notificationId = ID_FAST_GROWTH,
            isActive = active,
            setting = setting,
            title = "Crescimento rápido de uso",
            text = "Perda: ${formatGb(lostGb)} GB em ${formatElapsed(elapsedMinutes)} • Limite: ${formatGb(setting.limitGb)} GB",
            forceNotify = forceNotify
        )
    }


    /*
     * RITMO_09
     *
     * Guarda a hora real da última tentativa de verificação.
     * A rotina automática tenta rodar a cada 15 minutos, mas o Android
     * pode atrasar essa tentativa em economia de bateria ou repouso.
     */
    private fun markCheck(context: Context, manual: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_check_time", System.currentTimeMillis())
                .putBoolean("last_check_manual", manual)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun monitorStatus(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong("last_check_time", 0L)
            val manual = prefs.getBoolean("last_check_manual", false)

            if (last <= 0L) {
                "Ainda não houve verificação registrada."
            } else {
                val hora = java.text.SimpleDateFormat(
                    "HH:mm",
                    Locale("pt", "BR")
                ).format(java.util.Date(last))

                val tipo = if (manual) "manual" else "automática"

                "Última verificação: $hora ($tipo). Tentativa automática: a cada 15 min."
            }
        } catch (_: Throwable) {
            "Tentativa automática: a cada 15 min."
        }
    }

    /*
     * ARQUIVO_10
     *
     * Faz varredura controlada apenas em áreas públicas acessíveis.
     *
     * Primeira leitura:
     * - registra os arquivos atuais como conhecidos;
     * - não alerta arquivos antigos.
     *
     * Próximas leituras:
     * - detecta arquivos novos acima do limite configurado;
     * - alerta apenas o maior arquivo novo encontrado.
     */
    private fun checkLargeFile(
        context: Context,
        forceNotify: Boolean
    ) {
        val setting = SmartAlertsManager.read(
            context,
            SmartAlertsManager.DETECTOR_LARGE_FILE
        )

        if (!setting.enabled) {
            processDetector(
                context = context,
                detector = SmartAlertsManager.DETECTOR_LARGE_FILE,
                notificationId = ID_LARGE_FILE,
                isActive = false,
                setting = setting,
                title = "Arquivo grande novo",
                text = "Detector desligado",
                forceNotify = false
            )
            return
        }

        val result = LargeFileAlertScanner.scan(
            context = context,
            minimumGb = setting.limitGb,
            forceScan = forceNotify
        )

        val found = result.newLargeFile

        processDetector(
            context = context,
            detector = SmartAlertsManager.DETECTOR_LARGE_FILE,
            notificationId = ID_LARGE_FILE,
            isActive = found != null,
            setting = setting,
            title = "Arquivo grande novo",
            text = if (found != null) {
                "${found.name} • ${formatGb(found.sizeGb)} GB • ${found.location}"
            } else {
                result.message
            },
            forceNotify = forceNotify && found != null
        )
    }

    private fun processDetector(
        context: Context,
        detector: String,
        notificationId: Int,
        isActive: Boolean,
        setting: SmartAlertsManager.DetectorSettings,
        title: String,
        text: String,
        forceNotify: Boolean
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val activeKey = "${detector}_active"
        val lastKey = "${detector}_last"

        if (!isActive) {
            prefs.edit()
                .putBoolean(activeKey, false)
                .remove(lastKey)
                .apply()

            notificationManager(context).cancel(notificationId)
            return
        }

        val now = System.currentTimeMillis()
        val wasActive = prefs.getBoolean(activeKey, false)
        val last = prefs.getLong(lastKey, 0L)
        val repeatMs = setting.repeatMinutes.toLong() * 60L * 1000L

        val mustNotify = when {
            forceNotify -> true
            !wasActive -> true
            setting.repeatMinutes <= 0 -> false
            last <= 0L -> true
            now - last >= repeatMs -> true
            else -> false
        }

        prefs.edit()
            .putBoolean(activeKey, true)
            .apply()

        if (!mustNotify) return

        playDirectSoundAndVibration(
            context = context,
            soundEnabled = setting.sound,
            vibrationEnabled = setting.vibration
        )

        SmartAlertOverlay.showIfAllowed(
            context = context,
            title = "⚠ $title",
            message = text
        )

        showNotification(
            context = context,
            notificationId = notificationId,
            title = "⚠ $title",
            text = text,
            sound = false,
            vibration = false
        )

        prefs.edit()
            .putLong(lastKey, now)
            .apply()
    }

    private fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        sound: Boolean,
        vibration: Boolean
    ) {
        val openIntent = Intent(context, CleanupSimpleActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("open_tab", "limpeza")
            putExtra("source", "smart_alarm")
        }

        val openPending = PendingIntent.getActivity(
            context,
            notificationId + 100,
            openIntent,
            pendingFlags()
        )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelIdFor(sound, vibration))
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            when {
                sound && vibration ->
                    builder.setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
                sound ->
                    builder.setDefaults(Notification.DEFAULT_SOUND)
                vibration ->
                    builder.setDefaults(Notification.DEFAULT_VIBRATE)
            }
        }

        notificationManager(context).notify(notificationId, builder.build())
    }

    private fun playDirectSoundAndVibration(
        context: Context,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        if (soundEnabled) {
            try {
                stopTestAlarm()

                val uri = RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_ALARM
                ) ?: RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION
                )

                if (uri != null) {
                    testRingtone = RingtoneManager.getRingtone(context, uri)
                    testRingtone?.play()

                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({
                            try {
                                testRingtone?.stop()
                            } catch (_: Throwable) {
                            }
                        }, 7000L)
                }
            } catch (_: Throwable) {
            }
        }

        if (vibrationEnabled) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

                if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0L, 250L, 120L, 250L, 120L, 450L),
                            -1
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        longArrayOf(0L, 250L, 120L, 250L, 120L, 450L),
                        -1
                    )
                }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return

        val manager = notificationManager(context)
        val alarmUri = RingtoneManager.getDefaultUri(
            RingtoneManager.TYPE_ALARM
        ) ?: RingtoneManager.getDefaultUri(
            RingtoneManager.TYPE_NOTIFICATION
        )

        fun makeChannel(
            id: String,
            name: String,
            soundEnabled: Boolean,
            vibrationEnabled: Boolean
        ) {
            val channel = NotificationChannel(
                id,
                name,
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            channel.enableVibration(vibrationEnabled)

            if (vibrationEnabled) {
                channel.vibrationPattern =
                    longArrayOf(0L, 250L, 120L, 250L, 120L, 450L)
            }

            if (soundEnabled && alarmUri != null) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                channel.setSound(alarmUri, attributes)
            } else {
                channel.setSound(null, null)
            }

            manager.createNotificationChannel(channel)
        }

        makeChannel(
            CHANNEL_SOUND_VIBRATE,
            "Monitor: alarme com som e vibração",
            true,
            true
        )

        makeChannel(
            CHANNEL_SOUND,
            "Monitor: alarme com som",
            true,
            false
        )

        makeChannel(
            CHANNEL_VIBRATE,
            "Monitor: alarme com vibração",
            false,
            true
        )

        makeChannel(
            CHANNEL_SILENT,
            "Monitor: alarme silencioso",
            false,
            false
        )
    }

    private fun channelIdFor(sound: Boolean, vibration: Boolean): String {
        return when {
            sound && vibration -> CHANNEL_SOUND_VIBRATE
            sound -> CHANNEL_SOUND
            vibration -> CHANNEL_VIBRATE
            else -> CHANNEL_SILENT
        }
    }

    private fun storageFreeGb(): Double {
        return try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            stat.availableBytes.coerceAtLeast(0L).toDouble() /
                (1024.0 * 1024.0 * 1024.0)
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

            val number = match.groupValues[1]
                .replace(",", ".")
                .toDoubleOrNull() ?: return null

            when (match.groupValues[2].uppercase(Locale.ROOT)) {
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
        return context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
    }

    private fun pendingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun formatElapsed(minutes: Long): String {
        return when {
            minutes < 60L -> "${minutes} min"
            minutes < 120L -> "1 hora"
            minutes < 1440L -> "${minutes / 60L} horas"
            else -> "24 horas"
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

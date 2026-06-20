package br.com.monitorarmazenamentomemoria

import android.content.Context
import java.util.Locale

/*
 * ALERTA_02_configuracoes_individuais
 *
 * Salva preferencias individuais para cada detector.
 * Nesta fase ainda nao toca som, nao vibra e nao cria popup.
 */
object SmartAlertsManager {

    const val DETECTOR_STORAGE_LOW = "storage_low"
    const val DETECTOR_CACHE_HIGH = "cache_high"
    const val DETECTOR_LARGE_FILE = "large_file"
    const val DETECTOR_FAST_GROWTH = "fast_growth"

    private const val PREFS = "smart_alerts"

    data class DetectorSettings(
        val enabled: Boolean,
        val limitGb: Double,
        val sound: Boolean,
        val vibration: Boolean,
        val popup: Boolean,
        val repeatMinutes: Int
    )

    fun isMasterEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("master_enabled", true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean("master_enabled", enabled)
            .apply()
    }

    fun read(context: Context, detector: String): DetectorSettings {
        val p = prefs(context)

        return DetectorSettings(
            enabled = p.getBoolean(key(detector, "enabled"), false),
            limitGb = Double.fromBits(
                p.getLong(
                    key(detector, "limit_bits"),
                    defaultLimit(detector).toBits()
                )
            ),
            sound = p.getBoolean(key(detector, "sound"), true),
            vibration = p.getBoolean(key(detector, "vibration"), true),
            popup = p.getBoolean(key(detector, "popup"), false),
            repeatMinutes = p.getInt(key(detector, "repeat_minutes"), 60)
        )
    }

    fun save(
        context: Context,
        detector: String,
        enabled: Boolean,
        limitGb: Double,
        sound: Boolean,
        vibration: Boolean,
        popup: Boolean,
        repeatMinutes: Int
    ) {
        prefs(context).edit()
            .putBoolean(key(detector, "enabled"), enabled)
            .putLong(key(detector, "limit_bits"), limitGb.toBits())
            .putBoolean(key(detector, "sound"), sound)
            .putBoolean(key(detector, "vibration"), vibration)
            .putBoolean(key(detector, "popup"), popup)
            .putInt(key(detector, "repeat_minutes"), repeatMinutes)
            .apply()
    }

    fun summary(context: Context, detector: String): String {
        val setting = read(context, detector)
        val state = if (setting.enabled) "ATIVADO" else "DESLIGADO"

        val description = when (detector) {
            DETECTOR_STORAGE_LOW ->
                "abaixo de ${formatLimit(setting.limitGb)} GB livres"

            DETECTOR_CACHE_HIGH ->
                "acima de ${formatLimit(setting.limitGb)} GB de cache"

            DETECTOR_LARGE_FILE ->
                "arquivo novo acima de ${formatLimit(setting.limitGb)} GB"

            DETECTOR_FAST_GROWTH ->
                "perda acima de ${formatLimit(setting.limitGb)} GB em 24h"

            else -> ""
        }

        return "${title(detector)}\n$state • $description"
    }

    fun title(detector: String): String {
        return when (detector) {
            DETECTOR_STORAGE_LOW -> "Armazenamento baixo"
            DETECTOR_CACHE_HIGH -> "Cache alto"
            DETECTOR_LARGE_FILE -> "Arquivo grande novo"
            DETECTOR_FAST_GROWTH -> "Crescimento rápido de uso"
            else -> "Alerta"
        }
    }

    fun limitLabel(detector: String): String {
        return when (detector) {
            DETECTOR_STORAGE_LOW ->
                "Avisar quando ficar abaixo de quantos GB livres?"

            DETECTOR_CACHE_HIGH ->
                "Avisar quando o cache detectado passar de quantos GB?"

            DETECTOR_LARGE_FILE ->
                "Avisar quando entrar arquivo novo acima de quantos GB?"

            DETECTOR_FAST_GROWTH ->
                "Avisar se perder mais de quantos GB em 24 horas?"

            else -> "Limite em GB"
        }
    }

    fun defaultLimitHint(detector: String): String {
        return formatLimit(defaultLimit(detector))
    }

    fun normalizeLimit(detector: String, value: Double): Double {
        return value.coerceIn(0.1, 999.0)
    }

    fun formatLimit(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    fun repeatIndex(minutes: Int): Int {
        return when (minutes) {
            0 -> 0
            30 -> 1
            60 -> 2
            180 -> 3
            360 -> 4
            else -> 2
        }
    }

    fun repeatMinutesFromIndex(index: Int): Int {
        return when (index) {
            0 -> 0
            1 -> 30
            2 -> 60
            3 -> 180
            4 -> 360
            else -> 60
        }
    }

    private fun defaultLimit(detector: String): Double {
        return when (detector) {
            DETECTOR_STORAGE_LOW -> 10.0
            DETECTOR_CACHE_HIGH -> 2.0
            DETECTOR_LARGE_FILE -> 1.0
            DETECTOR_FAST_GROWTH -> 2.0
            else -> 1.0
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(detector: String, suffix: String): String {
        return "${detector}_${suffix}"
    }
}

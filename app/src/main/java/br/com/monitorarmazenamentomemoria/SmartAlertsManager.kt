package br.com.monitorarmazenamentomemoria

import android.content.Context
import java.util.Locale

/*
 * CARDS_03_alertas_restaurados_sem_recarregar_tela
 *
 * Apenas preferencias individuais.
 * Nenhum alarme real e nenhum scanner é acionado nesta fase.
 */
object SmartAlertsManager {

    const val DETECTOR_STORAGE_LOW = "storage_low"
    const val DETECTOR_CACHE_HIGH = "cache_high"
    const val DETECTOR_LARGE_FILE = "large_file"
    const val DETECTOR_FAST_GROWTH = "fast_growth"
    const val DETECTOR_WHATSAPP_FILE = "whatsapp_file"

    private const val PREFS = "smart_alerts_cards_03"

    data class DetectorSettings(
        val enabled: Boolean,
        val limitGb: Double,
        val sound: Boolean,
        val vibration: Boolean,
        val popup: Boolean,
        val repeatMinutes: Int
    )

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

    fun anyEnabled(context: Context): Boolean {
        return allDetectors().any { read(context, it).enabled }
    }

    fun setAllEnabled(context: Context, enabled: Boolean) {
        allDetectors().forEach { detector ->
            val current = read(context, detector)
            save(
                context = context,
                detector = detector,
                enabled = enabled,
                limitGb = current.limitGb,
                sound = current.sound,
                vibration = current.vibration,
                popup = current.popup,
                repeatMinutes = current.repeatMinutes
            )
        }
    }

    fun summary(context: Context, detector: String): String {
        val setting = read(context, detector)
        val state = if (setting.enabled) "ATIVADO" else "DESLIGADO"

        val details = when (detector) {
            DETECTOR_STORAGE_LOW ->
                "abaixo de ${formatLimit(setting.limitGb)} GB livres"

            DETECTOR_CACHE_HIGH ->
                "acima de ${formatLimit(setting.limitGb)} GB de cache"

            DETECTOR_LARGE_FILE ->
                "arquivo novo acima de ${formatLimit(setting.limitGb)} GB"

            DETECTOR_FAST_GROWTH ->
                "perda acima de ${formatLimit(setting.limitGb)} GB em 24h"

            DETECTOR_WHATSAPP_FILE ->
                "arquivo recebido no WhatsApp acima de ${formatLimit(setting.limitGb)} GB"

            else -> ""
        }

        return "${title(detector)}\n$state • $details"
    }

    fun title(detector: String): String {
        return when (detector) {
            DETECTOR_STORAGE_LOW -> "Armazenamento baixo"
            DETECTOR_CACHE_HIGH -> "Cache alto"
            DETECTOR_LARGE_FILE -> "Arquivo grande novo"
            DETECTOR_FAST_GROWTH -> "Crescimento rápido de uso"
            DETECTOR_WHATSAPP_FILE -> "Arquivo recebido no WhatsApp"
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

            DETECTOR_WHATSAPP_FILE ->
                "Avisar quando chegar arquivo no WhatsApp acima de quantos GB?"

            else -> "Limite em GB"
        }
    }

    fun defaultLimitHint(detector: String): String =
        formatLimit(defaultLimit(detector))

    fun normalizeLimit(detector: String, value: Double): Double =
        value.coerceIn(0.000001, 999.0)

    fun formatLimit(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
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

    private fun allDetectors(): List<String> {
        return listOf(
            DETECTOR_STORAGE_LOW,
            DETECTOR_CACHE_HIGH,
            DETECTOR_LARGE_FILE,
            DETECTOR_FAST_GROWTH,
            DETECTOR_WHATSAPP_FILE
        )
    }

    private fun defaultLimit(detector: String): Double {
        return when (detector) {
            DETECTOR_STORAGE_LOW -> 10.0
            DETECTOR_CACHE_HIGH -> 2.0
            DETECTOR_LARGE_FILE -> 1.0
            DETECTOR_FAST_GROWTH -> 2.0
            DETECTOR_WHATSAPP_FILE -> 0.05
            else -> 1.0
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(detector: String, suffix: String): String =
        "${detector}_${suffix}"
}

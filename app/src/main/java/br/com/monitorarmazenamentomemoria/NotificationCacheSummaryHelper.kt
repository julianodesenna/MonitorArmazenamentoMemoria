package br.com.monitorarmazenamentomemoria

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * N05I_FIX1_cache_detectado_notificacao_atualizar
 *
 * Objetivo:
 * - Mostrar cache detectado na notificacao fixa.
 * - Usar o botao Atualizar ja existente para tentar recalcular o cache.
 * - Salvar o ultimo valor para a notificacao nao ficar vazia.
 * - Nao criar botao Cache.
 * - Nao limpar cache automaticamente.
 * - Nao apagar dados.
 * - Nao usar root, ADB ou Shizuku.
 * - Manter calculo leve para nao travar o app.
 */
object NotificationCacheSummaryHelper {

    private const val PREFS = "monitor_premium"
    private const val KEY_CACHE_BYTES = "notification_cache_detected_bytes"
    private const val KEY_CACHE_UPDATED_AT = "notification_cache_updated_at"
    private const val KEY_CACHE_APPS_COUNT = "notification_cache_apps_count"

    private const val REFRESH_COOLDOWN_MS = 3L * 60L * 1000L
    private const val SOFT_TTL_MS = 15L * 60L * 1000L
    private const val MAX_APPS_TO_QUERY = 60

    data class CacheNotificationSummary(
        val show: Boolean,
        val compactLine: String,
        val detailedLine: String
    )

    fun getSummary(context: Context, forceRefresh: Boolean): CacheNotificationSummary {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val lastBytes = prefs.getLong(KEY_CACHE_BYTES, -1L)
        val lastAt = prefs.getLong(KEY_CACHE_UPDATED_AT, 0L)
        val lastCount = prefs.getInt(KEY_CACHE_APPS_COUNT, 0)

        val hasUsageAccess = AppStorageStatsHelper.hasUsageAccess(context)

        if (!hasUsageAccess) {
            if (lastBytes >= 0L && lastAt > 0L) {
                return CacheNotificationSummary(
                    show = true,
                    compactLine = "Cache ${formatSize(lastBytes)}",
                    detailedLine = "Cache detectado: ${formatSize(lastBytes)} • última leitura ${formatTime(lastAt)}"
                )
            }

            return CacheNotificationSummary(
                show = true,
                compactLine = "Cache requer acesso",
                detailedLine = "Cache: libere Acesso ao uso em Limpeza > Aplicativos"
            )
        }

        val canUseRecentCache = lastBytes >= 0L && lastAt > 0L && (now - lastAt) <= SOFT_TTL_MS
        val blockedByCooldown = lastBytes >= 0L && lastAt > 0L && (now - lastAt) <= REFRESH_COOLDOWN_MS

        if (!forceRefresh && canUseRecentCache) {
            return CacheNotificationSummary(
                show = true,
                compactLine = "Cache ${formatSize(lastBytes)}",
                detailedLine = "Cache detectado: ${formatSize(lastBytes)} • ${lastCount} apps analisados • ${formatTime(lastAt)}"
            )
        }

        if (forceRefresh && blockedByCooldown) {
            return CacheNotificationSummary(
                show = true,
                compactLine = "Cache ${formatSize(lastBytes)}",
                detailedLine = "Cache detectado: ${formatSize(lastBytes)} • atualizado há pouco"
            )
        }

        val refreshed = refreshCache(context)

        if (refreshed != null) {
            prefs.edit()
                .putLong(KEY_CACHE_BYTES, refreshed.cacheBytes)
                .putLong(KEY_CACHE_UPDATED_AT, now)
                .putInt(KEY_CACHE_APPS_COUNT, refreshed.appsCount)
                .apply()

            return CacheNotificationSummary(
                show = true,
                compactLine = "Cache ${formatSize(refreshed.cacheBytes)}",
                detailedLine = "Cache detectado: ${formatSize(refreshed.cacheBytes)} • ${refreshed.appsCount} apps analisados • atualizado agora"
            )
        }

        if (lastBytes >= 0L && lastAt > 0L) {
            return CacheNotificationSummary(
                show = true,
                compactLine = "Cache ${formatSize(lastBytes)}",
                detailedLine = "Cache detectado: ${formatSize(lastBytes)} • última leitura ${formatTime(lastAt)}"
            )
        }

        return CacheNotificationSummary(
            show = true,
            compactLine = "Cache indisponível",
            detailedLine = "Cache detectado: indisponível no momento"
        )
    }

    private data class RefreshResult(
        val cacheBytes: Long,
        val appsCount: Int
    )

    private data class AppCandidate(
        val packageName: String,
        val appKind: Int,
        val detectedSize: Long
    )

    private fun refreshCache(context: Context): RefreshResult? {
        return try {
            @Suppress("DEPRECATION")
            val packages = context.packageManager.getInstalledPackages(0)

            val candidates = packages.mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                val appKind = when {
                    !isSystem -> 0
                    isUpdatedSystem -> 1
                    else -> 2
                }

                val detectedSize = estimateApkSize(appInfo)

                AppCandidate(
                    packageName = pkg.packageName,
                    appKind = appKind,
                    detectedSize = detectedSize
                )
            }
                .sortedWith(
                    compareBy<AppCandidate> { it.appKind }
                        .thenByDescending { it.detectedSize }
                )
                .take(MAX_APPS_TO_QUERY)

            var totalCache = 0L
            var count = 0

            for (candidate in candidates) {
                val stats = AppStorageStatsHelper.query(context, candidate.packageName)
                if (stats != null) {
                    totalCache += stats.cacheBytes.coerceAtLeast(0L)
                    count++
                }
            }

            if (count <= 0) {
                null
            } else {
                RefreshResult(
                    cacheBytes = totalCache.coerceAtLeast(0L),
                    appsCount = count
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun estimateApkSize(appInfo: ApplicationInfo): Long {
        return try {
            val files = mutableListOf<File>()

            if (!appInfo.sourceDir.isNullOrBlank()) {
                files.add(File(appInfo.sourceDir))
            }

            if (!appInfo.publicSourceDir.isNullOrBlank()) {
                files.add(File(appInfo.publicSourceDir))
            }

            try {
                appInfo.splitSourceDirs?.forEach { path ->
                    if (!path.isNullOrBlank()) {
                        files.add(File(path))
                    }
                }
            } catch (_: Throwable) {
            }

            files.distinctBy { it.absolutePath }
                .sumOf { file ->
                    if (file.exists() && file.isFile) file.length() else 0L
                }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun formatSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0

        return when {
            safe >= gb -> String.format(Locale.US, "%.2f GB", safe / gb)
            safe >= mb -> String.format(Locale.US, "%.0f MB", safe / mb)
            safe >= kb -> String.format(Locale.US, "%.0f KB", safe / kb)
            else -> "$safe B"
        }
    }

    private fun formatTime(timeMillis: Long): String {
        return try {
            SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(timeMillis))
        } catch (_: Throwable) {
            "--:--"
        }
    }
}

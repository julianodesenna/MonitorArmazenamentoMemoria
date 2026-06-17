package br.com.monitorarmazenamentomemoria

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File
import java.util.LinkedHashSet
import java.util.UUID

/*
 * N05F3_FIX1_tamanho_real_android_tentativa_final
 *
 * OBJETIVO:
 * Tentar mostrar dentro do app os valores reais que o Android mostra em:
 * Configurações > Aplicativos > Armazenamento
 *
 * Valores desejados:
 * - Aplicativo
 * - Dados
 * - Cache
 * - Total
 *
 * IMPORTANTE:
 * 1. Precisa da permissão no AndroidManifest.xml:
 *    android.permission.PACKAGE_USAGE_STATS
 *
 * 2. Também precisa liberar manualmente no celular:
 *    Configurações > Acesso ao uso > Monitor de Armazenamento e Memória > Permitir
 *
 * 3. Mesmo com isso, alguns Android/Samsung podem bloquear parte da consulta.
 *    Se bloquear, o app deve continuar mostrando “Tamanho detectado”, sem travar.
 *
 * NÃO apagar dados.
 * NÃO limpar cache automaticamente.
 * NÃO usar root.
 * NÃO usar ADB.
 * NÃO usar Shizuku.
 */
object AppStorageStatsHelper {

    data class AppStorageStats(
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
        val totalBytes: Long
    )

    fun hasUsageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }

        val appOpsAllowed = try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }

            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }

        if (appOpsAllowed) {
            return true
        }

        /*
         * Em alguns aparelhos, o AppOps pode não retornar MODE_ALLOWED de forma confiável.
         * Então fazemos uma segunda conferência prática: se o Android devolver estatísticas
         * de uso, consideramos que o Acesso ao uso está liberado.
         */
        return try {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - (7L * 24L * 60L * 60L * 1000L)
            val stats = usage.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                start,
                now
            )

            !stats.isNullOrEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Throwable) {
            }
        }
    }

    fun openAppDetails(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Throwable) {
            }
        }
    }

    fun query(context: Context, packageName: String): AppStorageStats? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }

        if (!hasUsageAccess(context)) {
            return null
        }

        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)

            val storageStatsManager = try {
                context.getSystemService(StorageStatsManager::class.java)
            } catch (_: Throwable) {
                context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            } ?: return null

            val storageManager = try {
                context.getSystemService(StorageManager::class.java)
            } catch (_: Throwable) {
                context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            }

            val candidateUuids = LinkedHashSet<UUID>()
            candidateUuids.add(StorageManager.UUID_DEFAULT)

            if (storageManager != null) {
                tryAddUuidForPath(candidateUuids, storageManager, appInfo.sourceDir)
                tryAddUuidForPath(candidateUuids, storageManager, appInfo.publicSourceDir)

                try {
                    appInfo.splitSourceDirs?.forEach { splitPath ->
                        tryAddUuidForPath(candidateUuids, storageManager, splitPath)
                    }
                } catch (_: Throwable) {
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    tryAddUuidForPath(candidateUuids, storageManager, appInfo.dataDir)
                }

                tryAddUuidForPath(candidateUuids, storageManager, context.filesDir.absolutePath)
            }

            var best: AppStorageStats? = null

            for (uuid in candidateUuids) {
                val current = tryQueryForUuid(
                    storageStatsManager = storageStatsManager,
                    uuid = uuid,
                    packageName = packageName
                )

                if (current != null) {
                    if (best == null || current.totalBytes > best.totalBytes) {
                        best = current
                    }
                }
            }

            best
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryAddUuidForPath(
        set: LinkedHashSet<UUID>,
        storageManager: StorageManager,
        path: String?
    ) {
        if (path.isNullOrBlank()) {
            return
        }

        try {
            val uuid = storageManager.getUuidForPath(File(path))
            set.add(uuid)
        } catch (_: Throwable) {
        }
    }

    private fun tryQueryForUuid(
        storageStatsManager: StorageStatsManager,
        uuid: UUID,
        packageName: String
    ): AppStorageStats? {
        return try {
            val stats = storageStatsManager.queryStatsForPackage(
                uuid,
                packageName,
                Process.myUserHandle()
            )

            val appBytes = safeLong(stats.appBytes)
            val dataBytes = safeLong(stats.dataBytes)
            val cacheBytes = safeLong(stats.cacheBytes)

            /*
             * Para o usuário entender melhor, mostramos total como soma visível:
             * App + Dados + Cache.
             * Alguns fabricantes podem incluir cache dentro de dados, mas este formato
             * fica mais parecido com a tela de armazenamento do Android/Samsung.
             */
            val totalBytes = safeLong(appBytes + dataBytes + cacheBytes)

            if (totalBytes <= 0L) {
                null
            } else {
                AppStorageStats(
                    appBytes = appBytes,
                    dataBytes = dataBytes,
                    cacheBytes = cacheBytes,
                    totalBytes = totalBytes
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeLong(value: Long): Long {
        return if (value < 0L) 0L else value
    }
}

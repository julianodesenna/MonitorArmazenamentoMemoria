package br.com.monitorarmazenamentomemoria

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import java.io.File

/*
 * N05F1_apps_tamanho_real_android
 *
 * OBJETIVO:
 * Tentar mostrar dentro do app os valores que o Android mostra em
 * Configurações > Aplicativos > Armazenamento:
 * - Aplicativo
 * - Dados
 * - Cache
 * - Total
 *
 * IMPORTANTE:
 * O Android normalmente exige a permissão especial Acesso ao uso.
 * Se o usuário não liberar, a consulta pode falhar e o app deve continuar
 * mostrando o tamanho detectado, sem travar.
 *
 * NÃO apagar dados.
 * NÃO limpar cache automaticamente.
 * NÃO prometer 100% de precisão em todos os aparelhos.
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
        return try {
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

            val storageStatsManager = context.getSystemService(StorageStatsManager::class.java)
            val storageManager = context.getSystemService(StorageManager::class.java)

            val sourcePath = appInfo.sourceDir ?: context.filesDir.absolutePath
            val uuid = try {
                storageManager.getUuidForPath(File(sourcePath))
            } catch (_: Throwable) {
                StorageManager.UUID_DEFAULT
            }

            val stats = storageStatsManager.queryStatsForPackage(
                uuid,
                packageName,
                Process.myUserHandle()
            )

            val appBytes = safeLong(stats.appBytes)
            val dataBytes = safeLong(stats.dataBytes)
            val cacheBytes = safeLong(stats.cacheBytes)

            /*
             * Em aparelhos diferentes, o Android/fabricante pode apresentar dados
             * de forma ligeiramente diferente. Para ficar mais parecido com a tela
             * do Android, mostramos App + Dados + Cache como Total.
             */
            val totalBytes = safeLong(appBytes + dataBytes + cacheBytes)

            AppStorageStats(
                appBytes = appBytes,
                dataBytes = dataBytes,
                cacheBytes = cacheBytes,
                totalBytes = totalBytes
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeLong(value: Long): Long {
        return if (value < 0L) 0L else value
    }
}

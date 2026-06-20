package br.com.monitorarmazenamentomemoria

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.ArrayDeque

/*
 * ARQUIVO_10
 *
 * Scanner leve:
 * - Downloads
 * - Documents
 * - Pictures
 * - Movies
 * - DCIM
 *
 * Limites de segurança:
 * - profundidade máxima: 4 pastas;
 * - até 2500 entradas por leitura;
 * - pausa mínima de 10 min para leitura automática;
 * - não tenta entrar em pastas privadas de outros aplicativos.
 */
object LargeFileAlertScanner {

    private const val PREFS = "large_file_alert_scanner"
    private const val KEY_READY = "ready"
    private const val KEY_SEEN = "seen"
    private const val KEY_LAST_SCAN = "last_scan"

    private const val AUTO_SCAN_COOLDOWN_MS = 10L * 60L * 1000L
    private const val MAX_ENTRIES = 2500
    private const val MAX_DEPTH = 4
    private const val MAX_SAVED_IDS = 900

    data class LargeFile(
        val name: String,
        val sizeGb: Double,
        val location: String
    )

    data class ScanResult(
        val newLargeFile: LargeFile?,
        val message: String
    )

    fun scan(
        context: Context,
        minimumGb: Double,
        forceScan: Boolean
    ): ScanResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastScan = prefs.getLong(KEY_LAST_SCAN, 0L)

        if (!forceScan && lastScan > 0L && now - lastScan < AUTO_SCAN_COOLDOWN_MS) {
            return ScanResult(
                newLargeFile = null,
                message = "Varredura de arquivos aguardando próxima leitura"
            )
        }

        val minBytes = (minimumGb * 1024.0 * 1024.0 * 1024.0).toLong()
            .coerceAtLeast(1L)

        val roots = publicRoots()
        val candidates = mutableListOf<File>()

        var entries = 0

        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue

            val queue: ArrayDeque<Pair<File, Int>> = ArrayDeque()
            queue.add(root to 0)

            while (queue.isNotEmpty() && entries < MAX_ENTRIES) {
                val current = queue.removeFirst()
                val file = current.first
                val depth = current.second
                entries++

                if (file.isFile) {
                    if (file.length() >= minBytes) {
                        candidates.add(file)
                    }
                    continue
                }

                if (!file.isDirectory || depth >= MAX_DEPTH) continue

                val children = try {
                    file.listFiles()
                } catch (_: Throwable) {
                    null
                } ?: continue

                for (child in children) {
                    if (child.name.startsWith(".")) continue
                    queue.add(child to (depth + 1))
                }
            }

            if (entries >= MAX_ENTRIES) break
        }

        val currentIds = candidates
            .sortedByDescending { it.length() }
            .take(MAX_SAVED_IDS)
            .map { identifier(it) }
            .toMutableSet()

        val initialized = prefs.getBoolean(KEY_READY, false)
        val known = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        prefs.edit()
            .putBoolean(KEY_READY, true)
            .putStringSet(KEY_SEEN, currentIds)
            .putLong(KEY_LAST_SCAN, now)
            .apply()

        if (!initialized) {
            return ScanResult(
                newLargeFile = null,
                message = "Base criada. Novos arquivos grandes serão avisados."
            )
        }

        val newFiles = candidates
            .filter { identifier(it) !in known }
            .sortedByDescending { it.length() }

        val found = newFiles.firstOrNull()

        return if (found != null) {
            ScanResult(
                newLargeFile = LargeFile(
                    name = found.name,
                    sizeGb = found.length().toDouble() /
                        (1024.0 * 1024.0 * 1024.0),
                    location = locationName(found)
                ),
                message = "Arquivo grande novo encontrado"
            )
        } else {
            ScanResult(
                newLargeFile = null,
                message = "Nenhum arquivo grande novo encontrado"
            )
        }
    }

    private fun publicRoots(): List<File> {
        return listOf(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            ),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            ),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            ),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM
            )
        ).distinctBy { it.absolutePath }
    }

    private fun identifier(file: File): String {
        return "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    }

    private fun locationName(file: File): String {
        val path = file.absolutePath

        return when {
            path.contains("/Download") -> "Downloads"
            path.contains("/Documents") -> "Documentos"
            path.contains("/Pictures") -> "Imagens"
            path.contains("/Movies") -> "Vídeos"
            path.contains("/DCIM") -> "Câmera/DCIM"
            else -> "Armazenamento público"
        }
    }
}

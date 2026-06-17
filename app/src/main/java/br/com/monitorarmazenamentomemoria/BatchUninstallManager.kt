package br.com.monitorarmazenamentomemoria

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.Locale

/*
 * N05E1_base_desinstalacao_multipla_fila
 *
 * OBJETIVO:
 * Criar uma fila segura para desinstalar vários aplicativos selecionados.
 *
 * IMPORTANTE:
 * O Android normalmente NÃO permite que um app comum apague vários aplicativos
 * silenciosamente sem confirmação do usuário.
 *
 * Este gerenciador faz o fluxo profissional seguro:
 * 1. O usuário seleciona vários apps dentro do nosso app.
 * 2. O app chama a tela oficial de desinstalação do Android.
 * 3. O usuário confirma.
 * 4. Ao voltar, o app chama o próximo da fila.
 *
 * NÃO usar root.
 * NÃO usar ADB.
 * NÃO usar Shizuku.
 * NÃO tentar apagar app silenciosamente.
 * NÃO tentar apagar app do sistema.
 * NÃO tentar apagar o próprio app.
 */

object BatchUninstallManager {

    const val REQUEST_CODE_UNINSTALL_BATCH = 45081

    private const val PREFS = "batch_uninstall_queue_n05e"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_TOTAL = "total"
    private const val KEY_REMOVED = "removed"
    private const val KEY_CANCELLED = "cancelled"
    private const val KEY_FAILED = "failed"
    private const val KEY_RUNNING = "running"

    data class Target(
        val packageName: String,
        val label: String,
        val sizeText: String = "",
        val sensitive: Boolean = false
    )

    fun hasRunningQueue(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_RUNNING, false)
    }

    fun start(activity: Activity, targets: List<Target>) {
        val cleanTargets = targets
            .filter { it.packageName.isNotBlank() }
            .filter { it.packageName != activity.packageName }
            .distinctBy { it.packageName }

        if (cleanTargets.isEmpty()) {
            showMessage(
                activity = activity,
                title = "Nada selecionado",
                message = "Selecione pelo menos um aplicativo para desinstalar."
            )
            return
        }

        val sensitiveCount = cleanTargets.count { it.sensitive }

        val message = buildString {
            append("Aplicativos selecionados: ")
            append(cleanTargets.size)
            append("\n\n")

            if (sensitiveCount > 0) {
                append("Atenção: ")
                append(sensitiveCount)
                append(" app(s) marcado(s) como sensível/financeiro.\n\n")
            }

            append("O Android vai pedir confirmação para cada aplicativo.")
            append("\n\n")
            append("O app continuará a fila automaticamente após cada confirmação.")
        }

        AlertDialog.Builder(activity)
            .setTitle("Desinstalar selecionados?")
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                saveQueue(activity, cleanTargets)
                launchCurrent(activity)
            }
            .show()
    }

    fun handleActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int
    ): Boolean {
        if (requestCode != REQUEST_CODE_UNINSTALL_BATCH) {
            return false
        }

        if (!hasRunningQueue(activity)) {
            return true
        }

        val p = prefs(activity)
        val currentIndex = p.getInt(KEY_INDEX, 0)
        val queue = readQueue(activity)

        if (queue.isEmpty()) {
            clear(activity)
            return true
        }

        val target = queue.getOrNull(currentIndex)

        if (target == null) {
            finishQueue(activity)
            return true
        }

        val stillInstalled = isPackageInstalled(activity, target.packageName)

        when {
            !stillInstalled -> {
                increment(activity, KEY_REMOVED)
            }

            resultCode == Activity.RESULT_CANCELED -> {
                increment(activity, KEY_CANCELLED)
            }

            else -> {
                increment(activity, KEY_FAILED)
            }
        }

        p.edit()
            .putInt(KEY_INDEX, currentIndex + 1)
            .apply()

        launchCurrent(activity)
        return true
    }

    fun resumeIfNeeded(activity: Activity) {
        if (!hasRunningQueue(activity)) {
            return
        }

        val p = prefs(activity)
        val index = p.getInt(KEY_INDEX, 0)
        val queue = readQueue(activity)

        if (queue.isEmpty() || index >= queue.size) {
            finishQueue(activity)
        }
    }

    fun cancel(activity: Activity) {
        clear(activity)
        showMessage(
            activity = activity,
            title = "Fila cancelada",
            message = "A desinstalação em lote foi cancelada."
        )
    }

    private fun launchCurrent(activity: Activity) {
        val p = prefs(activity)
        val index = p.getInt(KEY_INDEX, 0)
        val queue = readQueue(activity)

        if (queue.isEmpty() || index >= queue.size) {
            finishQueue(activity)
            return
        }

        val target = queue[index]

        if (target.packageName == activity.packageName) {
            increment(activity, KEY_FAILED)
            p.edit().putInt(KEY_INDEX, index + 1).apply()
            launchCurrent(activity)
            return
        }

        if (!isPackageInstalled(activity, target.packageName)) {
            increment(activity, KEY_REMOVED)
            p.edit().putInt(KEY_INDEX, index + 1).apply()
            launchCurrent(activity)
            return
        }

        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${target.packageName}")
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            activity.startActivityForResult(intent, REQUEST_CODE_UNINSTALL_BATCH)
        } catch (_: Throwable) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${target.packageName}")
                }

                activity.startActivity(intent)
                increment(activity, KEY_FAILED)
                p.edit().putInt(KEY_INDEX, index + 1).apply()
            } catch (_: Throwable) {
                increment(activity, KEY_FAILED)
                p.edit().putInt(KEY_INDEX, index + 1).apply()
            }

            launchCurrent(activity)
        }
    }

    private fun finishQueue(activity: Activity) {
        val p = prefs(activity)

        val total = p.getInt(KEY_TOTAL, 0)
        val removed = p.getInt(KEY_REMOVED, 0)
        val cancelled = p.getInt(KEY_CANCELLED, 0)
        val failed = p.getInt(KEY_FAILED, 0)

        clear(activity)

        val message = buildString {
            append("Total selecionado: ")
            append(total)
            append("\n")

            append("Removidos: ")
            append(removed)
            append("\n")

            append("Cancelados: ")
            append(cancelled)
            append("\n")

            append("Falharam: ")
            append(failed)
            append("\n\n")

            append("RESULTADO FINAL: FILA DE DESINSTALAÇÃO FINALIZADA")
        }

        showMessage(
            activity = activity,
            title = "Desinstalação concluída",
            message = message
        )
    }

    private fun saveQueue(context: Context, targets: List<Target>) {
        val encoded = targets.joinToString("\n") { target ->
            listOf(
                encode(target.packageName),
                encode(target.label),
                encode(target.sizeText),
                target.sensitive.toString()
            ).joinToString("|")
        }

        prefs(context).edit()
            .putString(KEY_QUEUE, encoded)
            .putInt(KEY_INDEX, 0)
            .putInt(KEY_TOTAL, targets.size)
            .putInt(KEY_REMOVED, 0)
            .putInt(KEY_CANCELLED, 0)
            .putInt(KEY_FAILED, 0)
            .putBoolean(KEY_RUNNING, true)
            .apply()
    }

    private fun readQueue(context: Context): List<Target> {
        val raw = prefs(context).getString(KEY_QUEUE, "") ?: ""

        if (raw.isBlank()) {
            return emptyList()
        }

        return raw
            .lines()
            .mapNotNull { line ->
                val parts = line.split("|")

                if (parts.size < 4) {
                    null
                } else {
                    Target(
                        packageName = decode(parts[0]),
                        label = decode(parts[1]),
                        sizeText = decode(parts[2]),
                        sensitive = parts[3].lowercase(Locale.ROOT) == "true"
                    )
                }
            }
            .filter { it.packageName.isNotBlank() }
    }

    private fun increment(context: Context, key: String) {
        val p = prefs(context)
        val current = p.getInt(key, 0)

        p.edit()
            .putInt(key, current + 1)
            .apply()
    }

    private fun clear(context: Context) {
        prefs(context).edit()
            .clear()
            .apply()
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun showMessage(
        activity: Activity,
        title: String,
        message: String
    ) {
        try {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Throwable) {
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(value: String): String {
        return Uri.encode(value ?: "")
    }

    private fun decode(value: String): String {
        return Uri.decode(value ?: "")
    }
}

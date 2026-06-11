package br.com.monitorarmazenamentomemoria

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class CleanupActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView

    private var minSizeMb = 10
    private var orderMode = "size"
    private var currentCategory = "home"
    private var allFiles: List<CleanFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        showHome()
        scanFiles()
    }

    private fun buildScreen() {
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(Color.rgb(246, 248, 252))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(18), dp(18), dp(18), dp(10))
        header.setBackgroundColor(Color.rgb(246, 248, 252))

        val title = TextView(this)
        title.text = "Limpeza"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        header.addView(title)

        statusText = TextView(this)
        statusText.text = "Preparando análise..."
        statusText.textSize = 14f
        statusText.setTextColor(Color.rgb(80, 90, 110))
        statusText.setPadding(0, dp(4), 0, 0)
        header.addView(statusText)

        val topButtons = LinearLayout(this)
        topButtons.orientation = LinearLayout.HORIZONTAL
        topButtons.setPadding(0, dp(10), 0, 0)

        val refresh = Button(this)
        refresh.text = "Atualizar"
        refresh.setOnClickListener { scanFiles() }

        val cache = Button(this)
        cache.text = "Cache Android"
        cache.setOnClickListener { openAndroidCache() }

        topButtons.addView(refresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topButtons.addView(cache, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(topButtons)

        main.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(18), dp(6), dp(18), dp(8))
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        main.addView(bottomNav())

        setContentView(main)
    }

    private fun bottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(10), dp(8), dp(10), dp(8))
        nav.setBackgroundColor(Color.WHITE)

        nav.addView(navItem("📊\nPainel", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        nav.addView(navItem("▦\nWidget", false) {
            Toast.makeText(this, "Abra a aba Widget pelo painel principal", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
        })

        nav.addView(navItem("🧹\nLimpeza", true) {
            showHome()
        })

        nav.addView(navItem("⚙\nConfig.", false) {
            Toast.makeText(this, "Abra Config. pelo painel principal", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
        })

        return nav
    }

    private fun navItem(text: String, active: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.rgb(20, 92, 210) else Color.rgb(80, 90, 110))
            setPadding(dp(6), dp(5), dp(6), dp(5))
            if (active) background = rounded(Color.rgb(232, 241, 255), Color.TRANSPARENT, dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
    }

    private fun showHome() {
        currentCategory = "home"
        content.removeAllViews()

        content.addView(memoryCard())
        content.addView(permissionCard())
        content.addView(filterCard())

        val summaryTitle = TextView(this)
        summaryTitle.text = "Categorias"
        summaryTitle.textSize = 22f
        summaryTitle.setTypeface(null, Typeface.BOLD)
        summaryTitle.setTextColor(Color.rgb(14, 26, 56))
        summaryTitle.setPadding(0, dp(8), 0, dp(8))
        content.addView(summaryTitle)

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL

        val rows = listOf(
            listOf("Arquivos grandes", "Vídeos"),
            listOf("Imagens", "Áudios"),
            listOf("WhatsApp", "Backups"),
            listOf("Downloads", "Recentes"),
            listOf("Sensíveis", "APKs")
        )

        for (pair in rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(categoryCard(pair[0]), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(categoryCard(pair[1]), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            grid.addView(row)
        }

        content.addView(grid)
    }

    private fun memoryCard(): LinearLayout {
        val data = Monitor.read(this)
        val box = card()

        val title = TextView(this)
        title.text = "Memória"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        box.addView(title)

        val info = TextView(this)
        info.text = "RAM usada: ${data.memoryUsedPercent}%\nRAM livre: ${Monitor.formatPrecise(data.memoryFree)}\n\nO Android gerencia a RAM automaticamente. Aqui o app analisa e atualiza a leitura."
        info.textSize = 15f
        info.setTextColor(Color.rgb(80, 90, 110))
        info.setPadding(0, dp(8), 0, dp(8))
        box.addView(info)

        val analyze = Button(this)
        analyze.text = "Analisar memória"
        analyze.setOnClickListener {
            MonitorWidgetProvider.updateAll(this)
            Toast.makeText(this, "Memória analisada", Toast.LENGTH_SHORT).show()
            showHome()
        }
        box.addView(analyze)

        return box
    }

    private fun permissionCard(): LinearLayout {
        val box = card()
        val granted = hasAllFilesAccess()

        val title = TextView(this)
        title.text = if (granted) "Acesso liberado" else "Acesso aos arquivos"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        box.addView(title)

        val info = TextView(this)
        info.text = if (granted) {
            "O app pode analisar arquivos grandes, recentes, WhatsApp, backups e sensíveis."
        } else {
            "Libere acesso amplo para analisar arquivos do armazenamento."
        }
        info.textSize = 15f
        info.setTextColor(Color.rgb(80, 90, 110))
        info.setPadding(0, dp(8), 0, dp(8))
        box.addView(info)

        val btn = Button(this)
        btn.text = if (granted) "Permissão liberada" else "Liberar acesso"
        btn.setOnClickListener { requestAllFilesAccess() }
        box.addView(btn)

        return box
    }

    private fun filterCard(): LinearLayout {
        val box = card()

        val title = TextView(this)
        title.text = "Filtros"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        box.addView(title)

        val sizeRow = LinearLayout(this)
        sizeRow.orientation = LinearLayout.HORIZONTAL
        sizeRow.addView(filterButton("+10 MB", 10), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(filterButton("+50 MB", 50), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(filterButton("+100 MB", 100), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(sizeRow)

        val orderRow = LinearLayout(this)
        orderRow.orientation = LinearLayout.HORIZONTAL

        val size = Button(this)
        size.text = if (orderMode == "size") "✓ Maior" else "Maior"
        size.setOnClickListener {
            orderMode = "size"
            showCurrentList()
        }

        val date = Button(this)
        date.text = if (orderMode == "date") "✓ Recente" else "Recente"
        date.setOnClickListener {
            orderMode = "date"
            showCurrentList()
        }

        orderRow.addView(size, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        orderRow.addView(date, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(orderRow)

        return box
    }

    private fun filterButton(label: String, mb: Int): Button {
        return Button(this).apply {
            text = if (minSizeMb == mb) "✓ $label" else label
            setOnClickListener {
                minSizeMb = mb
                showCurrentList()
            }
        }
    }

    private fun categoryCard(name: String): LinearLayout {
        val files = categoryFiles(name)
        val total = files.sumOf { it.size }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        box.background = rounded(Color.WHITE, Color.rgb(225, 230, 240), dp(18))

        val icon = when (name) {
            "Arquivos grandes" -> "📦"
            "Vídeos" -> "🎬"
            "Imagens" -> "🖼"
            "Áudios" -> "🎵"
            "WhatsApp" -> "💬"
            "Backups" -> "🗄"
            "Downloads" -> "⬇"
            "Recentes" -> "🕒"
            "Sensíveis" -> "⚠"
            "APKs" -> "📱"
            else -> "📁"
        }

        val title = TextView(this)
        title.text = "$icon\n$name"
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        box.addView(title)

        val info = TextView(this)
        info.text = "${files.size} arquivos\n${Monitor.formatPrecise(total)}"
        info.textSize = 13f
        info.setTextColor(Color.rgb(80, 90, 110))
        info.setPadding(0, dp(6), 0, 0)
        box.addView(info)

        box.setOnClickListener {
            showList(name)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(dp(4), dp(4), dp(4), dp(8))
        box.layoutParams = params

        return box
    }

    private fun showCurrentList() {
        if (currentCategory == "home") showHome()
        else showList(currentCategory)
    }

    private fun showList(category: String) {
        currentCategory = category
        content.removeAllViews()

        val back = Button(this)
        back.text = "← Voltar para categorias"
        back.setOnClickListener { showHome() }
        content.addView(back)

        val title = TextView(this)
        title.text = category
        title.textSize = 26f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        title.setPadding(0, dp(8), 0, dp(8))
        content.addView(title)

        content.addView(filterCard())

        val files = ordered(categoryFiles(category)).take(80)

        val summary = TextView(this)
        summary.text = "${files.size} arquivos mostrados • ${Monitor.formatPrecise(files.sumOf { it.size })}"
        summary.textSize = 14f
        summary.setTextColor(Color.rgb(80, 90, 110))
        summary.setPadding(0, dp(4), 0, dp(12))
        content.addView(summary)

        if (files.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Nenhum arquivo encontrado para este filtro."
            empty.textSize = 16f
            empty.setTextColor(Color.rgb(80, 90, 110))
            content.addView(empty)
            return
        }

        for (f in files) {
            content.addView(fileRow(f))
        }
    }

    private fun ordered(files: List<CleanFile>): List<CleanFile> {
        return if (orderMode == "date") files.sortedByDescending { it.modified }
        else files.sortedByDescending { it.size }
    }

    private fun categoryFiles(category: String): List<CleanFile> {
        val minBytes = minSizeMb.toLong() * 1024L * 1024L

        return when (category) {
            "Arquivos grandes" -> allFiles.filter { it.size >= minBytes }
            "Vídeos" -> allFiles.filter { it.category == "Vídeos" && it.size >= minBytes }
            "Imagens" -> allFiles.filter { it.category == "Imagens" && it.size >= minBytes }
            "Áudios" -> allFiles.filter { it.category == "Áudios" && it.size >= 1 }
            "WhatsApp" -> allFiles.filter { it.path.lowercase(Locale.ROOT).contains("whatsapp") }
            "Backups" -> allFiles.filter { it.category == "Backups" || it.category == "Backups do WhatsApp" }
            "Downloads" -> allFiles.filter { it.path.lowercase(Locale.ROOT).contains("/download") }
            "Recentes" -> allFiles.sortedByDescending { it.modified }.take(200)
            "Sensíveis" -> allFiles.filter { it.risk == "alto" }
            "APKs" -> allFiles.filter { it.category == "APKs" }
            else -> allFiles
        }
    }

    private fun fileRow(file: CleanFile): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        row.background = rounded(
            if (file.risk == "alto") Color.rgb(255, 246, 232) else Color.WHITE,
            if (file.risk == "alto") Color.rgb(255, 170, 80) else Color.rgb(225, 230, 240),
            dp(16)
        )

        val title = TextView(this)
        title.text = if (file.risk == "alto") "⚠ ${file.name}" else file.name
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        row.addView(title)

        val detail = TextView(this)
        detail.text = "${file.type} • ${Monitor.formatPrecise(file.size)} • ${formatDate(file.modified)}"
        detail.textSize = 13f
        detail.setTextColor(Color.rgb(80, 90, 110))
        detail.setPadding(0, dp(4), 0, dp(4))
        row.addView(detail)

        val path = TextView(this)
        path.text = file.path
        path.textSize = 12f
        path.setTextColor(Color.rgb(110, 120, 140))
        row.addView(path)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, dp(8), 0, 0)

        val open = Button(this)
        open.text = "Abrir"
        open.setOnClickListener { openFile(file) }

        val details = Button(this)
        details.text = "Detalhes"
        details.setOnClickListener { showDetails(file) }

        buttons.addView(open, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(buttons)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(10))
        row.layoutParams = params

        return row
    }

    private fun showDetails(file: CleanFile) {
        val msg = "Nome:\n${file.name}\n\n" +
                "Categoria:\n${file.category}\n\n" +
                "Tipo:\n${file.type}\n\n" +
                "Tamanho:\n${Monitor.formatPrecise(file.size)}\n\n" +
                "Modificado:\n${formatDate(file.modified)}\n\n" +
                "Risco:\n${riskLabel(file.risk)}\n\n" +
                "Local:\n${file.path}\n\n" +
                "A exclusão será adicionada na próxima etapa com confirmação."

        AlertDialog.Builder(this)
            .setTitle("Detalhes")
            .setMessage(msg)
            .setPositiveButton("Abrir") { _, _ -> openFile(file) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun openFile(file: CleanFile) {
        try {
            val f = File(file.path)
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                f
            )

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeType(f))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            startActivity(Intent.createChooser(intent, "Abrir arquivo"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app encontrado para abrir este arquivo", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o arquivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAndroidCache() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_STORAGE))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun scanFiles() {
        statusText.text = "Analisando arquivos..."
        thread {
            val result = mutableListOf<CleanFile>()
            val roots = importantRoots()
            val started = System.currentTimeMillis()
            val maxFiles = 15000

            fun scan(dir: File, depth: Int) {
                if (result.size >= maxFiles) return
                if (depth > 12) return
                if (System.currentTimeMillis() - started > 45000) return

                val list = try { dir.listFiles() } catch (_: Exception) { null } ?: return

                for (f in list) {
                    if (result.size >= maxFiles) return
                    if (System.currentTimeMillis() - started > 45000) return

                    try {
                        if (f.isDirectory) {
                            if (!f.name.startsWith(".")) scan(f, depth + 1)
                        } else {
                            val size = f.length()
                            val path = f.absolutePath
                            result.add(
                                CleanFile(
                                    name = f.name,
                                    path = path,
                                    size = size,
                                    modified = f.lastModified(),
                                    type = fileType(f),
                                    category = fileCategory(f),
                                    risk = risk(f)
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            for (root in roots) {
                if (root.exists()) scan(root, 0)
            }

            allFiles = result.distinctBy { it.path }

            runOnUiThread {
                statusText.text = "Atualizado: ${formatDate(System.currentTimeMillis())} • ${allFiles.size} arquivos"
                showCurrentList()
            }
        }
    }

    private fun importantRoots(): List<File> {
        val base = Environment.getExternalStorageDirectory()
        return listOf(
            File(base, "Download"),
            File(base, "Downloads"),
            File(base, "DCIM"),
            File(base, "Movies"),
            File(base, "Pictures"),
            File(base, "WhatsApp"),
            File(base, "Android/media/com.whatsapp"),
            File(base, "Android/media"),
            base
        ).distinctBy { it.absolutePath }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true
    }

    private fun requestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun fileCategory(file: File): String {
        val n = file.name.lowercase(Locale.ROOT)
        val p = file.absolutePath.lowercase(Locale.ROOT)

        return when {
            p.contains("whatsapp") && (n.contains("msgstore") || p.contains("/databases/")) -> "Backups do WhatsApp"
            p.contains("whatsapp") -> "WhatsApp"
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".avi") -> "Vídeos"
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") -> "Imagens"
            n.endsWith(".opus") || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") -> "Áudios"
            n.endsWith(".apk") -> "APKs"
            n.endsWith(".db") || n.endsWith(".sqlite") || n.contains("crypt") || n.endsWith(".bak") || n.endsWith(".backup") -> "Backups"
            else -> "Outros"
        }
    }

    private fun fileType(file: File): String {
        val n = file.name.lowercase(Locale.ROOT)
        val p = file.absolutePath.lowercase(Locale.ROOT)

        return when {
            p.contains("whatsapp") && n.contains("msgstore") -> "Backup criptografado do WhatsApp"
            p.contains("whatsapp") && n.endsWith(".opus") -> "Áudio do WhatsApp"
            p.contains("whatsapp") && n.endsWith(".mp4") -> "Vídeo do WhatsApp"
            p.contains("whatsapp") -> "Arquivo do WhatsApp"
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") -> "Vídeo"
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") -> "Imagem"
            n.endsWith(".opus") || n.endsWith(".mp3") || n.endsWith(".m4a") -> "Áudio"
            n.endsWith(".apk") -> "APK"
            n.endsWith(".pdf") -> "PDF"
            n.endsWith(".db") || n.contains("crypt") -> "Banco de dados / backup"
            else -> "Arquivo"
        }
    }

    private fun risk(file: File): String {
        val n = file.name.lowercase(Locale.ROOT)
        val p = file.absolutePath.lowercase(Locale.ROOT)

        val high = listOf("msgstore", "wa.db", "database", "databases", "backup", "keystore", "certificate", "certificado", "token", "auth", "senha", "password", "cpf", "rg", "cnh", "contrato", "extrato")
        val ext = listOf(".db", ".sqlite", ".crypt", ".crypt12", ".crypt14", ".bak", ".backup", ".key", ".pem", ".p12", ".pfx")

        if (high.any { p.contains(it) }) return "alto"
        if (ext.any { n.endsWith(it) }) return "alto"
        return "normal"
    }

    private fun riskLabel(risk: String): String {
        return if (risk == "alto") "Alto — conferir antes de excluir" else "Normal"
    }

    private fun mimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun formatDate(ms: Long): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, Color.rgb(225, 230, 240), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
    }

    private fun rounded(bg: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bg)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

data class CleanFile(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val type: String,
    val category: String,
    val risk: String
)

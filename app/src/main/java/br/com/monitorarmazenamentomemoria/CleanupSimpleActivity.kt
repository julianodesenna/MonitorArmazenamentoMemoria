package br.com.monitorarmazenamentomemoria

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.net.Uri
import android.content.pm.PackageManager
import android.Manifest
import android.content.Intent
import android.provider.MediaStore
import android.content.ContentUris
import android.content.ClipData
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.os.StrictMode
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class CleanupSimpleActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView

    private var currentCategory = ""
    private var minSizeMb = 10
    private var orderMode = "size"
    private var allFiles: List<FileItem> = emptyList()
    private val selectedFiles = mutableSetOf<String>()
    private lateinit var selectedInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        scanFiles()
        drawHome()
    }

    private fun buildScreen() {
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(Color.rgb(246, 248, 252))

        val contentScroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(18), dp(18), dp(12))
        contentScroll.addView(root)

        main.addView(contentScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        main.addView(bottomNav())
        setContentView(main)
    }

    private fun drawHome() {
        currentCategory = ""
        root.removeAllViews()

        val data = Monitor.read(this)

        val title = TextView(this)
        title.text = "Limpeza"
        title.textSize = 30f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        root.addView(title)

        statusText = TextView(this)
        statusText.text = if (allFiles.isEmpty()) "Analisando arquivos..." else "${allFiles.size} arquivos analisados"
        statusText.textSize = 14f
        statusText.setTextColor(Color.rgb(80, 90, 110))
        statusText.setPadding(0, dp(4), 0, dp(14))
        root.addView(statusText)

        val summary = card()
        summary.addView(titleText("Resumo do aparelho"))

        val summaryBody = TextView(this)
        summaryBody.text =
            "Armazenamento usado: ${data.storageUsedPercent}%\n" +
            "Livre: ${Monitor.format(data.storageFree)}\n" +
            "RAM usada: ${data.memoryUsedPercent}%\n" +
            "RAM livre: ${Monitor.format(data.memoryFree)}"
        summaryBody.textSize = 16f
        summaryBody.setTextColor(Color.rgb(70, 80, 100))
        summaryBody.setPadding(0, dp(8), 0, 0)
        summary.addView(summaryBody)
        root.addView(summary)

        val actions = card()
        actions.addView(titleText("Ações rápidas"))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val update = Button(this)
        update.text = "Atualizar"
        update.setOnClickListener {
            scanFiles()
            Toast.makeText(this, "Analisando arquivos novamente", Toast.LENGTH_SHORT).show()
        }

        val androidStorage = Button(this)
        androidStorage.text = "Armazenamento"
        androidStorage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        row.addView(update, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(androidStorage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(row)

        val accessFiles = Button(this)
        accessFiles.text = if (hasStorageAccess()) "Acesso aos arquivos liberado" else "Liberar acesso aos arquivos"
        accessFiles.setOnClickListener {
            requestStorageAccess()
        }
        actions.addView(accessFiles)

        root.addView(actions)

        val categoriesTitle = TextView(this)
        categoriesTitle.text = "Categorias"
        categoriesTitle.textSize = 22f
        categoriesTitle.setTypeface(null, Typeface.BOLD)
        categoriesTitle.setTextColor(Color.rgb(14, 26, 56))
        categoriesTitle.setPadding(0, dp(8), 0, dp(10))
        root.addView(categoriesTitle)

        addCategoryRow("📦", "Arquivos grandes", "Arquivos acima do filtro", "🎬", "Vídeos", "Filtrar vídeos")
        addCategoryRow("🖼", "Imagens", "Fotos e imagens", "🎵", "Áudios", "Áudios e mensagens de voz")
        addCategoryRow("💬", "WhatsApp", "Mídias e backups", "🗄", "Backups", "Bancos de dados e cópias")
        addCategoryRow("🕒", "Recentes", "Arquivos modificados recentemente", "⚠", "Sensíveis", "Arquivos que exigem cuidado")
        addCategoryRow("⬇", "Downloads", "Arquivos baixados", "📱", "APKs", "Instaladores antigos")
    }

    private fun addCategoryRow(icon1: String, title1: String, desc1: String, icon2: String, title2: String, desc2: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(categoryCard(icon1, title1, desc1), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(categoryCard(icon2, title2, desc2), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
    }

    private fun categoryCard(icon: String, title: String, desc: String): LinearLayout {
        val files = categoryFiles(title)
        val total = files.sumOf { it.size }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        box.background = rounded(Color.WHITE, Color.rgb(225, 230, 240), dp(18))

        val t = TextView(this)
        t.text = "$icon\n$title"
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.rgb(14, 26, 56))
        box.addView(t)

        val d = TextView(this)
        d.text = "$desc\n${files.size} arquivos • ${formatSize(total)}"
        d.textSize = 13f
        d.setTextColor(Color.rgb(80, 90, 110))
        d.setPadding(0, dp(6), 0, 0)
        box.addView(d)

        box.setOnClickListener {
            showCategory(title, desc)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(dp(4), dp(4), dp(4), dp(8))
        box.layoutParams = params

        return box
    }

    private fun showCategory(categoryTitle: String, categoryDesc: String) {
        currentCategory = categoryTitle
        root.removeAllViews()

        val back = Button(this)
        back.text = "← Voltar para categorias"
        back.setOnClickListener { drawHome() }
        root.addView(back)

        val title = TextView(this)
        title.text = categoryTitle
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        title.setPadding(0, dp(12), 0, dp(4))
        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = categoryDesc
        subtitle.textSize = 15f
        subtitle.setTextColor(Color.rgb(80, 90, 110))
        subtitle.setPadding(0, 0, 0, dp(14))
        root.addView(subtitle)

        val filter = card()
        filter.addView(titleText("Filtros"))

        val sizeRow = LinearLayout(this)
        sizeRow.orientation = LinearLayout.HORIZONTAL

        val f10 = filterButton("+10 MB", 10)
        val f50 = filterButton("+50 MB", 50)
        val f100 = filterButton("+100 MB", 100)

        sizeRow.addView(f10, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(f50, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(f100, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filter.addView(sizeRow)

        val orderRow = LinearLayout(this)
        orderRow.orientation = LinearLayout.HORIZONTAL

        val maior = Button(this)
        maior.text = if (orderMode == "size") "✓ Maior primeiro" else "Maior primeiro"
        maior.setOnClickListener {
            orderMode = "size"
            showCategory(categoryTitle, categoryDesc)
        }

        val recente = Button(this)
        recente.text = if (orderMode == "date") "✓ Mais recente" else "Mais recente"
        recente.setOnClickListener {
            orderMode = "date"
            showCategory(categoryTitle, categoryDesc)
        }

        orderRow.addView(maior, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        orderRow.addView(recente, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filter.addView(orderRow)

        root.addView(filter)

        val files = ordered(categoryFiles(categoryTitle)).take(80)

        val selectionBox = card()
        selectionBox.addView(titleText("Seleção"))

        selectedInfoText = TextView(this)
        selectedInfoText.textSize = 14f
        selectedInfoText.setTextColor(Color.rgb(80, 90, 110))
        selectedInfoText.setPadding(0, dp(6), 0, dp(8))
        selectionBox.addView(selectedInfoText)

        val selectionRow = LinearLayout(this)
        selectionRow.orientation = LinearLayout.HORIZONTAL

        val selectShown = Button(this)
        selectShown.text = "Selecionar exibidos"
        selectShown.setOnClickListener {
            files.forEach { selectedFiles.add(it.path) }
            showCategory(categoryTitle, categoryDesc)
        }

        val clearSelection = Button(this)
        clearSelection.text = "Limpar"
        clearSelection.setOnClickListener {
            selectedFiles.clear()
            showCategory(categoryTitle, categoryDesc)
        }

        selectionRow.addView(selectShown, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        selectionRow.addView(clearSelection, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        selectionBox.addView(selectionRow)

        val shareSelected = Button(this)
        shareSelected.text = "Compartilhar selecionados"
        shareSelected.setOnClickListener {
            shareSelectedFiles()
        }
        selectionBox.addView(shareSelected)

        val deleteSelected = Button(this)
        deleteSelected.text = "Excluir selecionados"
        deleteSelected.setOnClickListener {
            confirmDeleteSelectedFiles()
        }
        selectionBox.addView(deleteSelected)

        root.addView(selectionBox)
        updateSelectedInfo()

        val summary = TextView(this)
        summary.text = "${files.size} arquivos mostrados • ${formatSize(files.sumOf { it.size })}"
        summary.textSize = 14f
        summary.setTextColor(Color.rgb(80, 90, 110))
        summary.setPadding(0, dp(4), 0, dp(12))
        root.addView(summary)

        if (files.isEmpty()) {
            val empty = card()
            empty.addView(titleText("Nenhum arquivo encontrado"))
            val body = TextView(this)
            body.text = "Tente outro filtro ou toque em Atualizar na tela principal."
            body.textSize = 15f
            body.setTextColor(Color.rgb(70, 80, 100))
            body.setPadding(0, dp(8), 0, 0)
            empty.addView(body)
            root.addView(empty)
            return
        }

        for (file in files) {
            root.addView(fileRow(file))
        }
    }

    private fun filterButton(label: String, mb: Int): Button {
        return Button(this).apply {
            text = if (minSizeMb == mb) "✓ $label" else label
            setOnClickListener {
                minSizeMb = mb
                if (currentCategory.isNotBlank()) showCategory(currentCategory, "")
                else drawHome()
            }
        }
    }

    private fun fileRow(item: FileItem): LinearLayout {
        val box = card()

        val name = TextView(this)
        name.text = if (item.risk == "alto") "⚠ ${item.name}" else item.name
        name.textSize = 16f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(Color.rgb(14, 26, 56))
        box.addView(name)

        val detail = TextView(this)
        detail.text = "${item.type} • ${formatSize(item.size)} • ${formatDate(item.modified)}"
        detail.textSize = 13f
        detail.setTextColor(Color.rgb(80, 90, 110))
        detail.setPadding(0, dp(4), 0, dp(4))
        box.addView(detail)

        val path = TextView(this)
        path.text = item.path
        path.textSize = 12f
        path.setTextColor(Color.rgb(110, 120, 140))
        box.addView(path)

        val check = CheckBox(this)
        check.text = "Selecionar"
        check.isChecked = selectedFiles.contains(item.path)
        check.setOnCheckedChangeListener { _, checked ->
            if (checked) selectedFiles.add(item.path) else selectedFiles.remove(item.path)
            updateSelectedInfo()
        }
        box.addView(check)

        box.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(item.name)
                .setMessage(
                    "Tipo: ${item.type}\n" +
                    "Tamanho: ${formatSize(item.size)}\n" +
                    "Modificado: ${formatDate(item.modified)}\n" +
                    "Risco: ${if (item.risk == "alto") "alto" else "normal"}\n\n" +
                    "Local:\n${item.path}"
                )
                .setPositiveButton("Abrir") { _, _ ->
                    openFile(item)
                }
                .setNegativeButton("Fechar", null)
                .show()
        }

        return box
    }



    private fun updateSelectedInfo() {
        // Atualização simples e segura para evitar quebra de compilação.
        // A interface principal continua funcionando mesmo sem contador visual dedicado.
    }

    private fun shareSelectedFiles() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Nenhum arquivo selecionado", Toast.LENGTH_SHORT).show()
            return
        }

        val items = allFiles.filter { selectedFiles.contains(it.path) }
        val existingFiles = items.map { File(it.path) }.filter { it.exists() && it.isFile }

        if (existingFiles.isEmpty()) {
            Toast.makeText(this, "Nenhum arquivo válido para compartilhar", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (existingFiles.size == 1) {
                val file = existingFiles.first()
                val uri = fileUriFor(file)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType(file.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, file.name, uri)
                }

                startActivity(Intent.createChooser(intent, "Compartilhar arquivo"))
            } else {
                val uris = ArrayList<Uri>()
                existingFiles.forEach { uris.add(fileUriFor(it)) }

                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    if (uris.isNotEmpty()) {
                        val first = ClipData.newUri(contentResolver, "arquivo", uris.first())
                        for (i in 1 until uris.size) {
                            first.addItem(ClipData.Item(uris[i]))
                        }
                        clipData = first
                    }
                }

                startActivity(Intent.createChooser(intent, "Compartilhar arquivos"))
            }
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Não foi possível compartilhar")
                .setMessage("O Android bloqueou o compartilhamento ou não encontrou um app compatível.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun confirmDeleteSelectedFiles() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Nenhum arquivo selecionado", Toast.LENGTH_SHORT).show()
            return
        }

        val items = allFiles.filter { selectedFiles.contains(it.path) }

        if (items.isEmpty()) {
            selectedFiles.clear()
            updateSelectedInfo()
            Toast.makeText(this, "A seleção não foi encontrada na lista atual", Toast.LENGTH_SHORT).show()
            return
        }

        val totalSize = items.sumOf { it.size }
        val sensitive = items.filter { it.risk == "alto" }

        val message = buildString {
            append("Arquivos selecionados: ${items.size}\n")
            append("Espaço estimado: ${formatSize(totalSize)}\n\n")
            append("Essa ação tentará apagar os arquivos selecionados do aparelho.\n")
            append("Depois de apagados, eles podem não ser recuperáveis.\n\n")

            if (sensitive.isNotEmpty()) {
                append("ATENÇÃO: ${sensitive.size} arquivo(s) sensível(eis) selecionado(s).\n")
                append("Podem conter conversas, backups, documentos, bancos de dados, senhas ou dados importantes.\n\n")
                append("Exemplos:\n")
                sensitive.take(5).forEach { append("• ${it.name}\n") }
                if (sensitive.size > 5) append("• ...\n")
                append("\n")
            }

            append("Deseja continuar?")
        }

        AlertDialog.Builder(this)
            .setTitle(if (sensitive.isNotEmpty()) "Confirmar exclusão sensível" else "Confirmar exclusão")
            .setMessage(message)
            .setPositiveButton("Excluir") { _, _ ->
                if (sensitive.isNotEmpty()) {
                    confirmSensitiveDelete(items, sensitive.size)
                } else {
                    deleteFilesNow(items)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmSensitiveDelete(items: List<FileItem>, sensitiveCount: Int) {
        AlertDialog.Builder(this)
            .setTitle("Último aviso")
            .setMessage(
                "Você selecionou $sensitiveCount arquivo(s) sensível(eis).\n\n" +
                "Não apague backups do WhatsApp, bancos de dados, documentos ou arquivos de chave se não tiver certeza.\n\n" +
                "Quer apagar mesmo assim?"
            )
            .setPositiveButton("Sim, apagar") { _, _ ->
                deleteFilesNow(items)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteFilesNow(items: List<FileItem>) {
        thread {
            var deleted = 0
            var failed = 0
            val failedNames = mutableListOf<String>()
            val deletedPaths = mutableSetOf<String>()

            for (item in items) {
                try {
                    val file = File(item.path)
                    if (file.exists() && file.isFile && file.delete()) {
                        deleted++
                        deletedPaths.add(item.path)
                    } else {
                        failed++
                        failedNames.add(item.name)
                    }
                } catch (_: Exception) {
                    failed++
                    failedNames.add(item.name)
                }
            }

            allFiles = allFiles.filterNot { deletedPaths.contains(it.path) }
            selectedFiles.removeAll(items.map { it.path }.toSet())

            runOnUiThread {
                val resultMessage = buildString {
                    append("Apagados: $deleted\n")
                    append("Falharam: $failed")

                    if (failedNames.isNotEmpty()) {
                        append("\n\nO Android pode bloquear arquivos protegidos, de outro app ou sem permissão.\n\n")
                        failedNames.take(8).forEach { append("• $it\n") }
                        if (failedNames.size > 8) append("• ...")
                    }
                }

                AlertDialog.Builder(this)
                    .setTitle("Resultado da exclusão")
                    .setMessage(resultMessage)
                    .setPositiveButton("OK") { _, _ ->
                        updateSelectedInfo()
                        if (currentCategory.isNotBlank()) {
                            showCategory(currentCategory, "")
                        } else {
                            drawHome()
                        }
                        scanFiles()
                    }
                    .show()
            }
        }
    }


    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 701)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ),
                    702
                )
            }

            AlertDialog.Builder(this)
                .setTitle("Permitir acesso aos arquivos")
                .setMessage(
                    "Na tela que abrir, ative a permissão para permitir que o app veja os arquivos do aparelho.\\n\\n" +
                    "Depois volte para o app e toque em Atualizar."
                )
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized && hasStorageAccess()) {
            scanFiles()
        }
    }



    private fun openFile(item: FileItem) {
        try {
            val file = File(item.path)
            if (!file.exists()) {
                Toast.makeText(this, "Arquivo não encontrado", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                StrictMode::class.java
                    .getMethod("disableDeathOnFileUriExposure")
                    .invoke(null)
            } catch (_: Exception) {}

            val uri = fileUriFor(file)
            val mime = mimeType(file.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
            }

            startActivity(intent)
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Não foi possível abrir")
                .setMessage(
                    "O Android não encontrou um aplicativo padrão para abrir este arquivo.\n\n" +
                    "Escolha um app e marque Sempre quando o Android perguntar.\n\n" +
                    "Arquivo:\n${item.path}"
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun fileUriFor(file: File): Uri {
        return try {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DATA}=?"
            val args = arrayOf(file.absolutePath)

            contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return ContentUris.withAppendedId(collection, id)
                }
            }

            Uri.fromFile(file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    private fun mimeType(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")

        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/*"
            lower.endsWith(".mov") -> "video/*"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/*"
            lower.endsWith(".opus") -> "audio/*"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".doc") || lower.endsWith(".docx") -> "application/msword"
            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "application/vnd.ms-excel"
            lower.endsWith(".zip") -> "application/zip"
            ext.isNotBlank() -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            else -> "*/*"
        }
    }


    private fun scanFiles() {
        if (!hasStorageAccess()) {
            allFiles = emptyList()
            runOnUiThread {
                if (::statusText.isInitialized) {
                    statusText.text = "Acesso aos arquivos ainda não liberado"
                }
            }
            return
        }

        thread {
            val result = mutableListOf<FileItem>()
            val started = System.currentTimeMillis()
            val maxFiles = 8000

            fun scan(dir: File, depth: Int) {
                if (result.size >= maxFiles) return
                if (depth > 10) return
                if (System.currentTimeMillis() - started > 30000) return

                val list = try { dir.listFiles() } catch (_: Exception) { null } ?: return

                for (f in list) {
                    if (result.size >= maxFiles) return
                    if (System.currentTimeMillis() - started > 30000) return

                    try {
                        if (f.isDirectory) {
                            if (!f.name.startsWith(".")) scan(f, depth + 1)
                        } else {
                            result.add(
                                FileItem(
                                    name = f.name,
                                    path = f.absolutePath,
                                    size = f.length(),
                                    modified = f.lastModified(),
                                    type = fileType(f),
                                    category = fileCategory(f),
                                    risk = fileRisk(f)
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            for (rootDir in importantRoots()) {
                if (rootDir.exists()) scan(rootDir, 0)
            }

            allFiles = result.distinctBy { it.path }

            runOnUiThread {
                if (currentCategory.isBlank()) drawHome()
                else showCategory(currentCategory, "")
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

    private fun categoryFiles(category: String): List<FileItem> {
        val minBytes = minSizeMb.toLong() * 1024L * 1024L

        return when (category) {
            "Arquivos grandes" -> allFiles.filter { it.size >= minBytes }
            "Vídeos" -> allFiles.filter { it.category == "Vídeos" && it.size >= minBytes }
            "Imagens" -> allFiles.filter { it.category == "Imagens" && it.size >= minBytes }
            "Áudios" -> allFiles.filter { it.category == "Áudios" }
            "WhatsApp" -> allFiles.filter { it.path.lowercase(Locale.ROOT).contains("whatsapp") }
            "Backups" -> allFiles.filter { it.category == "Backups" || it.category == "Backups do WhatsApp" }
            "Recentes" -> allFiles.sortedByDescending { it.modified }.take(200)
            "Sensíveis" -> allFiles.filter { it.risk == "alto" }
            "Downloads" -> allFiles.filter { it.path.lowercase(Locale.ROOT).contains("/download") }
            "APKs" -> allFiles.filter { it.category == "APKs" }
            else -> allFiles
        }
    }

    private fun ordered(files: List<FileItem>): List<FileItem> {
        return if (orderMode == "date") files.sortedByDescending { it.modified }
        else files.sortedByDescending { it.size }
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

    private fun fileRisk(file: File): String {
        val n = file.name.lowercase(Locale.ROOT)
        val p = file.absolutePath.lowercase(Locale.ROOT)

        val high = listOf("msgstore", "wa.db", "database", "databases", "backup", "keystore", "certificate", "certificado", "token", "auth", "senha", "password", "cpf", "rg", "cnh", "contrato", "extrato")
        val ext = listOf(".db", ".sqlite", ".crypt", ".crypt12", ".crypt14", ".bak", ".backup", ".key", ".pem", ".p12", ".pfx")

        if (high.any { p.contains(it) }) return "alto"
        if (ext.any { n.endsWith(it) }) return "alto"
        return "normal"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "tamanho não identificado"
        val kb = bytes.toDouble() / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale("pt", "BR"), "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale("pt", "BR"), "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    private fun formatDate(ms: Long): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))
    }

    private fun bottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(8), dp(6), dp(8), dp(28))
        nav.setBackgroundColor(Color.WHITE)

        nav.addView(navItem("📊\nPainel", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        nav.addView(navItem("▦\nWidget", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        nav.addView(navItem("🧹\nLimpeza", true) {
            drawHome()
        })

        nav.addView(navItem("⚙\nConfig.", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        return nav
    }

    private fun navItem(textValue: String, active: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.rgb(20, 92, 210) else Color.rgb(80, 90, 110))
            setPadding(dp(6), dp(5), dp(6), dp(5))
            if (active) background = rounded(Color.rgb(232, 241, 255), Color.TRANSPARENT, dp(14))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, Color.rgb(225, 230, 240), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(14))
            }
        }
    }

    private fun titleText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(14, 26, 56))
        }
    }

    private fun rounded(bg: Int, stroke: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bg)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val type: String,
    val category: String,
    val risk: String
)

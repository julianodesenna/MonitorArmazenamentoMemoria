package br.com.monitorarmazenamentomemoria
import androidx.core.content.FileProvider
import android.content.ActivityNotFoundException

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
import android.media.ThumbnailUtils
import android.widget.ImageView
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.os.StrictMode
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
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
    private val previewCache = mutableMapOf<String, Bitmap>()
    private val previewExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val previewMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var categoryDisplayLimit = 120
    private var yearFilter = "Todos"
    private var autoLoadMoreLocked = false
    private lateinit var selectedInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.navigationBarColor = Color.WHITE
        window.statusBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        }
        window.navigationBarColor = Color.WHITE
        buildScreen()
        scanFiles()
        drawHome()
    }

    override fun onBackPressed() {
        if (currentCategory.isNotBlank()) {
            currentCategory = ""
            selectedFiles.clear()
            drawHome()
        } else {
            finish()
        }
    }

    private fun buildScreen() {
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(Color.rgb(248, 250, 253))

        val contentScroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(18), dp(18), dp(96))
        contentScroll.addView(root)

        main.addView(contentScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val bottom = bottomNav()
        main.addView(bottom)

        val safeBottomSpaceForSamsung = View(this)
        safeBottomSpaceForSamsung.setBackgroundColor(Color.WHITE)
        main.addView(safeBottomSpaceForSamsung, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(64)
        ))

        setContentView(main)
    }

    private fun drawHome() {
        removeFloatingListNav()
        currentCategory = ""
        categoryDisplayLimit = 120
        yearFilter = "Todos"
        categoryDisplayLimit = 120
        yearFilter = "Todos"
        root.removeAllViews()

        val data = Monitor.read(this)

        val title = TextView(this)
        title.text = "Limpeza"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(10, 18, 36))
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

        addCategoryRow("🆕", "Novos nas últimas 24h", "Arquivos recebidos, baixados ou criados hoje", "◷", "Últimos modificados", "Arquivos alterados mais recentemente")
        addCategoryRow("▣", "Arquivos grandes", "Arquivos acima do filtro", "▶", "Vídeos", "Filtrar vídeos")
        addCategoryRow("▧", "Imagens", "Fotos e imagens", "♫", "Áudios", "Áudios e mensagens de voz")
        addCategoryRow("◌", "WhatsApp", "Mídias e backups", "▤", "Backups", "Bancos de dados e cópias")
        addCategoryRow("🖼", "Fotos do WhatsApp", "Imagens recebidas e enviadas", "🎬", "Vídeos do WhatsApp", "Vídeos recebidos e enviados")
        addCategoryRow("📄", "Documentos do WhatsApp", "PDFs, planilhas e arquivos", "🎵", "Áudios do WhatsApp", "Áudios e mensagens de voz")
        addCategoryRow("🗄", "Backups do WhatsApp", "Bancos de dados e backups", "📁", "Todos do WhatsApp", "Tudo que estiver no WhatsApp")
        addCategoryRow("⚠", "Sensíveis", "Arquivos que exigem cuidado", "↓", "Downloads", "Arquivos baixados")
        addCategorySingleRow("◇", "APKs", "Instaladores antigos")
    }

    private fun addCategoryRow(icon1: String, title1: String, desc1: String, icon2: String, title2: String, desc2: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(categoryCard(icon1, title1, desc1), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(categoryCard(icon2, title2, desc2), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
    }

    private fun addCategorySingleRow(icon: String, title: String, desc: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        row.addView(
            categoryCard(icon, title, desc),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        root.addView(row)
    }

    private fun categoryCard(icon: String, title: String, desc: String): LinearLayout {
        val files = categoryFiles(title)
        val total = files.sumOf { it.size }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(12), dp(12), dp(12))
        box.background = rounded(Color.WHITE, Color.rgb(218, 225, 240), dp(20))

        val t = TextView(this)
        t.text = "$icon\n$title"
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.rgb(14, 26, 56))
        box.addView(t)

        val d = TextView(this)
        d.text = "$desc\n${files.size} arquivos • ${formatSize(total)}"
        d.textSize = 11f
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
        removeFloatingListNav()
        if (currentCategory != categoryTitle) {
            categoryDisplayLimit = 120
            yearFilter = "Todos"
        }
        autoLoadMoreLocked = false
        currentCategory = categoryTitle
        root.removeAllViews()

        val back = Button(this)
        back.text = "← Voltar para categorias"
        back.setOnClickListener {
            categoryDisplayLimit = 120
            drawHome()
        }
        root.addView(back)

        val title = TextView(this)
        title.text = categoryTitle
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(10, 18, 36))
        title.setPadding(0, dp(12), 0, dp(4))
        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = categoryDesc
        subtitle.textSize = 15f
        subtitle.setTextColor(Color.rgb(80, 90, 110))
        subtitle.setPadding(0, 0, 0, dp(14))
        root.addView(subtitle)

        val rawCategoryItems = categoryFiles(categoryTitle)

        fun chipButton(label: String, active: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                val shownLabel = if (label == "Antes de 2017") "Antes 2017" else label
                text = if (active) "✓ $shownLabel" else shownLabel
                textSize = 11f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(9), 0, dp(9), 0)
                setTextColor(if (active) Color.WHITE else Color.rgb(35, 45, 65))
                background = rounded(
                    if (active) Color.rgb(42, 92, 255) else Color.rgb(248, 250, 253),
                    if (active) Color.rgb(42, 92, 255) else Color.rgb(218, 225, 236),
                    dp(14)
                )
                elevation = if (active) dp(2).toFloat() else dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        fun addChip(row: LinearLayout, button: TextView) {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(30)
            )
            params.setMargins(0, 0, dp(5), dp(5))
            row.addView(button, params)
        }

        fun addWeightedChip(row: LinearLayout, button: TextView) {
            val params = LinearLayout.LayoutParams(
                0,
                dp(30),
                1f
            )
            params.setMargins(0, 0, dp(5), dp(5))
            row.addView(button, params)
        }

        val filter = card()
        filter.setPadding(dp(14), dp(14), dp(14), dp(10))

        val filterTitleRow = LinearLayout(this)
        filterTitleRow.orientation = LinearLayout.HORIZONTAL
        filterTitleRow.gravity = Gravity.CENTER_VERTICAL

        val filterTitle = TextView(this)
        filterTitle.text = "Filtros"
        filterTitle.textSize = 18f
        filterTitle.setTypeface(null, Typeface.BOLD)
        filterTitle.setTextColor(Color.rgb(14, 26, 56))
        filterTitleRow.addView(
            filterTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val activeYear = if (yearFilter == "Todos") "" else " • $yearFilter"
        val filterResume = TextView(this)
        filterResume.text = "${if (orderMode == "size") "Maiores" else if (orderMode == "date") "Recentes" else "Antigos"}$activeYear"
        filterResume.textSize = 11f
        filterResume.setTextColor(Color.rgb(80, 90, 110))
        filterTitleRow.addView(filterResume)

        filter.addView(filterTitleRow)

        val sizeRow = LinearLayout(this)
        sizeRow.orientation = LinearLayout.HORIZONTAL
        sizeRow.setPadding(0, dp(10), 0, 0)

        addWeightedChip(sizeRow, chipButton("10 MB", minSizeMb == 10) {
            minSizeMb = 10
            categoryDisplayLimit = 120
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(sizeRow, chipButton("50 MB", minSizeMb == 50) {
            minSizeMb = 50
            categoryDisplayLimit = 120
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(sizeRow, chipButton("100 MB", minSizeMb == 100) {
            minSizeMb = 100
            categoryDisplayLimit = 120
            showCategory(categoryTitle, categoryDesc)
        })

        filter.addView(sizeRow)

        val orderRow = LinearLayout(this)
        orderRow.orientation = LinearLayout.HORIZONTAL

        addWeightedChip(orderRow, chipButton("Maiores", orderMode == "size") {
            orderMode = "size"
            categoryDisplayLimit = 120
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(orderRow, chipButton("Recentes", orderMode == "date") {
            orderMode = "date"
            categoryDisplayLimit = 120
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(orderRow, chipButton("Antigos", orderMode == "old") {
            orderMode = "old"
            categoryDisplayLimit = 120
            showCategory(categoryTitle, categoryDesc)
        })

        filter.addView(orderRow)

        val yearTitle = TextView(this)
        yearTitle.text = "Ano"
        yearTitle.textSize = 11f
        yearTitle.setTypeface(null, Typeface.BOLD)
        yearTitle.setTextColor(Color.rgb(80, 90, 110))
        yearTitle.setPadding(0, dp(4), 0, dp(6))
        filter.addView(yearTitle)

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val yearOptions = mutableListOf("Todos")
        yearOptions.addAll((currentYear downTo 2017).map { it.toString() })
        yearOptions.add("Antes de 2017")

        val selectedYearText = if (yearFilter == "Antes de 2017") "Antes 2017" else yearFilter

        val yearButton = TextView(this)
        yearButton.text = "Ano: $selectedYearText  ▾"
        yearButton.textSize = 12f
        yearButton.gravity = Gravity.CENTER_VERTICAL
        yearButton.includeFontPadding = false
        yearButton.setTypeface(null, Typeface.BOLD)
        yearButton.setPadding(dp(14), 0, dp(14), 0)
        yearButton.setTextColor(Color.rgb(35, 45, 65))
        yearButton.background = rounded(
            Color.rgb(248, 250, 253),
            Color.rgb(218, 225, 236),
            dp(16)
        )
        yearButton.elevation = dp(1).toFloat()
        yearButton.isClickable = true
        yearButton.isFocusable = true
        yearButton.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this@CleanupSimpleActivity, view)

            yearOptions.forEachIndexed { index, option ->
                popup.menu.add(0, index, index, option)
            }

            popup.setOnMenuItemClickListener { item ->
                val selected = yearOptions.getOrNull(item.itemId) ?: "Todos"
                yearFilter = selected
                categoryDisplayLimit = 120
                autoLoadMoreLocked = false
                showCategory(categoryTitle, categoryDesc)
                true
            }

            popup.show()
        }

        val yearParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        )
        yearParams.setMargins(0, 0, 0, dp(4))
        filter.addView(yearButton, yearParams)

        root.addView(filter)

        val allCategoryItems = ordered(filterByYear(rawCategoryItems))
        val files = allCategoryItems.take(categoryDisplayLimit)

        fun scrollToCategoryTop() {
            val scrollView = root.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.smoothScrollTo(0, 0)
            }
        }

        fun scrollToCategoryBottom() {
            val scrollView = root.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.smoothScrollTo(0, root.height)
            }
        }

        fun listShortcutButton(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(Color.rgb(35, 45, 65))
                background = rounded(
                    Color.rgb(248, 250, 253),
                    Color.rgb(218, 225, 236),
                    dp(16)
                )
                elevation = dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        fun addListShortcutRow(label: String) {
            val box = card()
            box.setPadding(dp(12), dp(10), dp(12), dp(10))

            val title = TextView(this)
            title.text = label
            title.textSize = 12f
            title.setTypeface(null, Typeface.BOLD)
            title.setTextColor(Color.rgb(80, 90, 110))
            title.setPadding(0, 0, 0, dp(8))
            box.addView(title)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val topButton = listShortcutButton("↑ Topo") {
                scrollToCategoryTop()
            }

            val bottomButton = listShortcutButton("↓ Final") {
                scrollToCategoryBottom()
            }

            val topParams = LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
            )
            topParams.setMargins(0, 0, dp(6), 0)

            val bottomParams = LinearLayout.LayoutParams(
                0,
                dp(34),
                1f
            )
            bottomParams.setMargins(dp(6), 0, 0, 0)

            row.addView(topButton, topParams)
            row.addView(bottomButton, bottomParams)
            box.addView(row)

            val boxParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            boxParams.setMargins(0, dp(8), 0, dp(10))
            root.addView(box, boxParams)
        }

        val selectionBox = card()
        selectionBox.setPadding(dp(14), dp(12), dp(14), dp(10))

        val selectionHeader = LinearLayout(this)
        selectionHeader.orientation = LinearLayout.HORIZONTAL
        selectionHeader.gravity = Gravity.CENTER_VERTICAL

        val selectionTitle = TextView(this)
        selectionTitle.text = "Seleção"
        selectionTitle.textSize = 16f
        selectionTitle.setTypeface(null, Typeface.BOLD)
        selectionTitle.setTextColor(Color.rgb(14, 26, 56))
        selectionHeader.addView(
            selectionTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        selectedInfoText = TextView(this)
        selectedInfoText.textSize = 12f
        selectedInfoText.setTypeface(null, Typeface.BOLD)
        selectedInfoText.setTextColor(Color.rgb(80, 90, 110))
        selectedInfoText.gravity = Gravity.END
        selectionHeader.addView(selectedInfoText)

        selectionBox.addView(selectionHeader)

        val selectRow = LinearLayout(this)
        selectRow.orientation = LinearLayout.HORIZONTAL
        selectRow.setPadding(0, dp(10), 0, 0)

        addWeightedChip(selectRow, chipButton("Exibidos", false) {
            files.forEach { selectedFiles.add(it.path) }
            Toast.makeText(this, "${files.size} arquivos exibidos selecionados", Toast.LENGTH_SHORT).show()
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(selectRow, chipButton("Todos", false) {
            allCategoryItems.forEach { selectedFiles.add(it.path) }
            Toast.makeText(this, "${allCategoryItems.size} arquivos da categoria selecionados", Toast.LENGTH_SHORT).show()
            showCategory(categoryTitle, categoryDesc)
        })

        selectionBox.addView(selectRow)

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.setPadding(0, dp(4), 0, 0)

        addWeightedChip(actionRow, chipButton("Compartilhar", false) {
            shareSelectedFiles()
        })

        val deleteButton = chipButton("Excluir", false) {
            confirmDeleteSelectedFiles()
        }
        deleteButton.setTextColor(Color.rgb(150, 38, 38))
        deleteButton.background = rounded(
            Color.rgb(255, 245, 245),
            Color.rgb(245, 190, 190),
            dp(18)
        )
        addWeightedChip(actionRow, deleteButton)

        val clearButton = chipButton("Limpar", false) {
            selectedFiles.clear()
            showCategory(categoryTitle, categoryDesc)
        }
        clearButton.tag = "cleanup_clear_selection_button"
        clearButton.setTextColor(Color.rgb(80, 90, 110))
        clearButton.background = rounded(
            Color.rgb(248, 250, 253),
            Color.rgb(218, 225, 236),
            dp(18)
        )
        clearButton.visibility = if (selectedFiles.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        addWeightedChip(actionRow, clearButton)

        selectionBox.addView(actionRow)

        root.addView(selectionBox)
        updateSelectedInfo()

        val summary = TextView(this)
        val yearInfo = if (yearFilter == "Todos") "" else " • ano: $yearFilter"
        summary.text = "${files.size} de ${allCategoryItems.size} arquivos mostrados$yearInfo • exibidos: ${formatSize(files.sumOf { it.size })} • total: ${formatSize(allCategoryItems.sumOf { it.size })}"
        summary.textSize = 14f
        summary.setTextColor(Color.rgb(80, 90, 110))
        summary.setPadding(0, dp(4), 0, dp(12))
        root.addView(summary)

        val inlineNavBox = card()
        inlineNavBox.setPadding(dp(12), dp(10), dp(12), dp(10))

        val inlineNavTitle = TextView(this)
        inlineNavTitle.text = "Navegação da lista"
        inlineNavTitle.textSize = 12f
        inlineNavTitle.setTypeface(null, Typeface.BOLD)
        inlineNavTitle.setTextColor(Color.rgb(80, 90, 110))
        inlineNavTitle.setPadding(0, 0, 0, dp(8))
        inlineNavBox.addView(inlineNavTitle)

        val inlineNavRow = LinearLayout(this)
        inlineNavRow.orientation = LinearLayout.HORIZONTAL

        fun inlineNavButton(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(Color.rgb(35, 45, 65))
                background = rounded(
                    Color.rgb(248, 250, 253),
                    Color.rgb(205, 214, 230),
                    dp(18)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        val inlineTopButton = inlineNavButton("↑ Topo") {
            val scrollView = root.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.smoothScrollTo(0, 0)
            }
        }

        val inlineBottomButton = inlineNavButton("↓ Final") {
            val scrollView = root.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.smoothScrollTo(0, root.height)
            }
        }

        val inlineTopParams = LinearLayout.LayoutParams(
            0,
            dp(38),
            1f
        )
        inlineTopParams.setMargins(0, 0, dp(6), 0)

        val inlineBottomParams = LinearLayout.LayoutParams(
            0,
            dp(38),
            1f
        )
        inlineBottomParams.setMargins(dp(6), 0, 0, 0)

        inlineNavRow.addView(inlineTopButton, inlineTopParams)
        inlineNavRow.addView(inlineBottomButton, inlineBottomParams)
        inlineNavBox.addView(inlineNavRow)

        root.addView(inlineNavBox)

        setupAutoLoadMore(allCategoryItems.size, files.size, categoryTitle, categoryDesc)

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

        showFloatingListNav()

        for (file in files) {
            root.addView(fileRow(file))
        }
    }

    private fun removeFloatingListNav() {
        val decor = window.decorView as? android.view.ViewGroup ?: return
        val old = decor.findViewWithTag<android.view.View>("cleanup_floating_list_nav")
        val parent = old?.parent as? android.view.ViewGroup
        parent?.removeView(old)
    }

    private fun showFloatingListNav() {
        removeFloatingListNav()
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

    private fun isPreviewImageFile(item: FileItem): Boolean {
        val name = item.name.lowercase(Locale.ROOT)
        val type = item.type.lowercase(Locale.ROOT)

        return type.contains("imagem") ||
            type.contains("foto") ||
            name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp")
    }

    private fun isPreviewVideoFile(item: FileItem): Boolean {
        val name = item.name.lowercase(Locale.ROOT)
        val type = item.type.lowercase(Locale.ROOT)

        return type.contains("vídeo") ||
            type.contains("video") ||
            name.endsWith(".mp4") ||
            name.endsWith(".mov") ||
            name.endsWith(".mkv") ||
            name.endsWith(".avi") ||
            name.endsWith(".3gp")
    }

    private fun loadImagePreview(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(file.absolutePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            var sample = 1
            val target = dp(120)

            while ((bounds.outWidth / sample) > target || (bounds.outHeight / sample) > target) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
            }

            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            ThumbnailUtils.extractThumbnail(raw, dp(96), dp(96))
        } catch (_: Exception) {
            null
        }
    }

    private fun loadVideoPreview(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(file.absolutePath)

            val frame = retriever.getFrameAtTime(
                1_000_000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime

            if (frame != null) {
                ThumbnailUtils.extractThumbnail(frame, dp(96), dp(96))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadPreviewBitmap(item: FileItem): Bitmap? {
        val file = File(item.path)

        if (!file.exists() || !file.isFile) {
            return null
        }

        previewCache[item.path]?.let {
            return it
        }

        val bmp = when {
            isPreviewImageFile(item) -> loadImagePreview(file)
            isPreviewVideoFile(item) -> loadVideoPreview(file)
            else -> null
        }

        if (bmp != null) {
            if (previewCache.size > 80) {
                previewCache.clear()
            }

            previewCache[item.path] = bmp
        }

        return bmp
    }

    private fun fileFallbackIconView(item: FileItem): TextView {
        return TextView(this).apply {
            text = fileTypeIcon(item)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(42, 92, 255))
            background = rounded(
                Color.rgb(238, 243, 255),
                Color.rgb(205, 216, 245),
                dp(12)
            )
        }
    }

    private fun filePreviewView(item: FileItem): FrameLayout {
        val wrap = FrameLayout(this)
        wrap.background = rounded(
            Color.rgb(238, 243, 255),
            Color.rgb(205, 216, 245),
            dp(12)
        )

        fun showFallback() {
            wrap.removeAllViews()
            wrap.addView(
                fileFallbackIconView(item),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        fun showBitmap(bmp: Bitmap) {
            wrap.removeAllViews()

            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setImageBitmap(bmp)

            wrap.addView(
                img,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            if (isPreviewVideoFile(item)) {
                val play = TextView(this)
                play.text = "▶"
                play.textSize = 13f
                play.gravity = Gravity.CENTER
                play.setTextColor(Color.WHITE)
                play.background = rounded(
                    Color.argb(170, 0, 0, 0),
                    Color.argb(0, 0, 0, 0),
                    dp(16)
                )

                val playParams = FrameLayout.LayoutParams(dp(24), dp(24))
                playParams.gravity = Gravity.CENTER
                wrap.addView(play, playParams)
            }
        }

        showFallback()

        val cached = previewCache[item.path]
        if (cached != null) {
            showBitmap(cached)
            return wrap
        }

        if (isPreviewImageFile(item) || isPreviewVideoFile(item)) {
            val expectedPath = item.path

            previewExecutor.execute {
                val bmp = loadPreviewBitmap(item)

                previewMainHandler.post {
                    if (item.path == expectedPath && bmp != null) {
                        showBitmap(bmp)
                    }
                }
            }
        }

        return wrap
    }

    private fun fileTypeIcon(item: FileItem): String {
        val name = item.name.lowercase(Locale.ROOT)
        val type = item.type.lowercase(Locale.ROOT)
        val path = item.path.lowercase(Locale.ROOT)

        return when {
            type.contains("vídeo") || type.contains("video") || name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".mkv") || name.endsWith(".avi") -> "▶"
            type.contains("imagem") || type.contains("foto") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") -> "🖼"
            type.contains("pdf") || name.endsWith(".pdf") -> "PDF"
            type.contains("áudio") || type.contains("audio") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".ogg") || name.endsWith(".opus") || name.endsWith(".aac") -> "♪"
            type.contains("apk") || name.endsWith(".apk") -> "APK"
            type.contains("backup") || path.contains("backup") || name.contains("msgstore") || name.contains(".crypt") -> "▣"
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> "ZIP"
            else -> "📁"
        }
    }

    private fun compactDisplayPath(path: String): String {
        val parts = path.split("/").filter { it.isNotBlank() }

        if (parts.size <= 4) {
            return path
        }

        val fileName = parts.lastOrNull().orEmpty()
        val parent = parts.dropLast(1).lastOrNull().orEmpty()
        val grandParent = parts.dropLast(2).lastOrNull().orEmpty()

        return "…/$grandParent/$parent/$fileName"
    }

    private fun fileRow(item: FileItem): LinearLayout {
        val box = card()

        fun showFileDetails() {
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

        fun openThisFile() {
            Toast.makeText(this, "Abrindo arquivo...", Toast.LENGTH_SHORT).show()
            window.decorView.post {
                openFile(item)
            }
        }

        fun openActionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(if (primary) Color.WHITE else Color.rgb(35, 45, 65))
                background = rounded(
                    if (primary) Color.rgb(42, 92, 255) else Color.rgb(248, 250, 253),
                    if (primary) Color.rgb(42, 92, 255) else Color.rgb(205, 214, 230),
                    dp(18)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }


        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.setOnClickListener { openThisFile() }
        titleRow.setOnLongClickListener {
            showFileDetails()
            true
        }

        val name = TextView(this)
        name.text = if (item.risk == "alto") "⚠ ${item.name}" else item.name
        name.textSize = 16f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(Color.rgb(14, 26, 56))
        name.maxLines = 2
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.setOnClickListener { openThisFile() }
        name.setOnLongClickListener {
            showFileDetails()
            true
        }

        val fileIcon = filePreviewView(item)
        fileIcon.setOnClickListener { openThisFile() }

        val iconParams = LinearLayout.LayoutParams(dp(48), dp(48))
        iconParams.setMargins(0, 0, dp(10), 0)
        titleRow.addView(fileIcon, iconParams)

        val nameParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        titleRow.addView(name, nameParams)

        val selectTouchArea = FrameLayout(this)
        selectTouchArea.setPadding(dp(4), dp(4), dp(4), dp(4))
        selectTouchArea.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))

        val selectBadge = TextView(this)
        selectBadge.gravity = Gravity.CENTER
        selectBadge.textSize = 18f
        selectBadge.setTypeface(null, Typeface.BOLD)

        fun refreshSelectBadge() {
            val selected = selectedFiles.contains(item.path)
            selectBadge.text = if (selected) "✓" else ""
            selectBadge.setTextColor(if (selected) Color.WHITE else Color.rgb(80, 90, 110))
            selectBadge.background = rounded(
                if (selected) Color.rgb(42, 92, 255) else Color.WHITE,
                if (selected) Color.rgb(42, 92, 255) else Color.rgb(135, 148, 170),
                dp(8)
            )
        }

        refreshSelectBadge()

        val badgeParams = FrameLayout.LayoutParams(dp(28), dp(28))
        badgeParams.gravity = Gravity.CENTER
        selectTouchArea.addView(selectBadge, badgeParams)

        selectTouchArea.setOnClickListener {
            val selected = selectedFiles.contains(item.path)
            if (selected) {
                selectedFiles.remove(item.path)
            } else {
                selectedFiles.add(item.path)
            }
            refreshSelectBadge()
            updateSelectedInfo()
        }

        titleRow.addView(selectTouchArea)
        box.addView(titleRow)

        val detail = TextView(this)
        detail.text = "${item.type} • ${formatSize(item.size)} • ${formatDate(item.modified)}"
        detail.textSize = 11f
        detail.setTextColor(Color.rgb(80, 90, 110))
        detail.setPadding(0, dp(6), 0, dp(4))
        detail.setOnClickListener { openThisFile() }
        detail.setOnLongClickListener {
            showFileDetails()
            true
        }
        box.addView(detail)

        val pathView = TextView(this)
        pathView.text = "Local: ${compactDisplayPath(item.path)}"
        pathView.textSize = 11f
        pathView.setTextColor(Color.rgb(110, 120, 140))
        pathView.maxLines = 3
        pathView.setPadding(0, 0, 0, dp(6))
        pathView.setOnClickListener { openThisFile() }
        pathView.setOnLongClickListener {
            showFileDetails()
            true
        }
        box.addView(pathView)

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.setPadding(0, dp(10), 0, 0)

        val openButton = openActionButton("Abrir arquivo", true) {
            openThisFile()
        }

        val detailsButton = openActionButton("Detalhes", false) {
            showFileDetails()
        }

        val openParams = LinearLayout.LayoutParams(
            0,
            dp(38),
            1f
        )
        openParams.setMargins(0, 0, dp(6), 0)

        val detailsParams = LinearLayout.LayoutParams(
            0,
            dp(38),
            1f
        )
        detailsParams.setMargins(dp(6), 0, 0, 0)

        actionRow.addView(openButton, openParams)
        actionRow.addView(detailsButton, detailsParams)
        box.addView(actionRow)

        box.setOnClickListener { openThisFile() }
        box.setOnLongClickListener {
            showFileDetails()
            true
        }

        return box
    }



    private fun updateSelectedInfo() {
        if (::selectedInfoText.isInitialized) {
            val selected = allFiles.filter { selectedFiles.contains(it.path) }
            selectedInfoText.text = "${selected.size} selecionados • ${formatSize(selected.sumOf { it.size })}"
        }
        val clearButton = root.findViewWithTag<android.view.View>("cleanup_clear_selection_button")
        clearButton?.visibility = if (selectedFiles.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE

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




    private fun looksLikePdfContent(file: File): Boolean {
        return try {
            if (!file.exists() || !file.isFile || file.length() < 5) return false
            file.inputStream().use { input ->
                val buffer = ByteArray(5)
                val read = input.read(buffer)
                read == 5 && String(buffer, Charsets.US_ASCII) == "%PDF-"
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun detectOpenMime(file: File): String {
        val n = file.name.lowercase(Locale.ROOT)

        if (n.endsWith(".pdf") || looksLikePdfContent(file)) return "application/pdf"

        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".gif") -> "image/gif"

            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".3gp") -> "video/3gpp"
            n.endsWith(".mkv") -> "video/*"
            n.endsWith(".mov") -> "video/*"
            n.endsWith(".avi") -> "video/*"

            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".m4a") -> "audio/mp4"
            n.endsWith(".aac") -> "audio/aac"
            n.endsWith(".opus") -> "audio/opus"
            n.endsWith(".ogg") -> "audio/ogg"
            n.endsWith(".wav") -> "audio/wav"

            n.endsWith(".doc") || n.endsWith(".docx") -> "application/msword"
            n.endsWith(".xls") || n.endsWith(".xlsx") -> "application/vnd.ms-excel"
            n.endsWith(".ppt") || n.endsWith(".pptx") -> "application/vnd.ms-powerpoint"
            n.endsWith(".txt") -> "text/plain"
            n.endsWith(".csv") -> "text/csv"
            n.endsWith(".zip") -> "application/zip"

            else -> "*/*"
        }
    }

    private fun prepareOpenFile(file: File, mimeType: String): File {
        val nameLower = file.name.lowercase(Locale.ROOT)

        if (mimeType == "application/pdf" && !nameLower.endsWith(".pdf")) {
            val dir = File(cacheDir, "open_pdf")
            if (!dir.exists()) dir.mkdirs()

            val safeName = file.name
                .ifBlank { "documento" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('.')
                .ifBlank { "documento" }

            val out = File(dir, "$safeName.pdf")

            if (!out.exists() || out.length() != file.length() || out.lastModified() < file.lastModified()) {
                file.copyTo(out, overwrite = true)
                out.setLastModified(file.lastModified())
            }

            return out
        }

        return file
    }


    private fun openFile(item: FileItem) {
        try {
            val originalFile = File(item.path)

            if (!originalFile.exists() || !originalFile.isFile) {
                Toast.makeText(this, "Arquivo não encontrado ou indisponível.", Toast.LENGTH_LONG).show()
                return
            }

            val mimeType = detectOpenMime(originalFile)
            val openFile = prepareOpenFile(originalFile, mimeType)

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                openFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newUri(contentResolver, openFile.name, uri)
            }

            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                val chooser = Intent.createChooser(intent, "Abrir arquivo")
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Não foi possível abrir este arquivo. Tente compartilhar ou abrir por outro app.",
                Toast.LENGTH_LONG
            ).show()
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
            val maxFiles = 30000

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
            File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Sent"),
            File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents"),
            File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents/Sent"),
            File(base, "WhatsApp/Media/WhatsApp Documents"),
            File(base, "WhatsApp/Media/WhatsApp Documents/Sent"),
            File(base, "Download"),
            File(base, "Downloads"),
            File(base, "Documents"),
            File(base, "DCIM"),
            File(base, "Movies"),
            File(base, "Pictures"),
            File(base, "WhatsApp"),
            File(base, "Android/media/com.whatsapp"),
            File(base, "Android/media/com.whatsapp.w4b"),
            File(base, "Android/media"),
            base
        ).distinctBy { it.absolutePath }
    }

    private fun normalizedPath(path: String): String {
        return path.lowercase(Locale.ROOT).replace("\\", "/")
    }

    private fun isWhatsAppPath(path: String): Boolean {
        return normalizedPath(path).contains("whatsapp")
    }

    private fun isImageName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif")
    }

    private fun isVideoName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".3gp")
    }

    private fun isAudioName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".opus") || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".ogg")
    }

    private fun isPdfName(name: String): Boolean {
        return name.lowercase(Locale.ROOT).endsWith(".pdf")
    }

    private fun isDocumentName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".pdf") ||
            n.endsWith(".doc") || n.endsWith(".docx") ||
            n.endsWith(".xls") || n.endsWith(".xlsx") ||
            n.endsWith(".ppt") || n.endsWith(".pptx") ||
            n.endsWith(".txt") || n.endsWith(".csv") ||
            n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z") ||
            n.endsWith(".xml") || n.endsWith(".json")
    }

    private fun isWhatsAppBackup(item: FileItem): Boolean {
        val p = normalizedPath(item.path)
        val n = item.name.lowercase(Locale.ROOT)
        return isWhatsAppPath(item.path) && (
            p.contains("/databases/") ||
            n.contains("msgstore") ||
            n.endsWith(".db") ||
            n.endsWith(".sqlite") ||
            n.contains(".crypt") ||
            n.endsWith(".backup") ||
            n.endsWith(".bak")
        )
    }

    private fun categoryFiles(category: String): List<FileItem> {
        val minBytes = minSizeMb.toLong() * 1024L * 1024L

        return when (category) {
            "Novos nas últimas 24h" -> allFiles.filter { isLast24Hours(it) }

            "Arquivos grandes" -> allFiles.filter { it.size >= minBytes }
            "Vídeos" -> allFiles.filter { it.category == "Vídeos" && it.size >= minBytes }
            "Imagens" -> allFiles.filter { it.category == "Imagens" && it.size >= minBytes }
            "Áudios" -> allFiles.filter { it.category == "Áudios" }
            "WhatsApp" -> allFiles.filter { it.path.lowercase(Locale.ROOT).contains("whatsapp") }
            "Backups" -> allFiles.filter { it.category == "Backups" || it.category == "Backups do WhatsApp" }
            "Últimos modificados" -> allFiles.sortedByDescending { it.modified }.take(200)
            "Sensíveis" -> allFiles.filter { it.risk == "alto" }
            "Downloads" -> allFiles.filter { it.path.lowercase(Locale.ROOT).contains("/download") }
            "APKs" -> allFiles.filter { it.category == "APKs" }
            "Fotos do WhatsApp" -> allFiles.filter { isWhatsAppPath(it.path) && isImageName(it.name) }
            "Vídeos do WhatsApp" -> allFiles.filter { isWhatsAppPath(it.path) && isVideoName(it.name) }
            "Documentos do WhatsApp" -> allFiles.filter {
                isWhatsAppPath(it.path) && (
                    isPdfName(it.name) ||
                    isDocumentName(it.name) ||
                    normalizedPath(it.path).contains("whatsapp documents") ||
                    normalizedPath(it.path).contains("whatsapp business documents")
                )
            }
            "Áudios do WhatsApp" -> allFiles.filter { isWhatsAppPath(it.path) && isAudioName(it.name) }
            "Backups do WhatsApp" -> allFiles.filter { isWhatsAppBackup(it) }
            "Todos do WhatsApp" -> allFiles.filter { isWhatsAppPath(it.path) }
            else -> allFiles
        }
    }

    private fun setupAutoLoadMore(totalItems: Int, shownItems: Int, categoryTitle: String, categoryDesc: String) {
        val activeScroll = root.parent as? ScrollView ?: return

        activeScroll.setOnScrollChangeListener(null)
        autoLoadMoreLocked = false

        if (totalItems <= 0) {
            return
        }

        if (shownItems >= totalItems) {
            return
        }

        activeScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (autoLoadMoreLocked) return@setOnScrollChangeListener
            if (currentCategory != categoryTitle) return@setOnScrollChangeListener
            if (categoryDisplayLimit >= totalItems) return@setOnScrollChangeListener

            val child = activeScroll.getChildAt(activeScroll.childCount - 1) ?: return@setOnScrollChangeListener
            val distanceToBottom = child.bottom - (activeScroll.height + activeScroll.scrollY)

            if (distanceToBottom <= dp(900)) {
                autoLoadMoreLocked = true
                categoryDisplayLimit = minOf(categoryDisplayLimit + 200, totalItems)

                activeScroll.postDelayed({
                    autoLoadMoreLocked = false
                    if (currentCategory == categoryTitle) {
                        showCategory(categoryTitle, categoryDesc)
                    }
                }, 120)
            }
        }
    }


    private fun isLast24Hours(item: FileItem): Boolean {
        val now = System.currentTimeMillis()
        val limit = now - 24L * 60L * 60L * 1000L
        return item.modified >= limit
    }

    private fun itemYear(item: FileItem): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = item.modified
        return cal.get(java.util.Calendar.YEAR)
    }

    private fun availableYears(files: List<FileItem>): List<Int> {
        return files.map { itemYear(it) }.distinct().sortedDescending()
    }

    private fun filterByYear(files: List<FileItem>): List<FileItem> {
        if (yearFilter == "Todos") return files

        if (yearFilter == "Antes de 2017") {
            return files.filter { itemYear(it) < 2017 }
        }

        val selectedYear = yearFilter.toIntOrNull() ?: return files
        return files.filter { itemYear(it) == selectedYear }
    }

    private fun ordered(files: List<FileItem>): List<FileItem> {
        return when (orderMode) {
            "date" -> files.sortedByDescending { it.modified }
            "old" -> files.sortedBy { it.modified }
            else -> files.sortedByDescending { it.size }
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
            p.contains("whatsapp") && n.endsWith(".pdf") -> "PDF do WhatsApp"
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

    private fun drawWidget() {
        removeFloatingListNav()
        currentCategory = ""
        categoryDisplayLimit = 120
        root.removeAllViews()

        val title = TextView(this)
        title.text = "Widget"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(10, 18, 36))
        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "Acompanhe armazenamento e memória direto na tela inicial do celular."
        subtitle.textSize = 15f
        subtitle.setTextColor(Color.rgb(80, 90, 110))
        subtitle.setPadding(0, dp(4), 0, dp(14))
        root.addView(subtitle)

        val mainCard = card()
        mainCard.addView(titleText("Widget do Android"))

        val mainText = TextView(this)
        mainText.text =
            "O widget serve para mostrar informações rápidas do aparelho sem precisar abrir o app.\n\n" +
            "Ele pode ajudar a acompanhar armazenamento, memória RAM e acesso rápido à limpeza."
        mainText.textSize = 16f
        mainText.setTextColor(Color.rgb(70, 80, 100))
        mainText.setPadding(0, dp(8), 0, dp(10))
        mainCard.addView(mainText)

        val widgetItems = TextView(this)
        widgetItems.text =
            "• Armazenamento usado e livre\n" +
            "• Memória/RAM em uso\n" +
            "• Acesso rápido à limpeza\n" +
            "• Status geral do aparelho"
        widgetItems.textSize = 15f
        widgetItems.setTextColor(Color.rgb(45, 55, 75))
        widgetItems.setPadding(0, dp(4), 0, 0)
        mainCard.addView(widgetItems)

        root.addView(mainCard)

        val howCard = card()
        howCard.addView(titleText("Como adicionar na tela inicial"))

        val steps = TextView(this)
        steps.text =
            "1. Toque e segure em uma área vazia da tela inicial.\n" +
            "2. Toque em Widgets.\n" +
            "3. Procure este app na lista.\n" +
            "4. Segure o widget e arraste para a tela inicial."
        steps.textSize = 15f
        steps.setTextColor(Color.rgb(70, 80, 100))
        steps.setPadding(0, dp(8), 0, 0)
        howCard.addView(steps)

        root.addView(howCard)

        val noteCard = card()
        noteCard.addView(titleText("Observação"))

        val note = TextView(this)
        note.text = "Se o widget não aparecer ou não atualizar, remova e adicione novamente na tela inicial."
        note.textSize = 14f
        note.setTextColor(Color.rgb(80, 90, 110))
        note.setPadding(0, dp(8), 0, 0)
        noteCard.addView(note)

        root.addView(noteCard)
    }

    private fun bottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(8), dp(8), dp(8), dp(10))
        nav.background = rounded(Color.WHITE, Color.rgb(222, 228, 242), dp(24))

        nav.addView(navItem("◉\nPainel", false) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })

        nav.addView(navItem("▦\nWidget", false) {
            drawWidget()
        })

        nav.addView(navItem("✦\nLimpeza", true) {
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
            setTextColor(if (active) Color.rgb(42, 92, 255) else Color.rgb(80, 90, 110))
            setPadding(dp(4), dp(4), dp(4), dp(6))
            if (active) background = rounded(Color.rgb(232, 245, 255), Color.TRANSPARENT, dp(16))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, Color.rgb(218, 225, 240), dp(22))
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

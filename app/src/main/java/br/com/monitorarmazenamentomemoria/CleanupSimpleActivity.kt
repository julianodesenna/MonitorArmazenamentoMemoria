package br.com.monitorarmazenamentomemoria
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
import android.content.ActivityNotFoundException

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
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
      private val cleanupBackStack = mutableListOf<String>()

      private val homeSummaryCache = mutableMapOf<String, String>()
      private val homeSummarySizeCache = mutableMapOf<String, Long>()
      private var homeSummarySignature = ""
    private var minSizeMb = 10
    private var orderMode = "size"
    private var allFiles: List<FileItem> = emptyList()
    private val selectedFiles = mutableSetOf<String>()
    private val previewCache = mutableMapOf<String, Bitmap>()
    private val previewExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val previewMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var categoryDisplayLimit = 40
    private var yearFilter = "Todos"
      private var appFilterMode = "Todos"
    private var autoLoadMoreLocked = false
    private lateinit var selectedInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
          // T20B2_NATIVE_BACK_CALLBACK
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
              try {
                  onBackInvokedDispatcher.registerOnBackInvokedCallback(
                      android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
                  ) {
                      if (!navigateBackInCleanup()) {
                          finish()
                      }
                  }
              } catch (_: Exception) {
              }
          }

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
          if (navigateBackInCleanup()) {
              return
          }

          super.onBackPressed()
      }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        /*
         * N05E2_ligar_selecao_apps_desinstalar_em_lote
         *
         * Recebe o retorno oficial do Android após cada confirmação de desinstalação
         * e entrega para o BatchUninstallManager continuar a fila.
         *
         * NÃO tentar desinstalar silenciosamente.
         * NÃO usar root.
         * NÃO usar ADB.
         * NÃO usar Shizuku.
         */
        if (BatchUninstallManager.handleActivityResult(this, requestCode, resultCode)) {
            selectedFiles.clear()

            if (::root.isInitialized) {
                try {
                    scanFiles()
                } catch (_: Throwable) {
                }

                /*
                 * N05E2_FIX1_atualizar_lista_apps_apos_desinstalar
                 *
                 * A desinstalação em lote já funcionava, mas a lista podia ficar
                 * com apps antigos presos na tela. Depois que a fila termina,
                 * redesenhamos Aplicativos para consultar novamente o Android.
                 *
                 * NÃO mexer na fila que já funcionou.
                 * NÃO tentar desinstalar silenciosamente.
                 */
                try {
                    window.decorView.postDelayed({
                        if (!BatchUninstallManager.hasRunningQueue(this) && currentCategory == "Aplicativos") {
                            selectedFiles.clear()
                            homeSummarySignature = ""
                            showCategory("Aplicativos", cleanupCategoryDesc("Aplicativos"))
                        }
                    }, 450L)
                } catch (_: Throwable) {
                }
            }

            return
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
          cleanupBackStack.clear()

        removeFloatingListNav()
        currentCategory = ""
        categoryDisplayLimit = 40
        yearFilter = "Todos"
        categoryDisplayLimit = 40
        yearFilter = "Todos"
        root.removeAllViews()
          refreshHomeSummaryCacheIfNeeded()
        // Resumo premium do aparelho
        val data = Monitor.read(this)

        fun smallLabel(textValue: String): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(95, 105, 125))
                includeFontPadding = false
            }
        }

        fun valueText(textValue: String): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(10, 18, 36))
                includeFontPadding = false
            }
        }

        fun premiumActionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView {
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

        fun metricPill(label: String, value: String): LinearLayout {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.setPadding(dp(8), dp(8), dp(8), dp(8))
            box.background = rounded(
                Color.rgb(248, 250, 253),
                Color.rgb(225, 231, 242),
                dp(18)
            )

            val labelView = smallLabel(label)
            labelView.gravity = Gravity.CENTER

            val valueView = valueText(value)
            valueView.gravity = Gravity.CENTER
            valueView.textSize = 18f

            box.addView(labelView)
            box.addView(valueView)

            return box
        }

        val heroCard = card()
        heroCard.setPadding(dp(18), dp(18), dp(18), dp(18))

        val heroTop = LinearLayout(this)
        heroTop.orientation = LinearLayout.HORIZONTAL
        heroTop.gravity = Gravity.CENTER_VERTICAL

        val heroTexts = LinearLayout(this)
        heroTexts.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = "Limpeza"
        title.textSize = 30f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(10, 18, 36))
        title.includeFontPadding = false
        heroTexts.addView(title)

        statusText = TextView(this)
        statusText.text = if (allFiles.isEmpty()) {
            "Analisando arquivos..."
        } else {
            "${allFiles.size} arquivos analisados"
        }
        statusText.textSize = 14f
        statusText.setTextColor(Color.rgb(80, 90, 110))
        statusText.setPadding(0, dp(6), 0, 0)
        heroTexts.addView(statusText)

        heroTop.addView(
            heroTexts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val statusBadge = TextView(this)
        statusBadge.text = if (data.storageUsedPercent >= 89) "Atenção" else "Normal"
        statusBadge.textSize = 12f
        statusBadge.gravity = Gravity.CENTER
        statusBadge.includeFontPadding = false
        statusBadge.setTypeface(null, Typeface.BOLD)
        statusBadge.setTextColor(
            if (data.storageUsedPercent >= 89) Color.rgb(130, 86, 0) else Color.rgb(20, 110, 70)
        )
        statusBadge.setPadding(dp(12), 0, dp(12), 0)
        statusBadge.background = rounded(
            if (data.storageUsedPercent >= 89) Color.rgb(255, 248, 224) else Color.rgb(228, 255, 240),
            if (data.storageUsedPercent >= 89) Color.rgb(244, 190, 65) else Color.rgb(120, 215, 160),
            dp(18)
        )
        heroTop.addView(statusBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))

        heroCard.addView(heroTop)

        val divider = View(this)
        divider.setBackgroundColor(Color.rgb(230, 235, 245))
        val dividerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        )
        dividerParams.setMargins(0, dp(16), 0, dp(14))
        heroCard.addView(divider, dividerParams)

        val metricsRow = LinearLayout(this)
        metricsRow.orientation = LinearLayout.HORIZONTAL

        val storagePill = metricPill(
            "Armazenamento",
            "${data.storageUsedPercent}%"
        )

        val freePill = metricPill(
            "Livre",
            Monitor.format(data.storageFree)
        )

        val ramPill = metricPill(
            "RAM",
            "${data.memoryUsedPercent}%"
        )

        val metricParams1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        metricParams1.setMargins(0, 0, dp(6), 0)

        val metricParams2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        metricParams2.setMargins(dp(3), 0, dp(3), 0)

        val metricParams3 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        metricParams3.setMargins(dp(6), 0, 0, 0)

        metricsRow.addView(storagePill, metricParams1)
        metricsRow.addView(freePill, metricParams2)
        metricsRow.addView(ramPill, metricParams3)

        heroCard.addView(metricsRow)

        val storageDetail = TextView(this)
        storageDetail.text = "RAM livre: ${Monitor.format(data.memoryFree)}"
        storageDetail.textSize = 13f
        storageDetail.setTextColor(Color.rgb(80, 90, 110))
        storageDetail.setPadding(0, dp(14), 0, 0)
        heroCard.addView(storageDetail)

        root.addView(heroCard)

        val actions = card()
        actions.setPadding(dp(18), dp(16), dp(18), dp(16))

        val actionsTitle = TextView(this)
        actionsTitle.text = "Ações rápidas"
        actionsTitle.textSize = 20f
        actionsTitle.setTypeface(null, Typeface.BOLD)
        actionsTitle.setTextColor(Color.rgb(10, 18, 36))
        actionsTitle.includeFontPadding = false
        actions.addView(actionsTitle)

        val actionsSubtitle = TextView(this)
        actionsSubtitle.text = "Atualize a análise ou abra ajustes do Android."
        actionsSubtitle.textSize = 14f
        actionsSubtitle.setTextColor(Color.rgb(80, 90, 110))
        actionsSubtitle.setPadding(0, dp(6), 0, dp(14))
        actions.addView(actionsSubtitle)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val update = premiumActionButton("Atualizar", true) {
            scanFiles()
            Toast.makeText(this, "Analisando arquivos novamente", Toast.LENGTH_SHORT).show()
        }

        val androidStorage = premiumActionButton("Armazenamento", false) {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        val updateParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        updateParams.setMargins(0, 0, dp(6), 0)

        val storageParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        storageParams.setMargins(dp(6), 0, 0, 0)

        row.addView(update, updateParams)
        row.addView(androidStorage, storageParams)
        actions.addView(row)

        val hasFileAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }

        val accessFiles = premiumActionButton(
            if (hasFileAccess) "Acesso aos arquivos liberado" else "Liberar acesso aos arquivos",
            false
        ) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        setData(Uri.fromParts("package", packageName, null))
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Ative o acesso a todos os arquivos e volte ao app", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        Toast.makeText(this, "Ative o acesso a todos os arquivos e volte ao app", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            } else {
                scanFiles()
                Toast.makeText(this, "Analisando arquivos novamente", Toast.LENGTH_SHORT).show()
            }
        }

        val accessParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        )
        accessParams.setMargins(0, dp(12), 0, 0)
        actions.addView(accessFiles, accessParams)

        root.addView(actions)

        val categoriesTitle = TextView(this)
        categoriesTitle.text = "Categorias"
        categoriesTitle.textSize = 22f
        categoriesTitle.setTypeface(null, Typeface.BOLD)
        categoriesTitle.setTextColor(Color.rgb(14, 26, 56))
        categoriesTitle.setPadding(0, dp(8), 0, dp(10))
        root.addView(categoriesTitle)

          addHomeCategoriesSortedBySize()

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

    private fun refreshHomeSummaryCacheIfNeeded() {
          if (allFiles.isEmpty()) {
              homeSummaryCache.clear()
              homeSummarySizeCache.clear()
              homeSummarySignature = "empty"
              return
          }

          val maxModified = allFiles.maxOfOrNull { it.modified } ?: 0L
          val totalSize = allFiles.sumOf { it.size }
          val appsSignature = try { installedAppItems().size } catch (_: Exception) { 0 }
          val signature = "${allFiles.size}|$totalSize|$maxModified|$minSizeMb|$appsSignature"

          if (signature == homeSummarySignature && homeSummaryCache.isNotEmpty() && homeSummarySizeCache.isNotEmpty()) {
              return
          }

          data class Bucket(var count: Int = 0, var size: Long = 0L)

          fun put(map: MutableMap<String, Bucket>, key: String, item: FileItem) {
              val bucket = map.getOrPut(key) { Bucket() }
              bucket.count += 1
              bucket.size += item.size
          }

          fun lowerName(item: FileItem): String = item.name.lowercase(Locale.ROOT)
          fun lowerPath(item: FileItem): String = item.path.lowercase(Locale.ROOT)

          fun isImage(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".jpg") ||
                  n.endsWith(".jpeg") ||
                  n.endsWith(".png") ||
                  n.endsWith(".webp") ||
                  n.endsWith(".gif") ||
                  n.endsWith(".bmp") ||
                  n.endsWith(".heic") ||
                  n.endsWith(".heif")
          }

          fun isVideo(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".mp4") ||
                  n.endsWith(".mkv") ||
                  n.endsWith(".mov") ||
                  n.endsWith(".avi") ||
                  n.endsWith(".3gp") ||
                  n.endsWith(".webm")
          }

          fun isAudio(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".opus") ||
                  n.endsWith(".mp3") ||
                  n.endsWith(".m4a") ||
                  n.endsWith(".wav") ||
                  n.endsWith(".aac") ||
                  n.endsWith(".ogg") ||
                  n.endsWith(".flac")
          }

          fun isApk(item: FileItem): Boolean {
              return lowerName(item).endsWith(".apk")
          }

          fun isDownload(item: FileItem): Boolean {
              val p = lowerPath(item)
              return p.contains("/download") ||
                  p.contains("/downloads") ||
                  p.contains("/baixados")
          }

          fun isWhatsapp(item: FileItem): Boolean {
              val p = lowerPath(item)
              return p.contains("whatsapp") ||
                  p.contains("com.whatsapp") ||
                  p.contains("com.whatsapp.w4b")
          }

          fun isBackup(item: FileItem): Boolean {
              val n = lowerName(item)
              val p = lowerPath(item)
              return n.endsWith(".db") ||
                  n.endsWith(".sqlite") ||
                  n.contains("crypt") ||
                  n.endsWith(".bak") ||
                  n.endsWith(".backup") ||
                  p.contains("/backup") ||
                  p.contains("/backups") ||
                  p.contains("/databases/")
          }

          val now = System.currentTimeMillis()
          val last24h = now - (24L * 60L * 60L * 1000L)
          val bigCutoff = minSizeMb * 1024L * 1024L

          val buckets = mutableMapOf<String, Bucket>()

          allFiles.forEach { item ->
              if (item.modified >= last24h) put(buckets, "Novos nas últimas 24h", item)
              if (item.size >= bigCutoff) put(buckets, "Arquivos grandes", item)
              if (item.risk == "alto") put(buckets, "Sensíveis", item)

              if (isImage(item)) put(buckets, "Imagens", item)
              if (isVideo(item)) put(buckets, "Vídeos", item)
              if (isAudio(item)) put(buckets, "Áudios", item)
              if (isDownload(item)) put(buckets, "Downloads", item)
              if (isWhatsapp(item)) put(buckets, "WhatsApp", item)
              if (isBackup(item) && !isWhatsapp(item)) put(buckets, "Backups", item)
              if (isApk(item)) put(buckets, "APKs", item)
          }

          val latest = allFiles.sortedByDescending { it.modified }.take(200)
          latest.forEach { put(buckets, "Últimos modificados", it) }

          homeSummaryCache.clear()
          homeSummarySizeCache.clear()

          buckets.forEach { entry ->
              val bucket = entry.value
              homeSummaryCache[entry.key] = "${bucket.count} arquivos • ${formatSize(bucket.size)}"
              homeSummarySizeCache[entry.key] = bucket.size
          }

          try {
              val apps = installedAppItems()
              val appTotal = apps.sumOf { it.size }
              homeSummaryCache["Aplicativos"] = "${apps.size} apps • ${formatSize(appTotal)}"
              homeSummarySizeCache["Aplicativos"] = appTotal
          } catch (_: Exception) {
              homeSummaryCache["Aplicativos"] = "Toque para abrir filtros de aplicativos"
              homeSummarySizeCache["Aplicativos"] = 0L
          }

          homeSummaryCache["Duplicados"] = "Toque para analisar possíveis duplicados"
          homeSummarySizeCache["Duplicados"] = 0L

          homeSummaryCache["Cache e temporários"] = "Toque para abrir cache e temporários"
          homeSummarySizeCache["Cache e temporários"] = 0L

          homeSummarySignature = signature
      }

private fun fastCategorySummary(title: String): String {
          val cached = homeSummaryCache[title]
          if (!cached.isNullOrBlank()) return cached

          return when (title) {
              "Duplicados",
              "Arquivos duplicados",
              "Imagens duplicadas",
              "Vídeos duplicados",
              "Áudios duplicados",
              "Documentos duplicados" -> "Toque para analisar possíveis duplicados"

              "Cache e temporários",
              "Cache acessível",
              "Temporários",
              "Miniaturas",
              "Cache do WhatsApp" -> "Toque para abrir cache e temporários"

              "WhatsApp" -> "Toque para abrir as pastas do WhatsApp"
              "Aplicativos" -> "Toque para abrir filtros de aplicativos"

              else -> "Toque para abrir"
          }
      }

private fun cleanupParentCategory(title: String): String? {
          return when (title) {
              "Fotos do WhatsApp",
              "Vídeos do WhatsApp",
              "Documentos do WhatsApp",
              "Áudios do WhatsApp",
              "Backups do WhatsApp",
              "Todos do WhatsApp" -> "WhatsApp"

              "Arquivos duplicados",
              "Imagens duplicadas",
              "Vídeos duplicados",
              "Áudios duplicados",
              "Documentos duplicados" -> "Duplicados"

              "Cache acessível",
              "Temporários",
              "Miniaturas",
              "Cache do WhatsApp" -> "Cache e temporários"

              else -> null
          }
      }

      private fun cleanupIsMainFolder(title: String): Boolean {
          return title == "WhatsApp" ||
              title == "Duplicados" ||
              title == "Cache e temporários"
      }

private fun cleanupCategoryDesc(title: String): String {
          return when (title) {
              "WhatsApp" -> "Arquivos do WhatsApp por pasta"
              "Duplicados" -> "Possíveis arquivos repetidos"
              "Cache e temporários" -> "Caches acessíveis e arquivos temporários"
              "Novos nas últimas 24h" -> "Arquivos criados ou alterados recentemente"
              "Últimos modificados" -> "Arquivos mais recentes do aparelho"
              "Arquivos grandes" -> "Arquivos que ocupam mais espaço"
              "Sensíveis" -> "Arquivos que exigem cuidado"
              "Imagens" -> "Fotos e imagens do aparelho"
              "Vídeos" -> "Vídeos encontrados no armazenamento"
              "Áudios" -> "Músicas, gravações e áudios"
              "Downloads" -> "Arquivos baixados"
              "Backups" -> "Backups e bancos de dados"
              "Aplicativos" -> "Apps instalados e filtros"
              "APKs" -> "Instaladores APK encontrados"
              "Fotos do WhatsApp" -> "Imagens recebidas e enviadas"
              "Vídeos do WhatsApp" -> "Vídeos recebidos e enviados"
              "Documentos do WhatsApp" -> "PDFs, planilhas e arquivos"
              "Áudios do WhatsApp" -> "Áudios e mensagens de voz"
              "Backups do WhatsApp" -> "Bancos de dados e backups"
              "Todos do WhatsApp" -> "Tudo que estiver no WhatsApp"
              "Arquivos duplicados" -> "Todos os possíveis duplicados"
              "Imagens duplicadas" -> "Fotos e imagens repetidas"
              "Vídeos duplicados" -> "Vídeos possivelmente repetidos"
              "Áudios duplicados" -> "Áudios possivelmente repetidos"
              "Documentos duplicados" -> "Documentos e arquivos compactados repetidos"
              "Cache acessível" -> "Pastas cache visíveis no armazenamento"
              "Temporários" -> "Arquivos temporários"
              "Miniaturas" -> "Thumbnails e prévias"
              "Cache do WhatsApp" -> "Cache acessível relacionado ao WhatsApp"
              else -> ""
          }
      }

      private fun openCleanupCategory(title: String, desc: String) {
          val previous = currentCategory
          if (cleanupBackStack.isEmpty() || cleanupBackStack.last() != previous) {
              cleanupBackStack.add(previous)
          }
          showCategory(title, desc)
      }

      private fun navigateBackInCleanup(): Boolean {
          val current = currentCategory

          val parent = cleanupParentCategory(current)
          if (parent != null) {
              categoryDisplayLimit = 40
              autoLoadMoreLocked = false
              showCategory(parent, cleanupCategoryDesc(parent))
              return true
          }

          if (cleanupIsMainFolder(current)) {
              categoryDisplayLimit = 40
              autoLoadMoreLocked = false
              drawHome()
              return true
          }

          if (cleanupBackStack.isNotEmpty()) {
              val previous = cleanupBackStack.removeAt(cleanupBackStack.size - 1)

              categoryDisplayLimit = 40
              autoLoadMoreLocked = false

              if (previous.isBlank()) {
                  drawHome()
              } else {
                  showCategory(previous, cleanupCategoryDesc(previous))
              }

              return true
          }

          if (current.isNotBlank()) {
              categoryDisplayLimit = 40
              autoLoadMoreLocked = false
              drawHome()
              return true
          }

          return false
      }

private fun homeCategorySizeForOrder(title: String): Long {
          return homeSummarySizeCache[title] ?: 0L
      }

      private fun addHomeCategoriesSortedBySize() {
          data class HomeCategory(
              val icon: String,
              val title: String,
              val desc: String
          )

          val fixedTop = listOf(
              HomeCategory("🆕", "Novos nas últimas 24h", "Arquivos criados ou adicionados hoje"),
              HomeCategory("🕘", "Últimos modificados", "Arquivos alterados recentemente")
          )

          val sortable = listOf(
              HomeCategory("📦", "Arquivos grandes", "Os maiores arquivos do aparelho"),
              HomeCategory("🔒", "Sensíveis", "Arquivos que exigem conferência manual"),
              HomeCategory("🖼️", "Imagens", "Fotos, prints e imagens salvas"),
              HomeCategory("🎬", "Vídeos", "Vídeos que ocupam mais espaço"),
              HomeCategory("🎵", "Áudios", "Músicas, gravações e áudios"),
              HomeCategory("⬇️", "Downloads", "Arquivos baixados e recebidos"),
              HomeCategory("💬", "WhatsApp", "Mídias e documentos separados por pasta"),
              HomeCategory("🧬", "Duplicados", "Possíveis repetidos para conferir"),
              HomeCategory("🧹", "Cache e temporários", "Arquivos acessíveis de limpeza leve"),
              HomeCategory("🗄️", "Backups", "Cópias, bancos e arquivos de reserva"),
              HomeCategory("📱", "Aplicativos", "Apps instalados com filtros de análise"),
              HomeCategory("📦", "APKs", "Instaladores APK salvos no aparelho")
          )

          val ordered = fixedTop + sortable.sortedWith(
              compareByDescending<HomeCategory> { homeCategorySizeForOrder(it.title) }
                  .thenBy { it.title }
          )

          var i = 0
          while (i < ordered.size) {
              val first = ordered[i]
              val second = ordered.getOrNull(i + 1)

              if (second != null) {
                  addCategoryRow(
                      first.icon,
                      first.title,
                      first.desc,
                      second.icon,
                      second.title,
                      second.desc
                  )
                  i += 2
              } else {
                  addCategorySingleRow(
                      first.icon,
                      first.title,
                      first.desc
                  )
                  i += 1
              }
          }
      }

private fun categoryCard(icon: String, title: String, desc: String): LinearLayout {
          val summary = fastCategorySummary(title)

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
          val fullText = "$desc\n$summary"
          val spannable = android.text.SpannableString(fullText)
          val sizeRegex = Regex("""\d+(?:[,.]\d+)?\s*(?:B|KB|MB|GB|TB)""")
          val sizeMatch = sizeRegex.find(fullText)

          if (sizeMatch != null) {
              spannable.setSpan(
                  android.text.style.StyleSpan(Typeface.BOLD),
                  sizeMatch.range.first,
                  sizeMatch.range.last + 1,
                  android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
              )
          }

          d.text = spannable
          d.textSize = 11f
          d.setTextColor(Color.rgb(80, 90, 110))
          d.setPadding(0, dp(6), 0, 0)
          box.addView(d)

          box.setOnClickListener {
              openCleanupCategory(title, desc)
          }

          val params = LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT
          )
          params.setMargins(dp(4), dp(4), dp(4), dp(8))
          box.layoutParams = params

          return box
      }

    private fun showWhatsappFolders() {
          removeFloatingListNav()
          currentCategory = "WhatsApp"
          categoryDisplayLimit = 40
          yearFilter = "Todos"
          autoLoadMoreLocked = false
          root.removeAllViews()

          val back = Button(this)
          back.text = "← Voltar para categorias"
          back.setOnClickListener {
              if (!navigateBackInCleanup()) {
                  categoryDisplayLimit = 40
                  drawHome()
              }
          }
          root.addView(back)

          val title = TextView(this)
          title.text = "WhatsApp"
          title.textSize = 28f
          title.setTypeface(null, Typeface.BOLD)
          title.setTextColor(Color.rgb(10, 18, 36))
          title.setPadding(0, dp(12), 0, dp(4))
          root.addView(title)

          val subtitle = TextView(this)
          subtitle.text = "Arquivos do WhatsApp organizados por pasta."
          subtitle.textSize = 15f
          subtitle.setTextColor(Color.rgb(80, 90, 110))
          subtitle.setPadding(0, 0, 0, dp(14))
          root.addView(subtitle)

          addCategoryRow(
              "🖼️",
              "Fotos do WhatsApp",
              "Imagens recebidas e enviadas",
              "🎬",
              "Vídeos do WhatsApp",
              "Vídeos recebidos e enviados"
          )

          addCategoryRow(
              "📄",
              "Documentos do WhatsApp",
              "PDFs, planilhas e arquivos",
              "🎵",
              "Áudios do WhatsApp",
              "Áudios e mensagens de voz"
          )

          addCategoryRow(
              "🗄️",
              "Backups do WhatsApp",
              "Bancos de dados e backups",
              "📁",
              "Todos do WhatsApp",
              "Tudo que estiver no WhatsApp"
          )
      }

private fun showDuplicateFolders() {
          removeFloatingListNav()
          currentCategory = "Duplicados"
          categoryDisplayLimit = 40
          yearFilter = "Todos"
          autoLoadMoreLocked = false
          root.removeAllViews()
          // T21B_NOTICE_showDuplicateFolders
          addCleanupNoticeCard("Duplicados são sugestões", "Confira nome, pasta e data antes de excluir. A análise evita travar e aponta possíveis repetidos, mas a decisão final deve ser manual.")

          val back = Button(this)
          back.text = "← Voltar para categorias"
          back.setOnClickListener {
              if (!navigateBackInCleanup()) {
                  categoryDisplayLimit = 40
                  drawHome()
              }
          }
          root.addView(back)

          val title = TextView(this)
          title.text = "Duplicados"
          title.textSize = 28f
          title.setTypeface(null, Typeface.BOLD)
          title.setTextColor(Color.rgb(10, 18, 36))
          title.setPadding(0, dp(12), 0, dp(4))
          root.addView(title)

          val subtitle = TextView(this)
          subtitle.text = "Possíveis arquivos duplicados identificados de forma leve, sem varredura pesada."
          subtitle.textSize = 15f
          subtitle.setTextColor(Color.rgb(80, 90, 110))
          subtitle.setPadding(0, 0, 0, dp(14))
          root.addView(subtitle)

          val warning = TextView(this)
          warning.text = "Atenção: esta primeira análise usa tamanho + extensão. Antes de excluir, confira os arquivos."
          warning.textSize = 12f
          warning.setTextColor(Color.rgb(120, 78, 20))
          warning.setPadding(dp(12), dp(10), dp(12), dp(10))
          warning.background = rounded(Color.rgb(255, 248, 232), Color.rgb(238, 202, 126), dp(14))
          root.addView(warning)

          addCategoryRow(
              "📁",
              "Arquivos duplicados",
              "Todos os possíveis duplicados",
              "🖼️",
              "Imagens duplicadas",
              "Fotos e imagens repetidas"
          )

          addCategoryRow(
              "🎬",
              "Vídeos duplicados",
              "Vídeos possivelmente repetidos",
              "🎵",
              "Áudios duplicados",
              "Áudios possivelmente repetidos"
          )

          addCategorySingleRow(
              "📄",
              "Documentos duplicados",
              "Documentos e arquivos compactados repetidos"
          )
      }

private fun showCacheFolders() {
          removeFloatingListNav()
          currentCategory = "Cache e temporários"
          categoryDisplayLimit = 40
          yearFilter = "Todos"
          autoLoadMoreLocked = false
          root.removeAllViews()
          // T21B_NOTICE_showCacheFolders
          addCleanupNoticeCard("Cache com limite do Android", "O app mostra apenas arquivos acessíveis. Cache interno de outros aplicativos pode exigir abertura da tela oficial de armazenamento do Android.")

          val back = Button(this)
          back.text = "← Voltar para categorias"
          back.setOnClickListener {
              if (!navigateBackInCleanup()) {
                  categoryDisplayLimit = 40
                  drawHome()
              }
          }
          root.addView(back)

          val title = TextView(this)
          title.text = "Cache e temporários"
          title.textSize = 28f
          title.setTypeface(null, Typeface.BOLD)
          title.setTextColor(Color.rgb(10, 18, 36))
          title.setPadding(0, dp(12), 0, dp(4))
          root.addView(title)

          val subtitle = TextView(this)
          subtitle.text = "Temporários e caches acessíveis pelo app."
          subtitle.textSize = 15f
          subtitle.setTextColor(Color.rgb(80, 90, 110))
          subtitle.setPadding(0, 0, 0, dp(14))
          root.addView(subtitle)

          val warning = TextView(this)
          warning.text = "O Android não permite limpar silenciosamente o cache interno de todos os apps. Aqui aparecem caches acessíveis por arquivo. Para cache interno de apps, use o atalho oficial do Android abaixo."
          warning.textSize = 12f
          warning.setTextColor(Color.rgb(120, 78, 20))
          warning.setPadding(dp(12), dp(10), dp(12), dp(10))
          warning.background = rounded(Color.rgb(255, 248, 232), Color.rgb(238, 202, 126), dp(14))
          root.addView(warning)

          addCategoryRow(
              "🧹",
              "Cache acessível",
              "Pastas cache visíveis no armazenamento",
              "🕒",
              "Temporários",
              "Temporários"
          )

          addCategoryRow(
              "🖼️",
              "Miniaturas",
              "Miniaturas e prévias",
              "💬",
              "Cache do WhatsApp",
              "Cache acessível relacionado ao WhatsApp"
          )

          val androidCard = card()
          androidCard.addView(titleText("Cache dos aplicativos pelo Android"))

          val androidText = TextView(this)
          androidText.text = "Abre a tela oficial de armazenamento do Android. Por segurança, o Android exige limpar cache de apps por lá."
          androidText.textSize = 14f
          androidText.setTextColor(Color.rgb(70, 80, 100))
          androidText.setPadding(0, dp(8), 0, dp(12))
          androidCard.addView(androidText)

          val openStorage = Button(this)
          openStorage.text = "ABRIR ARMAZENAMENTO DO ANDROID"
          openStorage.setOnClickListener {
              try {
                  startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
              } catch (_: Exception) {
                  try {
                      startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                  } catch (_: Exception) {
                      startActivity(Intent(Settings.ACTION_SETTINGS))
                  }
              }
          }
          androidCard.addView(openStorage)

          root.addView(androidCard)
      }

private fun addCleanupNoticeCard(title: String, message: String) {
          val info = card()
          info.setPadding(dp(14), dp(14), dp(14), dp(14))

          val t = TextView(this)
          t.text = title
          t.textSize = 16f
          t.setTypeface(null, Typeface.BOLD)
          t.setTextColor(Color.rgb(14, 26, 56))
          t.includeFontPadding = false
          info.addView(t)

          val m = TextView(this)
          m.text = message
          m.textSize = 13f
          m.setTextColor(Color.rgb(80, 90, 110))
          m.setPadding(0, dp(8), 0, 0)
          info.addView(m)

          root.addView(info)
      }

      private fun cleanupEmptyMessage(categoryTitle: String): String {
          return when (categoryTitle) {
              "Novos nas últimas 24h" -> "Nenhum arquivo novo encontrado nas últimas 24 horas."
              "Últimos modificados" -> "Nenhum arquivo modificado recentemente foi encontrado."
              "Arquivos grandes" -> "Nenhum arquivo grande encontrado com o filtro atual. Reduza o filtro de tamanho para ver mais resultados."
              "Sensíveis" -> "Nenhum arquivo sensível encontrado nesta análise."
              "Imagens" -> "Nenhuma imagem encontrada nesta análise."
              "Vídeos" -> "Nenhum vídeo encontrado nesta análise."
              "Áudios" -> "Nenhum áudio encontrado nesta análise."
              "Downloads" -> "Nenhum arquivo baixado encontrado nesta análise."
              "Backups" -> "Nenhum backup separado encontrado nesta análise."
              "APKs" -> "Nenhum instalador APK encontrado nesta análise."
              "Aplicativos" -> "Nenhum aplicativo encontrado com o filtro atual."
              "Fotos do WhatsApp" -> "Nenhuma foto do WhatsApp encontrada nesta análise."
              "Vídeos do WhatsApp" -> "Nenhum vídeo do WhatsApp encontrado nesta análise."
              "Documentos do WhatsApp" -> "Nenhum documento do WhatsApp encontrado nesta análise."
              "Áudios do WhatsApp" -> "Nenhum áudio do WhatsApp encontrado nesta análise."
              "Backups do WhatsApp" -> "Nenhum backup do WhatsApp encontrado nesta análise."
              "Todos do WhatsApp" -> "Nenhum arquivo do WhatsApp encontrado nesta análise."
              "Arquivos duplicados",
              "Imagens duplicadas",
              "Vídeos duplicados",
              "Áudios duplicados",
              "Documentos duplicados" -> "Nenhum possível duplicado encontrado com a análise atual."
              "Cache acessível",
              "Temporários",
              "Miniaturas",
              "Cache do WhatsApp" -> "Nenhum arquivo desta categoria foi encontrado nesta análise."
              else -> "Nenhum item encontrado nesta categoria."
          }
      }

      private fun cleanupCategoryWarningTitle(categoryTitle: String): String {
          return when (categoryTitle) {
              "Arquivos duplicados",
              "Imagens duplicadas",
              "Vídeos duplicados",
              "Áudios duplicados",
              "Documentos duplicados" -> "Atenção antes de excluir"
              "Cache acessível",
              "Temporários",
              "Miniaturas",
              "Cache do WhatsApp" -> "Limpeza com segurança"
              else -> ""
          }
      }

      private fun cleanupCategoryWarningMessage(categoryTitle: String): String {
          return when (categoryTitle) {
              "Arquivos duplicados",
              "Imagens duplicadas",
              "Vídeos duplicados",
              "Áudios duplicados",
              "Documentos duplicados" -> "A lista mostra possíveis duplicados por tamanho e tipo. Confira o nome, pasta e data antes de excluir qualquer arquivo."
              "Cache acessível",
              "Temporários",
              "Miniaturas",
              "Cache do WhatsApp" -> "O Android limita a limpeza automática de cache interno de aplicativos. Esta área mostra apenas arquivos acessíveis ao app."
              else -> ""
          }
      }

private fun showCategory(categoryTitle: String, categoryDesc: String) {
          if (categoryTitle == "Cache e temporários") {
              showCacheFolders()
              return
          }

          if (categoryTitle == "Duplicados") {
              showDuplicateFolders()
              return
          }

          if (categoryTitle == "WhatsApp") {
              showWhatsappFolders()
              return
          }

        removeFloatingListNav()
        if (currentCategory != categoryTitle) {
              if (categoryTitle != "Aplicativos") appFilterMode = "Todos"
            categoryDisplayLimit = 40
            yearFilter = "Todos"
        }
        autoLoadMoreLocked = false
        currentCategory = categoryTitle
        root.removeAllViews()

        val back = Button(this)
        back.text = "← Voltar para categorias"
        back.setOnClickListener {
              if (!navigateBackInCleanup()) {
                  categoryDisplayLimit = 40
                  drawHome()
              }
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

          // T21B_CATEGORY_NOTICE
          val warningTitle = cleanupCategoryWarningTitle(categoryTitle)
          val warningMessage = cleanupCategoryWarningMessage(categoryTitle)
          if (warningTitle.isNotBlank() && warningMessage.isNotBlank()) {
              addCleanupNoticeCard(warningTitle, warningMessage)
          }

        val rawCategoryItems = categoryFiles(categoryTitle)

          // T21B_EMPTY_CATEGORY_MESSAGE
          if (rawCategoryItems.isEmpty()) {
              addCleanupNoticeCard("Nada encontrado", cleanupEmptyMessage(categoryTitle))
              return
          }

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
            categoryDisplayLimit = 40
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(sizeRow, chipButton("50 MB", minSizeMb == 50) {
            minSizeMb = 50
            categoryDisplayLimit = 40
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(sizeRow, chipButton("100 MB", minSizeMb == 100) {
            minSizeMb = 100
            categoryDisplayLimit = 40
            showCategory(categoryTitle, categoryDesc)
        })

        filter.addView(sizeRow)

        val orderRow = LinearLayout(this)
        orderRow.orientation = LinearLayout.HORIZONTAL

        addWeightedChip(orderRow, chipButton("Maiores", orderMode == "size") {
            orderMode = "size"
            categoryDisplayLimit = 40
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(orderRow, chipButton("Recentes", orderMode == "date") {
            orderMode = "date"
            categoryDisplayLimit = 40
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(orderRow, chipButton("Antigos", orderMode == "old") {
            orderMode = "old"
            categoryDisplayLimit = 40
            showCategory(categoryTitle, categoryDesc)
        })

        filter.addView(orderRow)

          if (categoryTitle == "Aplicativos") {
              val appFilterTitle = TextView(this)
              appFilterTitle.text = "Filtro de aplicativos"
              appFilterTitle.textSize = 11f
              appFilterTitle.setTypeface(null, Typeface.BOLD)
              appFilterTitle.setTextColor(Color.rgb(80, 90, 110))
              appFilterTitle.setPadding(0, dp(8), 0, dp(6))
              filter.addView(appFilterTitle)

              val appRow1 = LinearLayout(this)
              appRow1.orientation = LinearLayout.HORIZONTAL

              addWeightedChip(appRow1, chipButton("Todos", appFilterMode == "Todos") {
                  appFilterMode = "Todos"
                  categoryDisplayLimit = 40
                  showCategory(categoryTitle, categoryDesc)
              })

              addWeightedChip(appRow1, chipButton("Baixados", appFilterMode == "Baixados") {
                  appFilterMode = "Baixados"
                  categoryDisplayLimit = 40
                  showCategory(categoryTitle, categoryDesc)
              })

              filter.addView(appRow1)

              val appRow2 = LinearLayout(this)
              appRow2.orientation = LinearLayout.HORIZONTAL

              addWeightedChip(appRow2, chipButton("Sistema", appFilterMode == "Sistema") {
                  appFilterMode = "Sistema"
                  categoryDisplayLimit = 40
                  showCategory(categoryTitle, categoryDesc)
              })

              addWeightedChip(appRow2, chipButton("Sistema atualizado", appFilterMode == "Sistema atualizado") {
                  appFilterMode = "Sistema atualizado"
                  categoryDisplayLimit = 40
                  showCategory(categoryTitle, categoryDesc)
              })

              filter.addView(appRow2)

              val appRow3 = LinearLayout(this)
              appRow3.orientation = LinearLayout.HORIZONTAL

              addWeightedChip(appRow3, chipButton("Não utilizados", appFilterMode == "Não utilizados") {
                  appFilterMode = "Não utilizados"
                  categoryDisplayLimit = 40
                  showCategory(categoryTitle, categoryDesc)
              })

              filter.addView(appRow3)

              if (appFilterMode == "Não utilizados") {
                  val usageHint = TextView(this)
                  usageHint.text = "Não utilizados: se o Android permitir Acesso ao uso, o app usa o último uso real. Se não permitir, mostra uma estimativa com apps baixados mais antigos/menos atualizados. Toque aqui para tentar abrir Acesso ao uso."
                  usageHint.textSize = 11f
                  usageHint.setTextColor(Color.rgb(80, 90, 110))
                  usageHint.setPadding(dp(10), dp(8), dp(10), dp(8))
                  usageHint.background = rounded(Color.rgb(248, 250, 253), Color.rgb(218, 225, 236), dp(12))
                  usageHint.setOnClickListener {
                      try {
                          startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                      } catch (_: Exception) {
                          startActivity(Intent(Settings.ACTION_SETTINGS))
                      }
                  }
                  filter.addView(usageHint)
              }


              /*
               * N05F1_apps_tamanho_real_android
               *
               * Mostra orientação para liberar Acesso ao uso, necessário para tentar
               * exibir App/Dados/Cache/Total como o Android mostra.
               */
              val androidStatsHint = TextView(this)
              androidStatsHint.text = if (AppStorageStatsHelper.hasUsageAccess(this)) {
                  "Tamanho real Android: Acesso ao uso liberado. Quando o Android permitir, os cards mostram App, Dados, Cache e Total. Toque para abrir a permissão."
              } else {
                  "Tamanho real Android: toque aqui e permita Acesso ao uso para tentar mostrar App, Dados, Cache e Total dentro do app."
              }
              androidStatsHint.textSize = 11f
              androidStatsHint.setTextColor(Color.rgb(80, 90, 110))
              androidStatsHint.setPadding(dp(10), dp(8), dp(10), dp(8))
              androidStatsHint.background = rounded(
                  if (AppStorageStatsHelper.hasUsageAccess(this)) Color.rgb(236, 255, 244) else Color.rgb(255, 248, 232),
                  if (AppStorageStatsHelper.hasUsageAccess(this)) Color.rgb(140, 215, 165) else Color.rgb(238, 202, 126),
                  dp(12)
              )
              androidStatsHint.setOnClickListener {
                  AppStorageStatsHelper.openUsageAccessSettings(this)
              }
              filter.addView(androidStatsHint)
          }

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
                categoryDisplayLimit = 40
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
            val itemWord = if (categoryTitle == "Aplicativos") "apps exibidos" else "arquivos exibidos"
            Toast.makeText(this, "${files.size} $itemWord selecionados", Toast.LENGTH_SHORT).show()
            showCategory(categoryTitle, categoryDesc)
        })

        addWeightedChip(selectRow, chipButton("Todos", false) {
            allCategoryItems.forEach { selectedFiles.add(it.path) }
            val itemWord = if (categoryTitle == "Aplicativos") "apps da categoria" else "arquivos da categoria"
            Toast.makeText(this, "${allCategoryItems.size} $itemWord selecionados", Toast.LENGTH_SHORT).show()
            showCategory(categoryTitle, categoryDesc)
        })

        selectionBox.addView(selectRow)

        val actionRow = LinearLayout(this)
        actionRow.orientation = LinearLayout.HORIZONTAL
        actionRow.setPadding(0, dp(4), 0, 0)

        if (categoryTitle == "Aplicativos") {
            val uninstallSelectedButton = chipButton("Desinstalar", false) {
                batchUninstallSelectedApps()
            }
            uninstallSelectedButton.setTextColor(Color.rgb(150, 38, 38))
            uninstallSelectedButton.background = rounded(
                Color.rgb(255, 245, 245),
                Color.rgb(245, 190, 190),
                dp(18)
            )
            addWeightedChip(actionRow, uninstallSelectedButton)
        } else {
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
        }

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
        val listItemWord = if (categoryTitle == "Aplicativos") "apps mostrados" else "arquivos mostrados"
        summary.text = "${files.size} de ${allCategoryItems.size} $listItemWord$yearInfo • exibidos: ${formatSize(files.sumOf { it.size })} • total: ${formatSize(allCategoryItems.sumOf { it.size })}"
        summary.textSize = 14f
        summary.setTextColor(Color.rgb(80, 90, 110))
        summary.setPadding(0, dp(4), 0, dp(12))
        root.addView(summary)

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
            type.contains("aplicativo") || path.startsWith("app:") -> "APP"
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
            if (isAppItem(item)) {
                launchInstalledApp(item)
                return
            }

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
        detail.text = if (isAppItem(item)) {
            "${item.type} • ${formatDate(item.modified)}"
        } else {
            "${item.type} • ${formatSize(item.size)} • ${formatDate(item.modified)}"
        }
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

        val openButton = openActionButton(if (isAppItem(item)) "Abrir app" else "Abrir arquivo", true) {
            if (isAppItem(item)) {
                launchInstalledApp(item)
            } else {
                openThisFile()
            }
        }

        val detailsButton = openActionButton(if (isAppItem(item)) "Desinstalar" else "Detalhes", false) {
            if (isAppItem(item)) {
                confirmUninstallApp(item)
            } else {
                showFileDetails()
            }
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

private fun selectedItemsForActions(): List<FileItem> {
    if (selectedFiles.isEmpty()) return emptyList()

    val selectedSet = selectedFiles.toSet()
    val result = mutableListOf<FileItem>()

    try {
        if (currentCategory.isNotBlank()) {
            result.addAll(categoryFiles(currentCategory).filter { selectedSet.contains(it.path) })
        }
    } catch (_: Exception) {
    }

    try {
        result.addAll(allFiles.filter { selectedSet.contains(it.path) })
    } catch (_: Exception) {
    }

    try {
        val appPaths = selectedSet.filter { it.startsWith("app:") }
        if (appPaths.isNotEmpty()) {
            result.addAll(installedAppItems().filter { appPaths.contains(it.path) })
        }
    } catch (_: Exception) {
    }

    return result.distinctBy { it.path }
}

private fun updateSelectedInfo() {
        if (::selectedInfoText.isInitialized) {
            val selected = selectedItemsForActions()
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

        val items = selectedItemsForActions()
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

    private fun batchUninstallSelectedApps() {
        /*
         * N05E2_ligar_selecao_apps_desinstalar_em_lote
         *
         * Liga os checkboxes da categoria Aplicativos ao BatchUninstallManager.
         *
         * Fluxo seguro:
         * 1. Selecionar vários apps.
         * 2. Tocar em Desinstalar.
         * 3. Filtrar apps do sistema/protegidos.
         * 4. Chamar a desinstalação oficial do Android em fila.
         * 5. O Android confirma um por um.
         *
         * NÃO apagar silenciosamente.
         * NÃO tentar remover app do sistema.
         * NÃO tentar remover o próprio app.
         */
        val selected = selectedItemsForActions().filter { isAppItem(it) }

        if (selected.isEmpty()) {
            Toast.makeText(this, "Nenhum aplicativo selecionado", Toast.LENGTH_SHORT).show()
            return
        }

        val ownPackage = packageName
        val systemApps = selected.filter { isSystemAppItem(it) }
        val ownApp = selected.filter { appPackage(it) == ownPackage }
        val allowedApps = selected
            .filter { !isSystemAppItem(it) }
            .filter { appPackage(it) != ownPackage }
            .distinctBy { appPackage(it) }

        if (allowedApps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Nenhum app pode ser desinstalado em lote")
                .setMessage(
                    "Os aplicativos selecionados parecem ser do sistema, protegidos pelo Android ou o próprio app atual.\n\n" +
                    "Para apps do sistema, use Detalhes do Android, quando disponível."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val sensitiveCount = allowedApps.count { it.risk == "alto" || sensitiveAppTag(it.name, appPackage(it)) != null }

        val message = buildString {
            append("Aplicativos selecionados: ${selected.size}\n")
            append("Entrarão na fila: ${allowedApps.size}\n")

            if (systemApps.isNotEmpty()) {
                append("Ignorados por serem do sistema/protegidos: ${systemApps.size}\n")
            }

            if (ownApp.isNotEmpty()) {
                append("Ignorado por ser este app: ${ownApp.size}\n")
            }

            if (sensitiveCount > 0) {
                append("\nAtenção: $sensitiveCount app(s) sensível(eis), financeiro(s), conversa(s), autenticação ou nuvem.\n")
            }

            append("\nO Android vai pedir confirmação para cada aplicativo.")
            append("\nDepois de confirmar um, o app tentará continuar para o próximo.")
        }

        AlertDialog.Builder(this)
            .setTitle("Desinstalar apps selecionados?")
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                val targets = allowedApps.map { app ->
                    BatchUninstallManager.Target(
                        packageName = appPackage(app),
                        label = app.name,
                        sizeText = formatSize(app.size),
                        sensitive = app.risk == "alto" || sensitiveAppTag(app.name, appPackage(app)) != null
                    )
                }

                BatchUninstallManager.start(this, targets)
            }
            .show()
    }

    private fun confirmDeleteSelectedFiles() {
    if (selectedFiles.isEmpty()) {
        Toast.makeText(this, "Nenhum arquivo selecionado", Toast.LENGTH_SHORT).show()
        return
    }

    val items = selectedItemsForActions()

    if (items.isEmpty()) {
        selectedFiles.clear()
        updateSelectedInfo()
        Toast.makeText(this, "A seleção não foi encontrada na lista atual", Toast.LENGTH_SHORT).show()
        return
    }

    val appItems = items.filter { isAppItem(it) }
    if (appItems.isNotEmpty()) {
        if (appItems.size == 1) {
            confirmUninstallApp(appItems.first())
        } else {
            AlertDialog.Builder(this)
                .setTitle("Aplicativos selecionados")
                .setMessage(
                    "O Android não permite desinstalar vários aplicativos de uma vez por aqui.\n\n" +
                    "Selecione apenas um aplicativo por vez e toque em Excluir."
                )
                .setPositiveButton("OK", null)
                .show()
        }
        return
    }

    val totalSize = items.sumOf { it.size }
    val sensitive = items.filter { it.risk == "alto" }

    val message = buildString {
        append("Arquivos selecionados: ${items.size}\n")
        append("Espaço estimado: ${formatSize(totalSize)}\n\n")
        append("Essa ação tentará apagar somente arquivos permitidos do armazenamento do aparelho.\n")
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
                    if (file.exists() && file.isFile && cleanupT25SafeDeleteFile(file)) {
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
                    Uri.fromParts("package", packageName, null)
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

override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)

    if (intent?.action == "br.com.monitorarmazenamentomemoria.UNINSTALL_RESULT") {
        handleUninstallResult(intent)
    }
}

override fun onResume() {
        super.onResume()

        /*
         * N05E2_ligar_selecao_apps_desinstalar_em_lote
         *
         * Se houver fila pendente, o gerenciador mantém o controle.
         * A lista é reanalisada para refletir apps removidos.
         */
        try {
            BatchUninstallManager.resumeIfNeeded(this)
        } catch (_: Throwable) {
        }

        if (::root.isInitialized && hasStorageAccess()) {
            scanFiles()
        }

        /*
         * N05E2_FIX1_atualizar_lista_apps_apos_desinstalar
         *
         * Se voltamos da tela oficial do Android e a fila já acabou,
         * força a categoria Aplicativos a ser redesenhada. Assim apps já
         * removidos não ficam presos visualmente na lista.
         */
        if (::root.isInitialized && currentCategory == "Aplicativos") {
            try {
                val removedSelections = selectedFiles
                    .filter { it.startsWith("app:") }
                    .filter { !isAppPackageInstalled(it.removePrefix("app:")) }
                    .toSet()

                if (removedSelections.isNotEmpty()) {
                    selectedFiles.removeAll(removedSelections)
                }

                window.decorView.postDelayed({
                    if (!BatchUninstallManager.hasRunningQueue(this) && currentCategory == "Aplicativos") {
                        homeSummarySignature = ""
                        showCategory("Aplicativos", cleanupCategoryDesc("Aplicativos"))
                    }
                }, 600L)
            } catch (_: Throwable) {
            }
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

private fun isAppPackageInstalled(packageName: String): Boolean {
    return try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Throwable) {
        false
    }
}

private fun appPackage(item: FileItem): String {
    return item.path.removePrefix("app:")
}

private fun isSystemAppItem(item: FileItem): Boolean {
    val t = item.type.lowercase(Locale.ROOT)
    return t.contains("aplicativo • sistema") || t.contains("sistema atualizado")
}

private fun sensitiveAppTag(appName: String, packageName: String): String? {
    val text = (appName + " " + packageName).lowercase(Locale.ROOT)

    return when {
        listOf("nubank", "banco", "bank", "caixa", "bradesco", "itau", "itaú", "santander", "inter", "picpay", "mercadopago", "paypal", "pagbank", "c6bank").any { text.contains(it) } ->
            "SENSÍVEL: financeiro"

        listOf("gov", "gov.br", "receita", "detran", "inss", "fgts", "serpro").any { text.contains(it) } ->
            "SENSÍVEL: governo/documentos"

        listOf("authenticator", "autenticador", "auth", "otp", "2fa", "duo").any { text.contains(it) } ->
            "SENSÍVEL: autenticação"

        listOf("whatsapp", "telegram", "signal", "gmail", "outlook", "instagram", "facebook").any { text.contains(it) } ->
            "SENSÍVEL: conversas/contas"

        listOf("drive", "onedrive", "dropbox", "mega", "photos", "fotos").any { text.contains(it) } ->
            "SENSÍVEL: nuvem/arquivos"

        else -> null
    }
}

private fun launchInstalledApp(item: FileItem) {
    val packageName = appPackage(item)

    try {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            return
        }

        Toast.makeText(this, "Este app não possui tela inicial. Abrindo detalhes.", Toast.LENGTH_LONG).show()
        openAppDetails(packageName)
    } catch (_: Exception) {
        Toast.makeText(this, "Não foi possível abrir este aplicativo. Abrindo detalhes.", Toast.LENGTH_LONG).show()
        openAppDetails(packageName)
    }
}

private fun openAppDetails(packageName: String) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            setData(Uri.fromParts("package", packageName, null))
        }
        startActivity(intent)
    } catch (_: Exception) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun handleUninstallResult(intent: Intent?) {
    try {
        if (intent == null) {
            Toast.makeText(this, "Retorno de desinstalação vazio.", Toast.LENGTH_LONG).show()
            return
        }

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

                if (confirmIntent != null) {
                    try {
                        startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(this, "O Android bloqueou a tela de confirmação da desinstalação.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "O Android pediu confirmação, mas não retornou a tela para abrir.", Toast.LENGTH_LONG).show()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "Aplicativo desinstalado.", Toast.LENGTH_LONG).show()
                selectedFiles.clear()
                updateSelectedInfo()
                if (currentCategory.isNotBlank()) {
                    showCategory(currentCategory, "")
                }
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Não foi possível desinstalar este aplicativo."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    } catch (_: Exception) {
        Toast.makeText(this, "Não foi possível concluir a desinstalação.", Toast.LENGTH_LONG).show()
    }
}

private fun launchAndroidUninstall(packageName: String) {
    try {
        val callbackIntent = Intent(this, CleanupSimpleActivity::class.java).apply {
            action = "br.com.monitorarmazenamentomemoria.UNINSTALL_RESULT"
            putExtra("uninstall_package", packageName)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

        val sender = PendingIntent.getActivity(
            this,
            packageName.hashCode(),
            callbackIntent,
            flags
        ).intentSender

        packageManager.packageInstaller.uninstall(packageName, sender)
        return
    } catch (_: Exception) {
    }

    try {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.fromParts("package", packageName, null)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        startActivity(intent)
        return
    } catch (_: Exception) {
    }

    try {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        return
    } catch (_: Exception) {
    }

    openAppDetails(packageName)
}

private fun confirmUninstallApp(item: FileItem) {
    val packageName = appPackage(item)
    val tag = sensitiveAppTag(item.name, packageName)

    if (isSystemAppItem(item)) {
        AlertDialog.Builder(this)
            .setTitle("App do sistema")
            .setMessage(
                "Este aplicativo parece ser do sistema ou protegido pelo fabricante.\n\n" +
                "O Android normalmente não permite desinstalar diretamente.\n\n" +
                "Você pode abrir os detalhes oficiais para desativar, limpar dados ou remover atualizações, se o Android permitir."
            )
            .setPositiveButton("ABRIR DETALHES") { _, _ ->
                openAppDetails(packageName)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
        return
    }

    if (tag != null) {
        AlertDialog.Builder(this)
            .setTitle("Aplicativo sensível")
            .setMessage(
                "Atenção: ${item.name}\n\n" +
                "$tag\n\n" +
                "Desinstalar este app pode remover acesso, dados, notificações, contas ou configurações importantes.\n\n" +
                "Deseja continuar?"
            )
            .setPositiveButton("CONTINUAR") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Última confirmação")
                    .setMessage(
                        "Confirme novamente:\n\n" +
                        "Você realmente quer iniciar a desinstalação de ${item.name}?\n\n" +
                        "Depois disso, o Android pode mostrar a confirmação oficial."
                    )
                    .setPositiveButton("SIM, DESINSTALAR") { _, _ ->
                        launchAndroidUninstall(packageName)
                    }
                    .setNegativeButton("CANCELAR", null)
                    .show()
            }
            .setNegativeButton("CANCELAR", null)
            .show()
        return
    }

    AlertDialog.Builder(this)
        .setTitle("Confirmar desinstalação")
        .setMessage(
            "Aplicativo: ${item.name}\n" +
            "Pacote: $packageName\n\n" +
            "Deseja iniciar a desinstalação deste aplicativo?"
        )
        .setPositiveButton("DESINSTALAR") { _, _ ->
            launchAndroidUninstall(packageName)
        }
        .setNegativeButton("CANCELAR", null)
        .show()
}

private fun showAppOptions(item: FileItem) {
    val packageName = appPackage(item)
    val tag = sensitiveAppTag(item.name, packageName)
    val systemApp = isSystemAppItem(item)

    val message = buildString {
        append("Pacote: ")
        append(packageName)

        if (tag != null) {
            append("\n\n")
            append(tag)
        }

        if (systemApp) {
            append("\n\nApp do sistema: desinstalação direta bloqueada pelo Android.")
        }

        append("\n\nEscolha uma ação:")
    }

    val builder = android.app.AlertDialog.Builder(this)
        .setTitle(item.name)
        .setMessage(message)
        .setPositiveButton("DETALHES DO APP") { _, _ ->
            openAppDetails(packageName)
        }
        .setNegativeButton("CANCELAR", null)

    if (!systemApp) {
        builder.setNeutralButton("DESINSTALAR") { _, _ ->
            confirmUninstallApp(item)
        }
    }

    builder.show()
}

private fun openFile(item: FileItem) {
        if (isAppItem(item)) {
      showAppOptions(item)
      return
  }

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

    private fun isAppItem(item: FileItem): Boolean {
        return item.path.startsWith("app:")
    }

    private fun installedAppItems(): List<FileItem> {
        return try {
            @Suppress("DEPRECATION")
            val packages = packageManager.getInstalledPackages(0)

            data class AppDraft(
                val appName: String,
                val packageName: String,
                val appKind: String,
                val version: String,
                val sensitiveTag: String?,
                val detectedSize: Long,
                val modified: Long
            )

            val drafts = packages.mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null

                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                val appKind = when {
                    isSystem && !isUpdatedSystem -> "sistema"
                    isUpdatedSystem -> "sistema atualizado"
                    else -> "baixado"
                }

                val appName = try {
                    appInfo.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    pkg.packageName
                }

                val sourceFiles = mutableListOf<File>()

                try {
                    val src = appInfo.sourceDir
                    if (!src.isNullOrBlank()) sourceFiles.add(File(src))
                } catch (_: Exception) {
                }

                try {
                    val pub = appInfo.publicSourceDir
                    if (!pub.isNullOrBlank()) sourceFiles.add(File(pub))
                } catch (_: Exception) {
                }

                try {
                    appInfo.splitSourceDirs?.forEach { split ->
                        if (!split.isNullOrBlank()) sourceFiles.add(File(split))
                    }
                } catch (_: Exception) {
                }

                val detectedSize = sourceFiles
                    .distinctBy { it.absolutePath }
                    .sumOf { f -> if (f.exists() && f.isFile) f.length() else 0L }

                val version = pkg.versionName ?: "versão não informada"
                val sensitiveTag = sensitiveAppTag(appName, pkg.packageName)

                AppDraft(
                    appName = appName,
                    packageName = pkg.packageName,
                    appKind = appKind,
                    version = version,
                    sensitiveTag = sensitiveTag,
                    detectedSize = detectedSize,
                    modified = pkg.lastUpdateTime
                )
            }.sortedWith(
                compareBy<AppDraft> {
                    when (it.appKind) {
                        "baixado" -> 0
                        "sistema atualizado" -> 1
                        else -> 2
                    }
                }.thenByDescending { it.detectedSize }.thenBy { it.appName.lowercase(Locale.ROOT) }
            )

            /*
             * N05H_FIX1_modo_leve_tamanho_real_apps
             *
             * O teste mostrou que StorageStatsManager funciona, mas consultar todos os
             * aplicativos de uma vez deixa a tela pesada e pode causar ANR.
             *
             * Correção segura:
             * - lista todos os apps normalmente;
             * - consulta App/Dados/Cache/Total somente para poucos apps mais relevantes;
             * - mantém o tamanho detectado para o restante;
             * - evita travar a rolagem;
             * - não limpa cache, não apaga dados, não usa root/ADB/Shizuku.
             */
            val usageAllowed = AppStorageStatsHelper.hasUsageAccess(this)
            val realStatsByPackage = mutableMapOf<String, AppStorageStatsHelper.AppStorageStats>()

            if (usageAllowed) {
                val realStatsLimit = 15

                val candidates = drafts
                    .filter { it.appKind != "sistema" }
                    .take(realStatsLimit)

                for (draft in candidates) {
                    val stats = AppStorageStatsHelper.query(this, draft.packageName)
                    if (stats != null) {
                        realStatsByPackage[draft.packageName] = stats
                    }
                }
            }

            drafts.map { draft ->
                val androidStats = realStatsByPackage[draft.packageName]
                val size = androidStats?.totalBytes ?: draft.detectedSize

                val baseTypeText = if (draft.sensitiveTag != null) {
                    "Aplicativo • ${draft.appKind} • ${draft.sensitiveTag} • versão ${draft.version} • pacote ${draft.packageName}"
                } else {
                    "Aplicativo • ${draft.appKind} • versão ${draft.version} • pacote ${draft.packageName}"
                }

                val sizeTypeText = if (androidStats != null) {
                    baseTypeText + "\nAndroid: App ${formatSize(androidStats.appBytes)} • Dados ${formatSize(androidStats.dataBytes)} • Cache ${formatSize(androidStats.cacheBytes)} • Total ${formatSize(androidStats.totalBytes)}"
                } else if (usageAllowed) {
                    baseTypeText + "\nTamanho detectado: ${formatSize(draft.detectedSize)} • modo leve: total real será priorizado nos maiores apps"
                } else {
                    baseTypeText + "\nTamanho detectado: ${formatSize(draft.detectedSize)} • total real depende do Acesso ao uso"
                }

                val riskLevel = if (draft.sensitiveTag != null) "alto" else "normal"

                FileItem(
                    name = draft.appName,
                    path = "app:${draft.packageName}",
                    size = size,
                    modified = draft.modified,
                    type = sizeTypeText,
                    category = "Aplicativos",
                    risk = riskLevel
                )
            }.sortedWith(
                compareBy<FileItem> {
                    when {
                        it.type.contains("Aplicativo • baixado") -> 0
                        it.type.contains("sistema atualizado") -> 1
                        else -> 2
                    }
                }.thenByDescending { it.size }.thenBy { it.name.lowercase(Locale.ROOT) }
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

private fun categoryFiles(title: String): List<FileItem> {
          fun lowerName(item: FileItem): String = item.name.lowercase(Locale.ROOT)
          fun lowerPath(item: FileItem): String = item.path.lowercase(Locale.ROOT)

          fun extensionOf(item: FileItem): String {
              val n = lowerName(item)
              val dot = n.lastIndexOf(".")
              return if (dot >= 0 && dot < n.length - 1) n.substring(dot + 1) else ""
          }

          fun isImage(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".jpg") ||
                  n.endsWith(".jpeg") ||
                  n.endsWith(".png") ||
                  n.endsWith(".webp") ||
                  n.endsWith(".gif") ||
                  n.endsWith(".bmp") ||
                  n.endsWith(".heic") ||
                  n.endsWith(".heif")
          }

          fun isVideo(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".mp4") ||
                  n.endsWith(".mkv") ||
                  n.endsWith(".mov") ||
                  n.endsWith(".avi") ||
                  n.endsWith(".3gp") ||
                  n.endsWith(".webm")
          }

          fun isAudio(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".opus") ||
                  n.endsWith(".mp3") ||
                  n.endsWith(".m4a") ||
                  n.endsWith(".wav") ||
                  n.endsWith(".aac") ||
                  n.endsWith(".ogg") ||
                  n.endsWith(".flac")
          }

          fun isDocument(item: FileItem): Boolean {
              val n = lowerName(item)
              return n.endsWith(".pdf") ||
                  n.endsWith(".doc") ||
                  n.endsWith(".docx") ||
                  n.endsWith(".xls") ||
                  n.endsWith(".xlsx") ||
                  n.endsWith(".ppt") ||
                  n.endsWith(".pptx") ||
                  n.endsWith(".txt") ||
                  n.endsWith(".zip") ||
                  n.endsWith(".rar") ||
                  n.endsWith(".7z") ||
                  n.endsWith(".csv") ||
                  n.endsWith(".xml") ||
                  n.endsWith(".json")
          }

          fun isApk(item: FileItem): Boolean {
              return lowerName(item).endsWith(".apk")
          }

          fun isDownload(item: FileItem): Boolean {
              val p = lowerPath(item)
              return p.contains("/download") ||
                  p.contains("/downloads") ||
                  p.contains("/baixados")
          }

          fun isWhatsapp(item: FileItem): Boolean {
              val p = lowerPath(item)
              return p.contains("whatsapp") ||
                  p.contains("com.whatsapp") ||
                  p.contains("com.whatsapp.w4b")
          }

          fun isWhatsappBackup(item: FileItem): Boolean {
              val n = lowerName(item)
              val p = lowerPath(item)
              return isWhatsapp(item) && (
                  n.contains("msgstore") ||
                  n.contains("wa.db") ||
                  p.contains("/databases/") ||
                  n.endsWith(".crypt") ||
                  n.endsWith(".crypt12") ||
                  n.endsWith(".crypt14") ||
                  n.endsWith(".crypt15") ||
                  n.endsWith(".db") ||
                  n.endsWith(".sqlite") ||
                  n.endsWith(".backup") ||
                  n.endsWith(".bak")
              )
          }

          fun isWhatsappImage(item: FileItem): Boolean = isWhatsapp(item) && isImage(item)
          fun isWhatsappVideo(item: FileItem): Boolean = isWhatsapp(item) && isVideo(item)
          fun isWhatsappAudio(item: FileItem): Boolean = isWhatsapp(item) && isAudio(item)

          fun isWhatsappDocument(item: FileItem): Boolean {
              return isWhatsapp(item) &&
                  !isWhatsappImage(item) &&
                  !isWhatsappVideo(item) &&
                  !isWhatsappAudio(item) &&
                  !isWhatsappBackup(item) &&
                  isDocument(item)
          }

          fun isBackup(item: FileItem): Boolean {
              val n = lowerName(item)
              val p = lowerPath(item)
              return n.endsWith(".db") ||
                  n.endsWith(".sqlite") ||
                  n.contains("crypt") ||
                  n.endsWith(".bak") ||
                  n.endsWith(".backup") ||
                  p.contains("/backup") ||
                  p.contains("/backups") ||
                  p.contains("/databases/")
          }

          fun isCache(item: FileItem): Boolean {
              val n = lowerName(item)
              val p = lowerPath(item)
              return p.contains("/cache/") ||
                  p.endsWith("/cache") ||
                  p.contains("/caches/") ||
                  p.contains("/code_cache/") ||
                  p.contains("/app_webview/cache/") ||
                  p.contains("/temporary/") ||
                  p.contains("/temp/") ||
                  p.contains("/tmp/") ||
                  n.endsWith(".tmp") ||
                  n.endsWith(".temp") ||
                  n.endsWith(".cache") ||
                  n.endsWith(".log") ||
                  n.endsWith(".nomedia")
          }

          fun isTemp(item: FileItem): Boolean {
              val n = lowerName(item)
              val p = lowerPath(item)
              return p.contains("/temporary/") ||
                  p.contains("/temp/") ||
                  p.contains("/tmp/") ||
                  n.endsWith(".tmp") ||
                  n.endsWith(".temp") ||
                  n.startsWith("tmp") ||
                  n.contains(".temp.")
          }

          fun isThumbnail(item: FileItem): Boolean {
              val n = lowerName(item)
              val p = lowerPath(item)
              return p.contains("/.thumbnails/") ||
                  p.contains("/thumbnails/") ||
                  p.contains("/thumb/") ||
                  p.contains("/thumbs/") ||
                  n.contains("thumb") ||
                  n.contains("thumbnail")
          }

          fun isWhatsappCache(item: FileItem): Boolean {
              return isWhatsapp(item) && (isCache(item) || isTemp(item) || isThumbnail(item))
          }

          fun appIsSystemUpdated(item: FileItem): Boolean {
              val type = item.type.lowercase(Locale.ROOT)
              return type.contains("sistema atualizado") || type.contains("atualizado")
          }

          fun lastUsedPackages(): Map<String, Long> {
              return try {
                  val usageStatsManager = getSystemService(android.app.usage.UsageStatsManager::class.java)
                      ?: return emptyMap()

                  val now = System.currentTimeMillis()
                  val start = now - (180L * 24L * 60L * 60L * 1000L)

                  val stats = usageStatsManager.queryUsageStats(
                      android.app.usage.UsageStatsManager.INTERVAL_BEST,
                      start,
                      now
                  ) ?: return emptyMap()

                  stats.groupBy { it.packageName }
                      .mapValues { entry -> entry.value.maxOfOrNull { it.lastTimeUsed } ?: 0L }
              } catch (_: Exception) {
                  emptyMap()
              }
          }

          fun duplicateCandidates(kind: String): List<FileItem> {
              val base = allFiles
                  .asSequence()
                  .filter { it.size > 10L * 1024L }
                  .filter { !it.path.startsWith("app:") }
                  .filter {
                      when (kind) {
                          "image" -> isImage(it)
                          "video" -> isVideo(it)
                          "audio" -> isAudio(it)
                          "document" -> isDocument(it)
                          else -> true
                      }
                  }
                  .filter { extensionOf(it).isNotBlank() }
                  .sortedByDescending { it.size }
                  .take(8000)
                  .toList()

              val grouped = base.groupBy { "${it.size}|${extensionOf(it)}" }

              return grouped.values
                  .asSequence()
                  .filter { it.size >= 2 }
                  .flatMap { group ->
                      group.sortedByDescending { it.size }.take(20).asSequence()
                  }
                  .distinctBy { it.path }
                  .sortedWith(compareByDescending<FileItem> { it.size }.thenBy { it.name.lowercase(Locale.ROOT) })
                  .take(500)
                  .toList()
          }

          val now = System.currentTimeMillis()
          val last24h = now - (24L * 60L * 60L * 1000L)
          val whatsappAll = allFiles.filter { isWhatsapp(it) }

          return when (title) {
              "Lixeira" -> emptyList()
              "Novos nas últimas 24h" -> allFiles.filter { it.modified >= last24h }
              "Últimos modificados" -> allFiles.sortedByDescending { it.modified }.take(200)
              "Recentes" -> allFiles.sortedByDescending { it.modified }.take(200)

              "Arquivos grandes" -> allFiles.filter { it.size >= minSizeMb * 1024L * 1024L }

              "Vídeos" -> allFiles.filter { isVideo(it) }
              "Imagens" -> allFiles.filter { isImage(it) }
              "Áudios" -> allFiles.filter { isAudio(it) }

              "WhatsApp" -> whatsappAll
              "Todos do WhatsApp" -> whatsappAll
              "Fotos do WhatsApp" -> allFiles.filter { isWhatsappImage(it) }
              "Vídeos do WhatsApp" -> allFiles.filter { isWhatsappVideo(it) }
              "Documentos do WhatsApp" -> allFiles.filter { isWhatsappDocument(it) }
              "Áudios do WhatsApp" -> allFiles.filter { isWhatsappAudio(it) }
              "Backups do WhatsApp" -> allFiles.filter { isWhatsappBackup(it) }

              "Duplicados" -> duplicateCandidates("all")
              "Arquivos duplicados" -> duplicateCandidates("all")
              "Imagens duplicadas" -> duplicateCandidates("image")
              "Vídeos duplicados" -> duplicateCandidates("video")
              "Áudios duplicados" -> duplicateCandidates("audio")
              "Documentos duplicados" -> duplicateCandidates("document")

              "Cache e temporários" -> allFiles.filter { isCache(it) || isTemp(it) || isThumbnail(it) }
              "Cache acessível" -> allFiles.filter { isCache(it) }
              "Temporários" -> allFiles.filter { isTemp(it) }
              "Miniaturas" -> allFiles.filter { isThumbnail(it) }
              "Cache do WhatsApp" -> allFiles.filter { isWhatsappCache(it) }

              "Backups" -> allFiles.filter { isBackup(it) && !isWhatsapp(it) }
              "Sensíveis" -> allFiles.filter { it.risk == "alto" }
              "Downloads" -> allFiles.filter { isDownload(it) }
              "Baixados" -> allFiles.filter { isDownload(it) }

              "Aplicativos" -> {
                  val apps = installedAppItems()
                  when (appFilterMode) {
                      "Baixados" -> apps.filter { !isSystemAppItem(it) }
                      "Sistema" -> apps.filter { isSystemAppItem(it) && !appIsSystemUpdated(it) }
                      "Sistema atualizado" -> apps.filter { isSystemAppItem(it) && appIsSystemUpdated(it) }
                      "Não utilizados" -> {
                          val lastUsed = lastUsedPackages()
                          val downloaded = apps.filter { !isSystemAppItem(it) }

                          if (lastUsed.isNotEmpty()) {
                              val cutoff = now - (60L * 24L * 60L * 60L * 1000L)
                              downloaded.filter {
                                  ((lastUsed[appPackage(it)] ?: 0L) < cutoff)
                              }
                          } else {
                              downloaded
                                  .sortedBy { it.modified }
                                  .take(80)
                          }
                      }
                      else -> apps
                  }
              }

              "APKs" -> allFiles.filter { it.category == "APKs" || isApk(it) }
              "Outros" -> allFiles.filter { it.category == "Outros" }
              else -> allFiles.filter { it.category == title }
          }.distinctBy { it.path }
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

    private fun drawConfig() {
        removeFloatingListNav()
        currentCategory = ""
        categoryDisplayLimit = 40
        root.removeAllViews()

        val title = TextView(this)
        title.text = "Configurações"
        title.textSize = 28f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(10, 18, 36))
        root.addView(title)

        val subtitle = TextView(this)
        subtitle.text = "Ajustes e informações do aplicativo."
        subtitle.textSize = 15f
        subtitle.setTextColor(Color.rgb(80, 90, 110))
        subtitle.setPadding(0, dp(4), 0, dp(14))
        root.addView(subtitle)



        val appCard = card()
        appCard.addView(titleText("Aplicativo"))

        val appText = TextView(this)
        appText.text =
            "Use esta área para acessar ajustes do Android relacionados ao app, permissões e armazenamento."
        appText.textSize = 15f
        appText.setTextColor(Color.rgb(70, 80, 100))
        appText.setPadding(0, dp(8), 0, dp(12))
        appCard.addView(appText)

        val openSettings = Button(this)
        openSettings.text = "CONFIGURAÇÕES DO APP"
        openSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    setData(Uri.fromParts("package", packageName, null))
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        appCard.addView(openSettings)

        root.addView(appCard)

        val storageCard = card()
        storageCard.addView(titleText("Armazenamento"))

        val storageText = TextView(this)
        storageText.text = "Abra as configurações de armazenamento do Android para conferir o uso geral do aparelho."
        storageText.textSize = 15f
        storageText.setTextColor(Color.rgb(70, 80, 100))
        storageText.setPadding(0, dp(8), 0, dp(12))
        storageCard.addView(storageText)

        val openStorage = Button(this)
        openStorage.text = "ARMAZENAMENTO DO ANDROID"
        openStorage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        storageCard.addView(openStorage)

        root.addView(storageCard)

        val monitorCard = card()
        monitorCard.addView(titleText("Monitor discreto no topo"))

        val monitorText = TextView(this)
        monitorText.text = if (cleanupN02MonitorEnabled()) {
            "Ativado. Livre primeiro, RAM em GB e hora. Fixo na tela; desligue somente por aqui."
        } else {
            "Desligado. Toque no botão abaixo para ativar."
        }
        monitorText.textSize = 15f
        monitorText.setTextColor(Color.rgb(70, 80, 100))
        monitorText.setPadding(0, dp(8), 0, dp(12))
        monitorCard.addView(monitorText)

        val monitorButton = Button(this)
        monitorButton.text = if (cleanupN02MonitorEnabled()) "Desligar monitor discreto" else "Ligar monitor discreto"
        cleanupN03XStyleButton(monitorButton, selected = false, danger = cleanupN02MonitorEnabled())
        monitorButton.setOnClickListener {
            cleanupN02SetMonitorEnabled(!cleanupN02MonitorEnabled())
        }
        monitorCard.addView(monitorButton)

        root.addView(monitorCard)
        cleanupCARDS03AddSmartAlertsCard(root)
        cleanupN03TAddNotificationOptionsCard(root)
        cleanupN03ZForcePremiumConfigVisual(root)


    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
          if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
              if (navigateBackInCleanup()) {
                  return true
              }
          }

          return super.onKeyDown(keyCode, event)
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

        nav.addView(navItem("✦\nLimpeza", true) {
            drawHome()
        })

        nav.addView(navItem("⚙\nConfig.", false) {
            drawConfig()
        })

        nav.addView(navItem("↑", false) {
            val scrollView = root.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.smoothScrollTo(0, 0)
            }
        })

        nav.addView(navItem("↓", false) {
            val scrollView = root.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.smoothScrollTo(0, root.height)
            }
        })
        return nav
    }

    private fun navItem(textValue: String, active: Boolean, action: () -> Unit): TextView {
        val isArrow = textValue == "↑" || textValue == "↓"

        return TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = if (isArrow) 24f else 12f
            setTypeface(null, if (active || isArrow) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                if (isArrow) {
                    Color.rgb(14, 26, 56)
                } else if (active) {
                    Color.rgb(42, 92, 255)
                } else {
                    Color.rgb(80, 90, 110)
                }
            )
            setPadding(0, dp(6), 0, dp(6))
            background = when {
                active -> rounded(
                    Color.rgb(230, 244, 255),
                    Color.TRANSPARENT,
                    dp(18)
                )
                isArrow -> rounded(
                    Color.rgb(248, 250, 253),
                    Color.rgb(180, 190, 210),
                    dp(18)
                )
                else -> null
            }
            isClickable = true
            isFocusable = true
            minHeight = dp(54)
            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(58),
                if (isArrow) 0.72f else 1f
            ).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
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

    // T22 - Camada de segurança para exclusão de arquivos.
    // Objetivo: impedir que app pseudo path, raiz, pasta perigosa ou item inexistente seja apagado por engano.



    // T25 - Funcoes consolidadas de seguranca, textos e revisao visual leve.
    private fun cleanupT25IsPseudoAppPath(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.startsWith("app:")
    }

    private fun cleanupT25IsDangerousDeleteTarget(file: java.io.File?): Boolean {
        if (file == null) return true

        val rawPath = file.path.trim()
        val absolutePath = try {
            file.absolutePath.trim()
        } catch (_: Throwable) {
            rawPath
        }

        if (rawPath.isEmpty() || absolutePath.isEmpty()) return true
        if (cleanupT25IsPseudoAppPath(rawPath) || cleanupT25IsPseudoAppPath(absolutePath)) return true

        val normalized = absolutePath.removeSuffix("/")

        if (normalized == "/") return true
        if (normalized == "/storage") return true
        if (normalized == "/storage/emulated") return true
        if (normalized == "/storage/emulated/0") return true
        if (normalized == "/sdcard") return true
        if (normalized == "/mnt") return true
        if (normalized == "/data") return true
        if (normalized == "/system") return true
        if (normalized == "/vendor") return true
        if (normalized == "/product") return true
        if (normalized == "/apex") return true
        if (normalized == "/proc") return true
        if (normalized == "/dev") return true
        if (normalized == "/cache") return true

        if (!file.exists()) return true

        val allowedUserStorage =
            normalized.startsWith("/storage/emulated/0/") ||
            normalized.startsWith("/sdcard/") ||
            normalized.startsWith(filesDir.absolutePath + "/") ||
            normalized.startsWith(cacheDir.absolutePath + "/")

        if (!allowedUserStorage) return true

        return false
    }

    private fun cleanupT25SafeDeleteFile(file: java.io.File?): Boolean {
        if (cleanupT25IsDangerousDeleteTarget(file)) return false
        val safeFile = file ?: return false

        return try {
            if (!safeFile.isFile) {
                false
            } else {
                safeFile.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun cleanupT25SafeDeleteRecursively(file: java.io.File?): Boolean {
        if (cleanupT25IsDangerousDeleteTarget(file)) return false
        val safeFile = file ?: return false

        return try {
            if (safeFile.isFile) {
                safeFile.delete()
            } else if (safeFile.isDirectory) {
                val children = safeFile.listFiles()
                if (children != null) {
                    for (child in children) {
                        if (cleanupT25IsDangerousDeleteTarget(child)) return false
                    }
                }
                safeFile.deleteRecursively()
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun cleanupT25FormatBytes(bytes: Long): String {
        val safeBytes = if (bytes < 0L) 0L else bytes
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val tb = gb * 1024.0

        return when {
            safeBytes >= tb -> String.format(java.util.Locale("pt", "BR"), "%.2f TB", safeBytes / tb)
            safeBytes >= gb -> String.format(java.util.Locale("pt", "BR"), "%.2f GB", safeBytes / gb)
            safeBytes >= mb -> String.format(java.util.Locale("pt", "BR"), "%.2f MB", safeBytes / mb)
            safeBytes >= kb -> String.format(java.util.Locale("pt", "BR"), "%.2f KB", safeBytes / kb)
            else -> "$safeBytes B"
        }
    }

    private fun cleanupT25BuildDeleteSummary(files: List<java.io.File>): String {
        val validFiles = files.filter { !cleanupT25IsDangerousDeleteTarget(it) && it.isFile }
        val blockedCount = files.size - validFiles.size

        val totalSize = validFiles.sumOf {
            try {
                it.length()
            } catch (_: Throwable) {
                0L
            }
        }

        val previewLimit = 8
        val preview = validFiles.take(previewLimit).joinToString(separator = "\n") { file ->
            val name = file.name.ifBlank { file.absolutePath }
            val size = try {
                cleanupT25FormatBytes(file.length())
            } catch (_: Throwable) {
                "tamanho desconhecido"
            }
            "• $name — $size"
        }

        val remaining = validFiles.size - validFiles.take(previewLimit).size

        return buildString {
            append("Arquivos selecionados: ${validFiles.size}\n")
            append("Espaço estimado: ${cleanupT25FormatBytes(totalSize)}\n")

            if (blockedCount > 0) {
                append("Itens bloqueados por segurança: $blockedCount\n")
            }

            if (preview.isNotBlank()) {
                append("\nPrévia do que será apagado:\n")
                append(preview)
                if (remaining > 0) {
                    append("\n• +$remaining item(ns) não exibido(s) na prévia")
                }
            }

            append("\n\nEssa ação tentará apagar somente arquivos permitidos do armazenamento do aparelho.")
            append("\nDepois de apagados, eles podem não ser recuperáveis.")
            append("\n\nDeseja continuar?")
        }
    }

    private fun cleanupT25CategoryNotice(title: String): String {
        val t = title.trim().lowercase(java.util.Locale.ROOT)

        return when {
            t.contains("duplicado") -> "Aviso: duplicados são sugestões encontradas por comparação leve. Confira antes de apagar."
            t.contains("cache") || t.contains("tempor") || t.contains("miniatura") -> "Aviso: o Android limita a limpeza de cache interno de outros apps. Aqui aparecem apenas itens acessíveis com segurança."
            t.contains("whatsapp") -> "Aviso: confira arquivos do WhatsApp antes de apagar, principalmente fotos, vídeos, documentos e backups."
            t.contains("sens") -> "Aviso: arquivos sensíveis podem conter documentos, comprovantes ou informações pessoais. Revise com cuidado."
            t.contains("apk") -> "Aviso: APKs são instaladores. Apagar APK não desinstala o aplicativo já instalado."
            t.contains("download") || t.contains("baixado") -> "Aviso: a pasta de downloads pode ter boletos, contratos, fotos e documentos importantes."
            else -> ""
        }
    }

    private fun cleanupT25EmptyMessage(title: String): String {
        val t = title.trim().lowercase(java.util.Locale.ROOT)

        return when {
            t.contains("apk") -> "Nenhum instalador APK encontrado nesta análise."
            t.contains("sens") -> "Nenhum arquivo sensível encontrado nesta análise."
            t.contains("duplicado") -> "Nenhum possível duplicado encontrado nesta análise."
            t.contains("cache") || t.contains("tempor") -> "Nenhum cache ou arquivo temporário acessível encontrado nesta análise."
            t.contains("whatsapp") && t.contains("foto") -> "Nenhuma foto do WhatsApp encontrada nesta análise."
            t.contains("whatsapp") && t.contains("vídeo") -> "Nenhum vídeo do WhatsApp encontrado nesta análise."
            t.contains("whatsapp") && t.contains("document") -> "Nenhum documento do WhatsApp encontrado nesta análise."
            t.contains("whatsapp") && t.contains("áudio") -> "Nenhum áudio do WhatsApp encontrado nesta análise."
            t.contains("whatsapp") && t.contains("backup") -> "Nenhum backup do WhatsApp encontrado nesta análise."
            t.contains("whatsapp") -> "Nenhum arquivo do WhatsApp encontrado nesta análise."
            t.contains("imagem") || t.contains("foto") -> "Nenhuma imagem encontrada nesta análise."
            t.contains("vídeo") -> "Nenhum vídeo encontrado nesta análise."
            t.contains("áudio") -> "Nenhum áudio encontrado nesta análise."
            t.contains("download") || t.contains("baixado") -> "Nenhum arquivo baixado encontrado nesta análise."
            t.contains("app") || t.contains("aplicativo") -> "Nenhum aplicativo encontrado para este filtro."
            else -> "Nenhum item encontrado nesta análise."
        }
    }

    private fun cleanupT25AppFilterNotice(title: String): String {
        val t = title.trim().lowercase(java.util.Locale.ROOT)

        return when {
            t.contains("não utilizado") || t.contains("nao utilizado") -> "Aplicativos não utilizados são estimativas. O Android pode limitar o acesso ao histórico de uso."
            t.contains("sistema atualizado") -> "Apps de sistema atualizados vieram de fábrica, mas receberam atualização depois. A remoção pode afetar funções do aparelho."
            t.contains("sistema") -> "Apps de sistema são protegidos pelo Android. Em geral, não devem ser removidos."
            t.contains("baixado") -> "Apps baixados normalmente foram instalados pelo usuário e podem ser desinstalados pelo Android."
            else -> ""
        }
    }









    private fun cleanupN03EOpenTrashShortcut() {
        val intents = mutableListOf<android.content.Intent>()

        intents.add(android.content.Intent("com.sec.android.app.myfiles.OPEN_TRASH").apply {
            setPackage("com.sec.android.app.myfiles")
        })

        intents.add(android.content.Intent("android.intent.action.VIEW").apply {
            setPackage("com.sec.android.app.myfiles")
        })

        packageManager.getLaunchIntentForPackage("com.sec.android.app.myfiles")?.let {
            intents.add(it)
        }

        packageManager.getLaunchIntentForPackage("com.google.android.documentsui")?.let {
            intents.add(it)
        }

        for (intent in intents) {
            try {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                android.widget.Toast.makeText(
                    this,
                    "Abrindo Lixeira/Meus Arquivos",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            } catch (_: Throwable) {
            }
        }

        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        } catch (_: Throwable) {
            android.widget.Toast.makeText(
                this,
                "Não foi possível abrir a Lixeira neste aparelho",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }


        private fun cleanupN01StartPremiumStatusNotification() {
        try {
            if (!cleanupN02MonitorEnabled()) return

            if (
                android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9101)
                return
            }

            PremiumStatusNotificationService.start(this)
        } catch (_: Throwable) {
            Toast.makeText(this, "Não foi possível iniciar o monitor", Toast.LENGTH_SHORT).show()
        }
    }


        private fun cleanupN02MonitorEnabled(): Boolean {
        return getSharedPreferences("monitor_premium", MODE_PRIVATE)
            .getBoolean("top_monitor_enabled", false)
    }


        private fun cleanupN02SetMonitorEnabled(enabled: Boolean) {
        getSharedPreferences("monitor_premium", MODE_PRIVATE)
            .edit()
            .putBoolean("top_monitor_enabled", enabled)
            .apply()

        if (enabled) {
            cleanupN01StartPremiumStatusNotification()
            Toast.makeText(this, "Monitor discreto ativado", Toast.LENGTH_SHORT).show()
        } else {
            PremiumStatusNotificationService.stop(this)
            Toast.makeText(this, "Monitor discreto desligado", Toast.LENGTH_SHORT).show()
        }

        drawConfig()
    }




        /*
         * CARDS_03_alertas_restaurados_sem_recarregar_tela
         *
         * Recria os cards de forma leve.
         *
         * Regra importante:
         * - nao chama drawConfig() ao salvar;
         * - nao reconstrói a tela inteira;
         * - ainda nao liga alarmes reais.
         */
        private fun cleanupCARDS03AddSmartAlertsCard(root: android.widget.LinearLayout) {
            try {
                val card = card()
                card.background = cleanupN03XRoundBg("#FFFFFF", "#E3E8F1", 22, 1)
                card.setPadding(dp(18), dp(18), dp(18), dp(18))

                card.addView(android.widget.TextView(this).apply {
                    text = "Alertas Inteligentes"
                    textSize = 22f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(cleanupN03XColor("#111827"))
                    setPadding(0, 0, 0, dp(6))
                })

                card.addView(cleanupN03XDesc(
                    "Cada alerta possui ajuste próprio. O Monitor tenta verificar automaticamente a cada 15 minutos."
                ))

                card.addView(android.widget.TextView(this).apply {
                    text = SmartAlertEngine.monitorStatus(this@CleanupSimpleActivity)
                    textSize = 13f
                    setTextColor(cleanupN03XColor("#667085"))
                    setPadding(0, 0, 0, dp(8))
                })

                fun addCardButton(detector: String) {
                    val button = android.widget.Button(this)
                    button.text = SmartAlertsManager.summary(this, detector)
                    button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    button.isAllCaps = false
                    cleanupN03XStyleButton(button, selected = false, danger = false)

                    button.setOnClickListener {
                        cleanupCARDS03OpenDetectorDialog(detector, button)
                    }

                    card.addView(
                        button,
                        android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, dp(4), 0, dp(4))
                        }
                    )
                }

                addCardButton(SmartAlertsManager.DETECTOR_STORAGE_LOW)
                addCardButton(SmartAlertsManager.DETECTOR_CACHE_HIGH)
                addCardButton(SmartAlertsManager.DETECTOR_LARGE_FILE)
                addCardButton(SmartAlertsManager.DETECTOR_FAST_GROWTH)

                val masterButton = android.widget.Button(this)
                masterButton.isAllCaps = false
                masterButton.text = if (SmartAlertsManager.anyEnabled(this)) {
                    "Desativar todos os alertas"
                } else {
                    "Ativar todos os alertas"
                }

                cleanupN03XStyleButton(
                    masterButton,
                    selected = false,
                    danger = SmartAlertsManager.anyEnabled(this)
                )

                masterButton.setOnClickListener {
                    val shouldEnable = !SmartAlertsManager.anyEnabled(this)
                    SmartAlertsManager.setAllEnabled(this, shouldEnable)

                    masterButton.text = if (shouldEnable) {
                        "Desativar todos os alertas"
                    } else {
                        "Ativar todos os alertas"
                    }

                    android.widget.Toast.makeText(
                        this,
                        if (shouldEnable) "Todos os alertas foram ativados"
                        else "Todos os alertas foram desativados",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

                card.addView(
                    masterButton,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(10), 0, 0)
                    }
                )

                val testButton = android.widget.Button(this)
                testButton.text = "TESTAR ALARME AGORA"
                testButton.isAllCaps = false
                cleanupN03XStyleButton(testButton, selected = true, danger = true)

                testButton.setOnClickListener {
                    cleanupPULSO05TestAlarm()
                }

                card.addView(
                    testButton,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(8), 0, 0)
                    }
                )


                val overlayPermissionButton = android.widget.Button(this)
                overlayPermissionButton.isAllCaps = false
                overlayPermissionButton.text =
                    if (SmartAlertOverlay.canShow(this)) {
                        "ALERTAS SOBRE OUTROS APPS: PERMITIDO"
                    } else {
                        "PERMITIR ALERTAS SOBRE OUTROS APPS"
                    }

                cleanupN03XStyleButton(
                    overlayPermissionButton,
                    selected = SmartAlertOverlay.canShow(this),
                    danger = !SmartAlertOverlay.canShow(this)
                )

                overlayPermissionButton.setOnClickListener {
                    cleanupSOBREPOR06RequestOverlayPermission()
                }

                card.addView(
                    overlayPermissionButton,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(8), 0, 0)
                    }
                )

                val testOverlayButton = android.widget.Button(this)
                testOverlayButton.text = "TESTAR ALERTA SOBRE OUTROS APPS"
                testOverlayButton.isAllCaps = false
                cleanupN03XStyleButton(testOverlayButton, selected = false, danger = true)

                testOverlayButton.setOnClickListener {
                    if (!SmartAlertOverlay.canShow(this)) {
                        cleanupSOBREPOR06RequestOverlayPermission()
                    } else {
                        SmartAlertEngine.testOverlayAlarm(this)
                    }
                }

                card.addView(
                    testOverlayButton,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(8), 0, 0)
                    }
                )

                root.addView(card)
            } catch (_: Throwable) {
            }
        }


        /*
         * PULSO_05_alarme_forte
         *
         * Teste visual e sonoro imediato.
         * Funciona enquanto o app estiver aberto.
         */

        private fun cleanupSOBREPOR06RequestOverlayPermission() {
            try {
                if (SmartAlertOverlay.canShow(this)) {
                    android.widget.Toast.makeText(
                        this,
                        "Permissão para alertas sobre outros apps já está ativa",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }

                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )

                startActivity(intent)

                android.widget.Toast.makeText(
                    this,
                    "Ative “Permitir exibição sobre outros apps” e volte ao Monitor",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Throwable) {
                android.widget.Toast.makeText(
                    this,
                    "Não foi possível abrir a tela de permissão",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun cleanupPULSO05TestAlarm() {
            try {
                SmartAlertEngine.testAlarm(this)

                val box = android.widget.LinearLayout(this)
                box.orientation = android.widget.LinearLayout.VERTICAL
                box.gravity = android.view.Gravity.CENTER
                box.setPadding(dp(28), dp(34), dp(28), dp(28))

                val title = android.widget.TextView(this).apply {
                    text = "⚠ TESTE DE ALARME"
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.WHITE)
                }

                val message = android.widget.TextView(this).apply {
                    text = "Som, vibração e alerta visual ativos."
                    textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(0, dp(16), 0, dp(6))
                }

                box.addView(title)
                box.addView(message)

                val dialog = android.app.AlertDialog.Builder(this)
                    .setView(box)
                    .setCancelable(true)
                    .setPositiveButton("SILENCIAR") { _, _ ->
                        SmartAlertEngine.stopTestAlarm()
                    }
                    .setNegativeButton("FECHAR") { _, _ ->
                        SmartAlertEngine.stopTestAlarm()
                    }
                    .create()

                dialog.setOnDismissListener {
                    SmartAlertEngine.stopTestAlarm()
                }

                dialog.show()

                val window = dialog.window
                window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.rgb(180, 20, 35)
                    )
                )

                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var red = true

                val pulse = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing) return

                        val color = if (red) {
                            android.graphics.Color.rgb(185, 20, 35)
                        } else {
                            android.graphics.Color.rgb(245, 135, 20)
                        }

                        window?.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(color)
                        )

                        red = !red
                        handler.postDelayed(this, 420L)
                    }
                }

                handler.post(pulse)

            } catch (_: Throwable) {
                android.widget.Toast.makeText(
                    this,
                    "Não foi possível iniciar o teste de alarme",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun cleanupCARDS03OpenDetectorDialog(
            detector: String,
            sourceButton: android.widget.Button
        ) {
            try {
                val current = SmartAlertsManager.read(this, detector)

                val container = android.widget.LinearLayout(this)
                container.orientation = android.widget.LinearLayout.VERTICAL
                container.setPadding(dp(22), dp(8), dp(22), dp(4))

                val enabled = android.widget.CheckBox(this).apply {
                    text = "Ativar este alerta"
                    isChecked = current.enabled
                }
                container.addView(enabled)

                val label = android.widget.TextView(this).apply {
                    text = SmartAlertsManager.limitLabel(detector)
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(4))
                }
                container.addView(label)

                val limitInput = android.widget.EditText(this).apply {
                    inputType =
                        android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(SmartAlertsManager.formatLimit(current.limitGb))
                    hint = SmartAlertsManager.defaultLimitHint(detector)
                }
                container.addView(limitInput)

                val repeatTitle = android.widget.TextView(this).apply {
                    text = "Repetir alerta"
                    textSize = 14f
                    setPadding(0, dp(12), 0, dp(4))
                }
                container.addView(repeatTitle)

                val repeatSpinner = android.widget.Spinner(this)
                val options = arrayOf("Nunca", "30 min", "1 hora", "3 horas", "6 horas")
                repeatSpinner.adapter = android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    options
                )
                repeatSpinner.setSelection(
                    SmartAlertsManager.repeatIndex(current.repeatMinutes)
                )
                container.addView(repeatSpinner)

                val sound = android.widget.CheckBox(this).apply {
                    text = "Tocar som quando este alerta disparar"
                    isChecked = current.sound
                    setPadding(0, dp(12), 0, 0)
                }
                container.addView(sound)

                val vibration = android.widget.CheckBox(this).apply {
                    text = "Vibrar quando este alerta disparar"
                    isChecked = current.vibration
                }
                container.addView(vibration)

                val popup = android.widget.CheckBox(this).apply {
                    text = "Mostrar pop-up quando o app estiver aberto"
                    isChecked = current.popup
                }
                container.addView(popup)

                android.app.AlertDialog.Builder(this)
                    .setTitle(SmartAlertsManager.title(detector))
                    .setView(container)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Salvar") { _, _ ->
                        val raw = limitInput.text?.toString()
                            ?.trim()
                            ?.replace(",", ".")
                            ?: ""

                        val parsed = raw.toDoubleOrNull()

                        if (parsed == null || parsed <= 0.0) {
                            android.widget.Toast.makeText(
                                this,
                                "Informe um limite maior que zero",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }

                        SmartAlertsManager.save(
                            context = this,
                            detector = detector,
                            enabled = enabled.isChecked,
                            limitGb = SmartAlertsManager.normalizeLimit(detector, parsed),
                            sound = sound.isChecked,
                            vibration = vibration.isChecked,
                            popup = popup.isChecked,
                            repeatMinutes = SmartAlertsManager.repeatMinutesFromIndex(
                                repeatSpinner.selectedItemPosition
                            )
                        )

                        // Atualiza somente o card tocado; nao recarrega toda a tela.
                        sourceButton.text = SmartAlertsManager.summary(this, detector)

                        android.widget.Toast.makeText(
                            this,
                            "Configuração salva",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            } catch (_: Throwable) {
                android.widget.Toast.makeText(
                    this,
                    "Não foi possível abrir esse alerta",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun cleanupN03TPrefs(): android.content.SharedPreferences {
        return getSharedPreferences("monitor_premium", MODE_PRIVATE)
    }


        private fun cleanupN03TRestartMonitorIfEnabled() {
        try {
            cleanupN03TPrefs().edit().remove("notification_size").apply()
            if (cleanupN02MonitorEnabled()) {
                PremiumStatusNotificationService.start(this)
            }
        } catch (_: Throwable) {
        }
    }


            private fun cleanupN03TAddNotificationOptionsCard(root: android.widget.LinearLayout) {
        try {
            val prefs = cleanupN03TPrefs()
            prefs.edit().remove("notification_size").apply()

            val currentMode = prefs.getString("notification_mode", "detalhado") ?: "detalhado"

            val optionsCard = card()
            optionsCard.background = cleanupN03XRoundBg("#FFFFFF", "#E3E8F1", 22, 1)
            optionsCard.setPadding(dp(18), dp(18), dp(18), dp(18))

            optionsCard.addView(android.widget.TextView(this).apply {
                text = "Opções da notificação"
                textSize = 22f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(cleanupN03XColor("#111827"))
                setPadding(0, 0, 0, dp(6))
            })

            optionsCard.addView(cleanupN03XDesc(
                "Escolha como o monitor fixo aparece na tela. Compacto junta as informações. Detalhado separa com espaçamento."
            ))

            fun addCheck(label: String, key: String, defaultValue: Boolean) {
                val row = android.widget.LinearLayout(this)
                row.orientation = android.widget.LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(4), 0, dp(4))

                val check = android.widget.CheckBox(this)
                check.isChecked = prefs.getBoolean(key, defaultValue)
                try {
                    check.buttonTintList = android.content.res.ColorStateList.valueOf(cleanupN03XColor("#0F9F7A"))
                } catch (_: Throwable) {
                }

                val labelView = android.widget.TextView(this)
                labelView.text = label
                labelView.textSize = 15f
                labelView.setTextColor(cleanupN03XColor("#1F2937"))
                labelView.setPadding(dp(8), 0, 0, 0)

                row.setOnClickListener {
                    check.isChecked = !check.isChecked
                }

                check.setOnCheckedChangeListener { _, checked ->
                    prefs.edit()
                        .putBoolean(key, checked)
                        .remove("notification_size")
                        .apply()
                    cleanupN03TRestartMonitorIfEnabled()
                }

                row.addView(check)
                row.addView(labelView, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                optionsCard.addView(row)
            }

            optionsCard.addView(cleanupN03XLabel("Modo da notificação"))

            val modeRow = android.widget.LinearLayout(this)
            modeRow.orientation = android.widget.LinearLayout.HORIZONTAL
            modeRow.setPadding(0, 0, 0, dp(12))

            val compactBtn = android.widget.Button(this)
            compactBtn.text = "Compacto"
            cleanupN03XStyleButton(compactBtn, currentMode == "compacto", false)
            compactBtn.setOnClickListener {
                prefs.edit()
                    .putString("notification_mode", "compacto")
                    .remove("notification_size")
                    .apply()
                cleanupN03TRestartMonitorIfEnabled()
                drawConfig()
                android.widget.Toast.makeText(this, "Compacto aplicado", android.widget.Toast.LENGTH_SHORT).show()
            }

            val detailedBtn = android.widget.Button(this)
            detailedBtn.text = "Detalhado"
            cleanupN03XStyleButton(detailedBtn, currentMode != "compacto", false)
            detailedBtn.setOnClickListener {
                prefs.edit()
                    .putString("notification_mode", "detalhado")
                    .remove("notification_size")
                    .apply()
                cleanupN03TRestartMonitorIfEnabled()
                drawConfig()
                android.widget.Toast.makeText(this, "Detalhado aplicado", android.widget.Toast.LENGTH_SHORT).show()
            }

            val gap = android.view.View(this)
            modeRow.addView(compactBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            modeRow.addView(gap, android.widget.LinearLayout.LayoutParams(dp(10), 1))
            modeRow.addView(detailedBtn, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            optionsCard.addView(modeRow)

            optionsCard.addView(cleanupN03XLabel("Conteúdo exibido"))

            addCheck("Mostrar status OK / ATENÇÃO / CRÍTICO", "show_status", true)
            addCheck("Mostrar armazenamento livre", "show_free", true)
            addCheck("Mostrar armazenamento usado", "show_used", true)
            addCheck("Mostrar RAM", "show_ram", true)
            addCheck("Mostrar hora da atualização", "show_time", true)
            addCheck("Mostrar cache detectado", "show_cache", true)

            root.addView(optionsCard)
        } catch (_: Throwable) {
        }
    }


                private fun cleanupN03XColor(hex: String): Int {
        return android.graphics.Color.parseColor(hex)
    }


                private fun cleanupN03XRoundBg(
        fill: String,
        stroke: String,
        radiusDp: Int = 14,
        strokeDp: Int = 1
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(cleanupN03XColor(fill))
            setStroke(dp(strokeDp), cleanupN03XColor(stroke))
        }
    }


    private fun cleanupN03XStyleButton(
        button: android.widget.Button,
        selected: Boolean = false,
        danger: Boolean = false
    ) {
        if (danger) {
            button.setTextColor(cleanupN03XColor("#FFFFFF"))
            button.background = cleanupN03XRoundBg("#111827", "#111827", 14, 1)
        } else if (selected) {
            button.setTextColor(cleanupN03XColor("#FFFFFF"))
            button.background = cleanupN03XRoundBg("#111827", "#111827", 14, 1)
        } else {
            button.setTextColor(cleanupN03XColor("#111827"))
            button.background = cleanupN03XRoundBg("#FFFFFF", "#111827", 14, 1)
        }

        button.textSize = 13f
        button.setTypeface(null, android.graphics.Typeface.BOLD)
        button.setPadding(dp(10), dp(10), dp(10), dp(10))
        button.minHeight = dp(48)
        button.isAllCaps = false
    }


    private fun cleanupN03XLabel(textValue: String): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = textValue
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(cleanupN03XColor("#111827"))
            setPadding(0, dp(12), 0, dp(6))
        }
    }


    private fun cleanupN03XDesc(textValue: String): android.widget.TextView {
        return android.widget.TextView(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(cleanupN03XColor("#4B5563"))
            setLineSpacing(2f, 1.05f)
            setPadding(0, dp(4), 0, dp(12))
        }
    }


            private fun cleanupN03ZStyleButtonByText(button: android.widget.Button) {
        try {
            val label = button.text?.toString()?.trim() ?: ""
            val normalized = label.uppercase()

            val prefs = getSharedPreferences("monitor_premium", MODE_PRIVATE)
            val mode = prefs.getString("notification_mode", "detalhado") ?: "detalhado"

            val selected = when (normalized) {
                "COMPACTO" -> mode == "compacto"
                "DETALHADO" -> mode != "compacto"
                else -> false
            }

            val danger = normalized.contains("DESLIGAR MONITOR")

            when {
                selected -> {
                    button.setTextColor(cleanupN03XColor("#FFFFFF"))
                    button.background = cleanupN03XRoundBg("#111827", "#111827", 16, 1)
                }

                danger -> {
                    button.setTextColor(cleanupN03XColor("#FFFFFF"))
                    button.background = cleanupN03XRoundBg("#111827", "#111827", 16, 1)
                }

                normalized.contains("CONFIGURAÇÕES DO APP") ||
                normalized.contains("ARMAZENAMENTO DO ANDROID") ||
                normalized.contains("LIGAR MONITOR") ||
                normalized.contains("COMPACTO") ||
                normalized.contains("DETALHADO") -> {
                    button.setTextColor(cleanupN03XColor("#111827"))
                    button.background = cleanupN03XRoundBg("#FFFFFF", "#111827", 16, 1)
                }

                else -> {
                    button.setTextColor(cleanupN03XColor("#111827"))
                    button.background = cleanupN03XRoundBg("#F8FAFC", "#D1D5DB", 14, 1)
                }
            }

            button.textSize = 13f
            button.setTypeface(null, android.graphics.Typeface.BOLD)
            button.setPadding(dp(10), dp(10), dp(10), dp(10))
            button.minHeight = dp(50)
            button.isAllCaps = false
        } catch (_: Throwable) {
        }
    }


            private fun cleanupN03ZApplyTextPremium(view: android.view.View) {
        try {
            if (view is android.widget.TextView && view !is android.widget.Button && view !is android.widget.CheckBox) {
                val value = view.text?.toString()?.trim() ?: ""

                if (
                    value == "Aplicativo" ||
                    value == "Armazenamento" ||
                    value == "Monitor discreto no topo" ||
                    value == "Opções da notificação" ||
                    value == "Modo da notificação" ||
                    value == "Conteúdo exibido"
                ) {
                    view.setTextColor(cleanupN03XColor("#111827"))
                    view.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    view.setTextColor(cleanupN03XColor("#4B5563"))
                }
            }

            if (view is android.widget.CheckBox) {
                view.setTextColor(cleanupN03XColor("#1F2937"))
                try {
                    view.buttonTintList = android.content.res.ColorStateList.valueOf(cleanupN03XColor("#0F9F7A"))
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }


            private fun cleanupN03ZWalkPremium(view: android.view.View) {
        try {
            if (view is android.widget.Button) {
                cleanupN03ZStyleButtonByText(view)
            } else {
                cleanupN03ZApplyTextPremium(view)
            }

            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) {
                    cleanupN03ZWalkPremium(view.getChildAt(i))
                }
            }
        } catch (_: Throwable) {
        }
    }


            private fun cleanupN03ZForcePremiumConfigVisual(root: android.widget.LinearLayout) {
        try {
            root.setBackgroundColor(cleanupN03XColor("#F6F8FC"))
            cleanupN03ZWalkPremium(root)

            if (root.findViewWithTag<android.view.View>("N03Z_BOTTOM_SPACER") == null) {
                val spacer = android.view.View(this)
                spacer.tag = "N03Z_BOTTOM_SPACER"
                root.addView(
                    spacer,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(120)
                    )
                )
            }

            root.post {
                try {
                    cleanupN03ZWalkPremium(root)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

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

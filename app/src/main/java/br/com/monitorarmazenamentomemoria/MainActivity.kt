package br.com.monitorarmazenamentomemoria

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import kotlin.concurrent.thread
import java.io.File
import android.view.View
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Monitor.createChannel(this)
        Monitor.schedule(this)
    }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var greenLimit = 89
    private var yellowLimit = 96
    private var notificationEnabled = true
    private var themeMode = "auto"
    private var activeScreen = "Painel"

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView

    private lateinit var storagePercent: TextView
    private lateinit var storageStatus: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var storageTotal: TextView
    private lateinit var storageUsed: TextView
    private lateinit var storageFree: TextView

    private lateinit var memoryPercent: TextView
    private lateinit var memoryStatus: TextView
    private lateinit var memoryBar: ProgressBar
    private lateinit var memoryTotal: TextView
    private lateinit var memoryUsed: TextView
    private lateinit var memoryFree: TextView

    private lateinit var alertText: TextView
    private lateinit var timeText: TextView

    private val autoRefresh = object : Runnable {
        override fun run() {
            updateInfo()
            handler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
        loadSettings()

        Monitor.createChannel(this)
        Monitor.schedule(this)
        requestNotificationPermission()
        startMonitorService()
        showPanelScreen()
    }

    override fun onResume() {
        super.onResume()
        handler.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
    }

    private fun loadSettings() {
        greenLimit = prefs.getInt("greenLimit", 89)
        yellowLimit = prefs.getInt("yellowLimit", 96)
        notificationEnabled = prefs.getBoolean("notificationEnabled", true)
        themeMode = prefs.getString("themeMode", "auto") ?: "auto"
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("greenLimit", greenLimit)
            .putInt("yellowLimit", yellowLimit)
            .putBoolean("notificationEnabled", notificationEnabled)
            .putString("themeMode", themeMode)
            .apply()
    }

    private fun startMonitorService() {
        if (notificationEnabled) {
            try {
                val intent = Intent(this, MonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Monitoramento em segundo plano será ajustado pelo Android", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopMonitorService() {
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun isDark(): Boolean {
        return when (themeMode) {
            "dark" -> true
            "light" -> false
            else -> {
                val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                night == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun bgColor() = if (isDark()) Color.rgb(12, 16, 28) else Color.rgb(246, 248, 252)
    private fun cardColor() = if (isDark()) Color.rgb(25, 31, 48) else Color.WHITE
    private fun mainText() = if (isDark()) Color.WHITE else Color.rgb(14, 26, 56)
    private fun subText() = if (isDark()) Color.rgb(190, 200, 220) else Color.rgb(80, 90, 110)

    private fun baseScreen() {
        scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor())

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(28), dp(18), dp(18))
        root.gravity = Gravity.CENTER_HORIZONTAL

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun showPanelScreen() {
        activeScreen = "Painel"
        baseScreen()

        val title = TextView(this)
        title.text = "Monitor de\nArmazenamento e Memória"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setTextColor(mainText())
        root.addView(title)

        timeText = TextView(this)
        timeText.textSize = 14f
        timeText.gravity = Gravity.CENTER
        timeText.setTextColor(Color.rgb(35, 92, 170))
        timeText.setPadding(0, dp(8), 0, dp(18))
        root.addView(timeText)

        val storageCard = card(if (isDark()) Color.rgb(39, 33, 18) else Color.rgb(255, 252, 240), Color.rgb(255, 193, 7))
        storageCard.addView(cardHeader("💾", "ARMAZENAMENTO", true))
        storagePercent = percentText()
        storageStatus = pillText()
        storageBar = progress()
        storageTotal = metricText()
        storageUsed = metricText()
        storageFree = metricText()

        storageCard.addView(rowPercent(storagePercent, storageStatus))
        storageCard.addView(storageBar)
        storageCard.addView(metricsRow("Capacidade total", storageTotal, "Usado", storageUsed, "Livre", storageFree))
        root.addView(storageCard)

        val memoryCard = card(if (isDark()) Color.rgb(18, 38, 24) else Color.rgb(244, 255, 246), Color.rgb(58, 201, 78))
        memoryCard.addView(cardHeader("🧠", "MEMÓRIA RAM", false))
        memoryPercent = percentText()
        memoryStatus = pillText()
        memoryBar = progress()
        memoryTotal = metricText()
        memoryUsed = metricText()
        memoryFree = metricText()

        memoryCard.addView(rowPercent(memoryPercent, memoryStatus))
        memoryCard.addView(memoryBar)
        memoryCard.addView(metricsRow("Capacidade total", memoryTotal, "Usada", memoryUsed, "Livre", memoryFree))
        root.addView(memoryCard)

        alertText = TextView(this)
        alertText.textSize = 16f
        alertText.setTextColor(if (isDark()) Color.rgb(255, 190, 180) else Color.rgb(130, 42, 32))
        alertText.setPadding(dp(18), dp(16), dp(18), dp(16))
        alertText.background = rounded(if (isDark()) Color.rgb(55, 28, 24) else Color.rgb(255, 238, 232), Color.rgb(255, 170, 150), dp(16))
        val alertParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        alertParams.setMargins(0, dp(12), 0, dp(12))
        root.addView(alertText, alertParams)

        val refresh = Button(this)
        refresh.text = "ATUALIZAR AGORA"
        refresh.setOnClickListener { updateInfo() }
        root.addView(refresh, buttonParams())

        val notify = Button(this)
        notify.text = if (notificationEnabled) "MONITORAMENTO ATIVO" else "ATIVAR MONITORAMENTO"
        notify.setOnClickListener {
            notificationEnabled = !notificationEnabled
            saveSettings()
            if (notificationEnabled) {
                startMonitorService()
                Monitor.showNotification(this, greenLimit, yellowLimit)
                Toast.makeText(this, "Monitoramento ativado", Toast.LENGTH_SHORT).show()
            } else {
                stopMonitorService()
                NotificationManagerCompat.from(this).cancel(1001)
                Toast.makeText(this, "Monitoramento desativado", Toast.LENGTH_SHORT).show()
            }
            showPanelScreen()
        }
        root.addView(notify, buttonParams())

        root.addView(bottomNav("Painel"))
        updateInfo()
    }

    private fun updateInfo() {
        val data = Monitor.read(this)

        applyData(data.storageUsedPercent, storagePercent, storageStatus, storageBar, storageTotal, storageUsed, storageFree, data.storageTotal, data.storageUsed, data.storageFree, true)
        applyData(data.memoryUsedPercent, memoryPercent, memoryStatus, memoryBar, memoryTotal, memoryUsed, memoryFree, data.memoryTotal, data.memoryUsed, data.memoryFree, false)

        alertText.text = if (data.storageUsedPercent > greenLimit) {
            "⚠ Alerta: armazenamento acima de $greenLimit%. Recomendado liberar espaço."
        } else {
            "✓ Armazenamento em nível normal."
        }

        timeText.text = "◉ Atualizado: ${Monitor.time(data.readAt)}"

        if (notificationEnabled) Monitor.showNotification(this, greenLimit, yellowLimit)
        MonitorWidgetProvider.updateAll(this)
    }

    private fun applyData(percent: Int, percentText: TextView, statusText: TextView, bar: ProgressBar, totalView: TextView, usedView: TextView, freeView: TextView, total: Long, used: Long, free: Long, isStorage: Boolean) {
        val color = Monitor.statusColor(percent, greenLimit, yellowLimit)
        val label = Monitor.statusLabel(percent, greenLimit, yellowLimit)

        percentText.text = if (isStorage) "$percent% usado" else "$percent% usada"
        statusText.text = label
        statusText.setTextColor(Monitor.statusTextColor(percent, greenLimit, yellowLimit))
        statusText.background = rounded(Monitor.statusLightColor(percent, greenLimit, yellowLimit), color, dp(18))

        bar.progress = percent
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(if (isDark()) Color.rgb(65, 70, 85) else Color.rgb(226, 230, 235))

        totalView.text = Monitor.format(total)
        usedView.text = Monitor.format(used)
        freeView.text = Monitor.format(free)
    }

    private fun showConfigScreen() {
        activeScreen = "Config."
        baseScreen()

        root.addView(screenTitle("Configurações"))
        root.addView(configCard())
        root.addView(themeCard())
        root.addView(infoCard("Atualização", "Tela aberta: a cada 30 segundos\nServiço ativo: atualiza notificação e widget em segundo plano\nWidget: toque nele para abrir o painel elegante"))

        val reset = Button(this)
        reset.text = "RESTAURAR PADRÃO"
        reset.setOnClickListener {
            greenLimit = 89
            yellowLimit = 96
            notificationEnabled = true
            themeMode = "auto"
            saveSettings()
            startMonitorService()
            Toast.makeText(this, "Padrão restaurado", Toast.LENGTH_SHORT).show()
            showConfigScreen()
        }
        root.addView(reset, buttonParams())

        root.addView(bottomNav("Config."))
    }

    private fun configCard(): LinearLayout {
        val box = card(cardColor(), if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(225, 230, 240))
        val h = TextView(this)
        h.text = "Cores e Limites"
        h.textSize = 20f
        h.setTypeface(null, Typeface.BOLD)
        h.setTextColor(mainText())
        h.setPadding(0, 0, 0, dp(14))
        box.addView(h)

        box.addView(limitRow("🟢 Verde até", greenLimit, 1, 95) {
            greenLimit = it
            if (yellowLimit <= greenLimit) yellowLimit = greenLimit + 1
            saveSettings()
            MonitorWidgetProvider.updateAll(this)
            showConfigScreen()
        })

        box.addView(limitRow("🟡 Amarelo até", yellowLimit, greenLimit + 1, 99) {
            yellowLimit = it
            saveSettings()
            MonitorWidgetProvider.updateAll(this)
            showConfigScreen()
        })

        val red = TextView(this)
        red.text = "🔴 Vermelho acima de ${yellowLimit + 1}%"
        red.textSize = 17f
        red.setTextColor(mainText())
        red.setPadding(0, dp(12), 0, dp(12))
        box.addView(red)

        val notificationButton = Button(this)
        notificationButton.text = if (notificationEnabled) "DESATIVAR MONITORAMENTO" else "ATIVAR MONITORAMENTO"
        notificationButton.setOnClickListener {
            notificationEnabled = !notificationEnabled
            saveSettings()
            if (notificationEnabled) startMonitorService() else stopMonitorService()
            showConfigScreen()
        }
        box.addView(notificationButton, buttonParams())
        return box
    }

    private fun themeCard(): LinearLayout {
        val box = card(cardColor(), if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(225, 230, 240))
        val h = TextView(this)
        h.text = "Tema"
        h.textSize = 20f
        h.setTypeface(null, Typeface.BOLD)
        h.setTextColor(mainText())
        box.addView(h)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(12), 0, 0)

        row.addView(themeButton("Automático pelo sistema", "auto"))
        row.addView(themeButton("Claro", "light"))
        row.addView(themeButton("Escuro", "dark"))
        box.addView(row)
        return box
    }

    private fun themeButton(label: String, value: String): Button {
        return Button(this).apply {
            text = if (themeMode == value) "✓ $label" else label
            setOnClickListener {
                themeMode = value
                saveSettings()
                MonitorWidgetProvider.updateAll(this@MainActivity)
                showConfigScreen()
            }
        }
    }

    private fun limitRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(8), 0, dp(8))

        val title = TextView(this)
        title.text = "$label: $value%"
        title.textSize = 17f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(mainText())
        container.addView(title)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val minus = Button(this)
        minus.text = "−"
        minus.setOnClickListener { if (value > min) onChange(value - 1) }

        val number = TextView(this)
        number.text = "$value%"
        number.textSize = 20f
        number.setTypeface(null, Typeface.BOLD)
        number.gravity = Gravity.CENTER
        number.setTextColor(mainText())

        val plus = Button(this)
        plus.text = "+"
        plus.setOnClickListener { if (value < max) onChange(value + 1) }

        row.addView(minus, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(number, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(row)
        return container
    }

    private fun showWidgetScreen() {
        activeScreen = "Widget"
        baseScreen()
        root.addView(screenTitle("Widget"))

        val preview = card(cardColor(), if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(210, 220, 240))
        val data = Monitor.read(this)

        val top = TextView(this)
        top.text = "Widget compacto"
        top.textSize = 20f
        top.setTypeface(null, Typeface.BOLD)
        top.setTextColor(mainText())
        preview.addView(top)

        val line1 = TextView(this)
        line1.text = "ARM ${data.storageUsedPercent}% • ${Monitor.statusLabel(data.storageUsedPercent, greenLimit, yellowLimit)}"
        line1.textSize = 20f
        line1.setTypeface(null, Typeface.BOLD)
        line1.setTextColor(Monitor.statusColor(data.storageUsedPercent, greenLimit, yellowLimit))
        line1.setPadding(0, dp(18), 0, dp(6))
        preview.addView(line1)

        val line2 = TextView(this)
        line2.text = "RAM ${data.memoryUsedPercent}% • ${Monitor.statusLabel(data.memoryUsedPercent, greenLimit, yellowLimit)}"
        line2.textSize = 20f
        line2.setTypeface(null, Typeface.BOLD)
        line2.setTextColor(Monitor.statusColor(data.memoryUsedPercent, greenLimit, yellowLimit))
        preview.addView(line2)

        val free = TextView(this)
        free.text = "Livre: ${Monitor.format(data.storageFree)} no aparelho"
        free.textSize = 16f
        free.setTextColor(subText())
        free.setPadding(0, dp(16), 0, 0)
        preview.addView(free)

        root.addView(preview)
        root.addView(infoCard("Como usar", "Segure na tela inicial do Samsung, toque em Widgets, procure Monitor de Armazenamento e Memória e arraste para a tela.\n\nAo tocar no widget, abre um painel elegante com os detalhes."))

        val updateWidgetButton = Button(this)
        updateWidgetButton.text = "ATUALIZAR WIDGET AGORA"
        updateWidgetButton.setOnClickListener {
            MonitorWidgetProvider.updateAll(this)
            Toast.makeText(this, "Widget atualizado", Toast.LENGTH_SHORT).show()
        }
        root.addView(updateWidgetButton, buttonParams())

        root.addView(bottomNav("Widget"))
    }


    private fun showCleanupScreen() {
        activeScreen = "Limpeza"
        baseScreen()

        root.addView(screenTitle("Limpeza"))

        val memoryBox = card(cardColor(), if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(225, 230, 240))

        val memoryTitle = TextView(this)
        memoryTitle.text = "Otimização de memória"
        memoryTitle.textSize = 20f
        memoryTitle.setTypeface(null, Typeface.BOLD)
        memoryTitle.setTextColor(mainText())
        memoryBox.addView(memoryTitle)

        val memoryInfo = TextView(this)
        val data = Monitor.read(this)
        memoryInfo.text = "RAM atual: ${data.memoryUsedPercent}% usada\nRAM livre: ${Monitor.format(data.memoryFree)}\n\nOtimização segura: atualiza a leitura, limpa cache interno do app e abre o gerenciador do Android quando necessário."
        memoryInfo.textSize = 15f
        memoryInfo.setTextColor(subText())
        memoryInfo.setPadding(0, dp(12), 0, dp(12))
        memoryBox.addView(memoryInfo)

        val optimize = Button(this)
        optimize.text = "OTIMIZAR MEMÓRIA AGORA"
        optimize.setOnClickListener {
            try {
                cacheDir.deleteRecursively()
                externalCacheDir?.deleteRecursively()
            } catch (_: Exception) {}
            MonitorWidgetProvider.updateAll(this)
            Monitor.showNotification(this, greenLimit, yellowLimit)
            Toast.makeText(this, "Otimização segura concluída", Toast.LENGTH_SHORT).show()
            showCleanupScreen()
        }
        memoryBox.addView(optimize, buttonParams())

        val androidStorage = Button(this)
        androidStorage.text = "ABRIR ARMAZENAMENTO DO ANDROID"
        androidStorage.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        memoryBox.addView(androidStorage, buttonParams())

        root.addView(memoryBox)

        val permissionBox = card(cardColor(), if (hasAllFilesAccess()) Color.rgb(58, 201, 78) else Color.rgb(255, 193, 7))

        val permissionTitle = TextView(this)
        permissionTitle.text = if (hasAllFilesAccess()) "Acesso aos arquivos liberado" else "Acesso aos arquivos necessário"
        permissionTitle.textSize = 20f
        permissionTitle.setTypeface(null, Typeface.BOLD)
        permissionTitle.setTextColor(mainText())
        permissionBox.addView(permissionTitle)

        val permissionText = TextView(this)
        permissionText.text = if (hasAllFilesAccess()) {
            "O app já pode analisar arquivos grandes, recentes e sensíveis no armazenamento."
        } else {
            "Para encontrar arquivos como backups do WhatsApp, vídeos grandes, APKs e bancos de dados, libere acesso amplo aos arquivos. Nesta etapa o app apenas lista; ainda não apaga nada."
        }
        permissionText.textSize = 15f
        permissionText.setTextColor(subText())
        permissionText.setPadding(0, dp(12), 0, dp(12))
        permissionBox.addView(permissionText)

        val permissionButton = Button(this)
        permissionButton.text = "LIBERAR ACESSO AOS ARQUIVOS"
        permissionButton.setOnClickListener { requestAllFilesAccess() }
        permissionBox.addView(permissionButton, buttonParams())

        root.addView(permissionBox)

        val status = TextView(this)
        status.text = "Preparando análise..."
        status.textSize = 15f
        status.setTextColor(subText())
        status.setPadding(0, dp(8), 0, dp(12))
        root.addView(status)

        val refreshButton = Button(this)
        refreshButton.text = "ATUALIZAR LISTA AGORA"
        root.addView(refreshButton, buttonParams())

        val resultsBox = LinearLayout(this)
        resultsBox.orientation = LinearLayout.VERTICAL
        root.addView(resultsBox)

        refreshButton.setOnClickListener {
            startCleanupScan(resultsBox, status)
        }

        startCleanupScan(resultsBox, status)

        root.addView(infoCard("Atualização automática", "Enquanto esta aba Limpeza estiver aberta, a lista será atualizada a cada 1 minuto para detectar arquivos novos ou modificados recentemente.\n\nNesta etapa, nada será apagado automaticamente."))

        root.addView(bottomNav("Limpeza"))
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uri = Uri.parse("package:$packageName")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Acesso compatível com esta versão do Android", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun startCleanupScan(resultsBox: LinearLayout, status: TextView) {
        resultsBox.removeAllViews()
        status.text = "Analisando arquivos... aguarde."

        thread {
            val files = mutableListOf<CleanupFile>()
            val roots = mutableListOf<File>()

            try {
                roots.add(Environment.getExternalStorageDirectory())
            } catch (_: Exception) {}

            val started = System.currentTimeMillis()
            val maxFiles = 2500

            fun scan(dir: File, depth: Int) {
                if (files.size >= maxFiles) return
                if (depth > 8) return
                if (System.currentTimeMillis() - started > 25000) return

                val list = try {
                    dir.listFiles()
                } catch (_: Exception) {
                    null
                } ?: return

                for (f in list) {
                    if (files.size >= maxFiles) return
                    if (System.currentTimeMillis() - started > 25000) return

                    try {
                        if (f.isDirectory) {
                            if (!f.name.startsWith(".")) scan(f, depth + 1)
                        } else {
                            val size = f.length()
                            if (size > 0) {
                                val path = f.absolutePath
                                val sensitive = isSensitiveFile(f)
                                val type = fileTypeLabel(f)
                                files.add(CleanupFile(f.name, path, size, f.lastModified(), sensitive, type))
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            for (r in roots) {
                if (r.exists()) scan(r, 0)
            }

            val largest = files.sortedByDescending { it.size }.take(25)
            val recent = files.sortedByDescending { it.modified }.take(25)
            val sensitive = files.filter { it.sensitive }.sortedWith(compareByDescending<CleanupFile> { it.modified }.thenByDescending { it.size }).take(30)

            runOnUiThread {
                if (activeScreen != "Limpeza") return@runOnUiThread

                resultsBox.removeAllViews()
                status.text = "Última análise: ${Monitor.time(System.currentTimeMillis())} • ${files.size} arquivos verificados"

                resultsBox.addView(cleanupSection("Arquivos sensíveis", "Backups, bancos de dados, arquivos do WhatsApp, certificados, chaves e documentos pessoais.", sensitive))
                resultsBox.addView(cleanupSection("Arquivos recentes", "Arquivos criados ou modificados mais recentemente.", recent))
                resultsBox.addView(cleanupSection("Maiores arquivos", "Arquivos ordenados do maior para o menor.", largest))

                handler.postDelayed({
                    if (activeScreen == "Limpeza") startCleanupScan(resultsBox, status)
                }, 60000)
            }
        }
    }

    private fun cleanupSection(title: String, subtitle: String, files: List<CleanupFile>): LinearLayout {
        val box = card(cardColor(), if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(225, 230, 240))

        val t = TextView(this)
        t.text = title
        t.textSize = 20f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(mainText())
        box.addView(t)

        val s = TextView(this)
        s.text = subtitle
        s.textSize = 14f
        s.setTextColor(subText())
        s.setPadding(0, dp(6), 0, dp(12))
        box.addView(s)

        if (files.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Nenhum arquivo encontrado nesta categoria."
            empty.textSize = 15f
            empty.setTextColor(subText())
            box.addView(empty)
            return box
        }

        for (f in files) {
            box.addView(cleanupFileRow(f))
        }

        return box
    }

    private fun cleanupFileRow(file: CleanupFile): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(10), dp(10), dp(10), dp(10))
        row.background = rounded(
            if (file.sensitive) {
                if (isDark()) Color.rgb(55, 35, 28) else Color.rgb(255, 246, 232)
            } else {
                if (isDark()) Color.rgb(32, 38, 56) else Color.rgb(247, 249, 252)
            },
            if (file.sensitive) Color.rgb(255, 170, 80) else if (isDark()) Color.rgb(55, 65, 90) else Color.rgb(230, 235, 245),
            dp(14)
        )

        val name = TextView(this)
        name.text = if (file.sensitive) "⚠ ${file.name}" else file.name
        name.textSize = 16f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(mainText())
        row.addView(name)

        val detail = TextView(this)
        detail.text = "${file.type} • ${Monitor.format(file.size)} • ${Monitor.time(file.modified)}"
        detail.textSize = 13f
        detail.setTextColor(subText())
        detail.setPadding(0, dp(4), 0, dp(4))
        row.addView(detail)

        val path = TextView(this)
        path.text = file.path
        path.textSize = 12f
        path.setTextColor(subText())
        row.addView(path)

        if (file.sensitive) {
            val warn = TextView(this)
            warn.text = "Arquivo sensível: não excluir sem conferir. Pode conter backup, histórico, banco de dados ou documento importante."
            warn.textSize = 12f
            warn.setTextColor(Color.rgb(160, 80, 20))
            warn.setPadding(0, dp(6), 0, 0)
            row.addView(warn)
        }

        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 0, 0, dp(8))
        row.layoutParams = params

        return row
    }

    private fun isSensitiveFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.ROOT)
        val path = file.absolutePath.lowercase(Locale.ROOT)

        val sensitiveWords = listOf(
            "msgstore",
            "wa.db",
            "crypt",
            "database",
            "databases",
            "backup",
            "backups",
            "senha",
            "senhas",
            "password",
            "key",
            "keystore",
            "certificate",
            "certificado",
            "token",
            "auth",
            "pix",
            "banco",
            "extrato",
            "contrato",
            "documento",
            "rg",
            "cpf",
            "cnh",
            "irpf",
            "receita",
            "imposto",
            "nota fiscal",
            "nfe"
        )

        val sensitiveExtensions = listOf(
            ".db",
            ".sqlite",
            ".sqlite3",
            ".crypt",
            ".crypt12",
            ".crypt14",
            ".bak",
            ".backup",
            ".key",
            ".pem",
            ".p12",
            ".pfx",
            ".cer",
            ".crt"
        )

        if (path.contains("/android/media/com.whatsapp/")) return true
        if (path.contains("/whatsapp/databases/")) return true
        if (sensitiveWords.any { path.contains(it) }) return true
        if (sensitiveExtensions.any { name.endsWith(it) }) return true

        return false
    }

    private fun fileTypeLabel(file: File): String {
        val name = file.name.lowercase(Locale.ROOT)
        val path = file.absolutePath.lowercase(Locale.ROOT)

        return when {
            path.contains("whatsapp") && name.contains("msgstore") -> "Backup criptografado do WhatsApp"
            path.contains("whatsapp") -> "Arquivo do WhatsApp"
            name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".mov") || name.endsWith(".avi") -> "Vídeo"
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") -> "Imagem"
            name.endsWith(".apk") -> "Instalador APK"
            name.endsWith(".pdf") -> "PDF"
            name.endsWith(".doc") || name.endsWith(".docx") -> "Documento Word"
            name.endsWith(".xls") || name.endsWith(".xlsx") -> "Planilha"
            name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") -> "Arquivo compactado"
            name.endsWith(".db") || name.endsWith(".sqlite") || name.contains("crypt") -> "Banco de dados / backup"
            else -> "Arquivo"
        }
    }

    private fun bottomNav(active: String): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(10), dp(10), dp(10), dp(10))
        nav.background = rounded(if (isDark()) Color.rgb(25, 31, 48) else Color.WHITE, if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(230, 235, 245), dp(22))

        nav.addView(navItem("📊\nPainel", active == "Painel") { showPanelScreen() })
        nav.addView(navItem("▦\nWidget", active == "Widget") { showWidgetScreen() })
        nav.addView(navItem("🧹\nLimpeza", active == "Limpeza") { showCleanupScreen() })
        nav.addView(navItem("⚙\nConfig.", active == "Config.") { showConfigScreen() })

        val navParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        navParams.setMargins(0, dp(8), 0, 0)
        nav.layoutParams = navParams
        return nav
    }

    private fun navItem(textValue: String, active: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.rgb(20, 92, 210) else subText())
            background = if (active) rounded(if (isDark()) Color.rgb(35, 50, 80) else Color.rgb(232, 241, 255), Color.TRANSPARENT, dp(16)) else null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
    }

    private fun screenTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(mainText())
            setPadding(0, 0, 0, dp(20))
        }
    }

    private fun infoCard(title: String, body: String): LinearLayout {
        val box = card(cardColor(), if (isDark()) Color.rgb(50, 60, 85) else Color.rgb(225, 230, 240))
        val t = TextView(this)
        t.text = title
        t.textSize = 20f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(mainText())
        box.addView(t)

        val b = TextView(this)
        b.text = body
        b.textSize = 16f
        b.setPadding(0, dp(12), 0, 0)
        b.setTextColor(subText())
        box.addView(b)
        return box
    }

    private fun cardHeader(icon: String, title: String, warning: Boolean): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val iconView = TextView(this)
        iconView.text = icon
        iconView.textSize = 26f
        iconView.gravity = Gravity.CENTER
        iconView.background = rounded(if (warning) Color.rgb(255, 193, 7) else Color.rgb(58, 201, 78), Color.TRANSPARENT, dp(16))

        val iconParams = LinearLayout.LayoutParams(dp(64), dp(64))
        iconParams.setMargins(0, 0, dp(14), 0)
        row.addView(iconView, iconParams)

        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 17f
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setTextColor(mainText())
        row.addView(titleView)
        return row
    }

    private fun rowPercent(percent: TextView, status: TextView): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))
        row.addView(percent, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(status)
        return row
    }

    private fun metricsRow(a: String, av: TextView, b: String, bv: TextView, c: String, cv: TextView): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(16), 0, 0)
        row.addView(metricBox(a, av), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(metricBox(b, bv), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(metricBox(c, cv), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun metricBox(label: String, value: TextView): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER

        val l = TextView(this)
        l.text = label
        l.textSize = 12f
        l.gravity = Gravity.CENTER
        l.setTextColor(subText())

        value.gravity = Gravity.CENTER
        box.addView(l)
        box.addView(value)
        return box
    }

    private fun card(bg: Int, border: Int): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(dp(18), dp(18), dp(18), dp(18))
        v.background = rounded(bg, border, dp(22))
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 0, 0, dp(16))
        v.layoutParams = params
        return v
    }

    private fun percentText() = TextView(this).apply {
        textSize = 34f
        setTypeface(null, Typeface.BOLD)
        setTextColor(mainText())
    }

    private fun pillText() = TextView(this).apply {
        textSize = 13f
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun metricText() = TextView(this).apply {
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(mainText())
    }

    private fun progress() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        minHeight = dp(12)
        progress = 0
    }

    private fun buttonParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        p.setMargins(0, dp(6), 0, dp(6))
        return p
    }

    private fun rounded(bg: Int, stroke: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(bg)
        d.cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke)
        return d
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
    }
}

data class CleanupFile(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val sensitive: Boolean,
    val type: String
)

data class MonitorData(
    val storageTotal: Long,
    val storageFree: Long,
    val storageUsed: Long,
    val storageUsedPercent: Int,
    val memoryTotal: Long,
    val memoryFree: Long,
    val memoryUsed: Long,
    val memoryUsedPercent: Int,
    val readAt: Long
)

object Monitor {
    const val CHANNEL_ID = "monitor_channel"
    const val NOTIFICATION_ID = 1001

    fun read(context: Context): MonitorData {
        val stat = StatFs(Environment.getDataDirectory().path)
        val storageTotal = stat.blockCountLong * stat.blockSizeLong
        val storageFree = stat.availableBlocksLong * stat.blockSizeLong
        val storageUsed = storageTotal - storageFree
        val storageUsedPercent = if (storageTotal > 0) ((storageUsed * 100) / storageTotal).toInt() else 0

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val memoryTotal = mi.totalMem
        val memoryFree = mi.availMem
        val memoryUsed = memoryTotal - memoryFree
        val memoryUsedPercent = if (memoryTotal > 0) ((memoryUsed * 100) / memoryTotal).toInt() else 0

        return MonitorData(storageTotal, storageFree, storageUsed, storageUsedPercent, memoryTotal, memoryFree, memoryUsed, memoryUsedPercent, System.currentTimeMillis())
    }

    fun format(bytes: Long): String {
        val gb = bytes.toDouble() / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1) String.format(Locale("pt", "BR"), "%.1f GB", gb)
        else String.format(Locale("pt", "BR"), "%.0f MB", bytes.toDouble() / 1024.0 / 1024.0)
    }

    fun time(millis: Long): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date(millis))
    }

    fun shortTime(millis: Long): String {
        return SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(millis))
    }

    fun statusColor(percent: Int, greenLimit: Int, yellowLimit: Int): Int {
        return when {
            percent > yellowLimit -> Color.rgb(235, 67, 53)
            percent > greenLimit -> Color.rgb(255, 193, 7)
            else -> Color.rgb(58, 201, 78)
        }
    }

    fun statusLightColor(percent: Int, greenLimit: Int, yellowLimit: Int): Int {
        return when {
            percent > yellowLimit -> Color.rgb(255, 235, 232)
            percent > greenLimit -> Color.rgb(255, 248, 220)
            else -> Color.rgb(232, 255, 236)
        }
    }

    fun statusTextColor(percent: Int, greenLimit: Int, yellowLimit: Int): Int {
        return when {
            percent > yellowLimit -> Color.rgb(130, 42, 32)
            percent > greenLimit -> Color.rgb(120, 82, 0)
            else -> Color.rgb(36, 118, 48)
        }
    }

    fun statusLabel(percent: Int, greenLimit: Int, yellowLimit: Int): String {
        return when {
            percent > yellowLimit -> "Crítico"
            percent > greenLimit -> "Atenção"
            else -> "Normal"
        }
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Monitor de armazenamento e memória", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Mostra armazenamento e memória RAM"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(context: Context, greenLimit: Int = 89, yellowLimit: Int = 96): android.app.Notification {
        val data = read(context)
        val storageStatus = statusLabel(data.storageUsedPercent, greenLimit, yellowLimit)
        val memoryStatus = statusLabel(data.memoryUsedPercent, greenLimit, yellowLimit)

        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(context, 1002, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Armaz. ${data.storageUsedPercent}% $storageStatus • RAM ${data.memoryUsedPercent}% $memoryStatus")
            .setContentText("Livre: ${format(data.storageFree)} • Atualizado ${shortTime(data.readAt)}")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showNotification(context: Context, greenLimit: Int = 89, yellowLimit: Int = 96) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, buildNotification(context, greenLimit, yellowLimit))
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("monitor_periodic_work", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class MonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
        val green = prefs.getInt("greenLimit", 89)
        val yellow = prefs.getInt("yellowLimit", 96)
        val enabled = prefs.getBoolean("notificationEnabled", true)
        if (enabled) Monitor.showNotification(applicationContext, green, yellow)
        MonitorWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }
}

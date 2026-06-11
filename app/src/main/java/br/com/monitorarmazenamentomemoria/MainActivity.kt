package br.com.monitorarmazenamentomemoria

import android.Manifest
import android.app.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
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
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("greenLimit", greenLimit)
            .putInt("yellowLimit", yellowLimit)
            .putBoolean("notificationEnabled", notificationEnabled)
            .apply()
    }

    private fun baseScreen() {
        scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(246, 248, 252))

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(28), dp(18), dp(18))
        root.gravity = Gravity.CENTER_HORIZONTAL

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun showPanelScreen() {
        baseScreen()

        val title = TextView(this)
        title.text = "Monitor de\nArmazenamento e Memória"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.rgb(14, 26, 56))
        root.addView(title)

        timeText = TextView(this)
        timeText.textSize = 14f
        timeText.gravity = Gravity.CENTER
        timeText.setTextColor(Color.rgb(35, 92, 170))
        timeText.setPadding(0, dp(8), 0, dp(18))
        root.addView(timeText)

        val storageCard = card(Color.rgb(255, 252, 240), Color.rgb(255, 193, 7))
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

        val memoryCard = card(Color.rgb(244, 255, 246), Color.rgb(58, 201, 78))
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
        alertText.setTextColor(Color.rgb(130, 42, 32))
        alertText.setPadding(dp(18), dp(16), dp(18), dp(16))
        alertText.background = rounded(Color.rgb(255, 238, 232), Color.rgb(255, 170, 150), dp(16))
        val alertParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        alertParams.setMargins(0, dp(12), 0, dp(12))
        root.addView(alertText, alertParams)

        val refresh = Button(this)
        refresh.text = "ATUALIZAR AGORA"
        refresh.setOnClickListener { updateInfo() }
        root.addView(refresh, buttonParams())

        val notify = Button(this)
        notify.text = if (notificationEnabled) "NOTIFICAÇÃO FIXA ATIVADA" else "ATIVAR NOTIFICAÇÃO FIXA"
        notify.setOnClickListener {
            notificationEnabled = !notificationEnabled
            saveSettings()
            if (notificationEnabled) {
                Monitor.showNotification(this, greenLimit, yellowLimit)
                Toast.makeText(this, "Notificação ativada", Toast.LENGTH_SHORT).show()
            } else {
                NotificationManagerCompat.from(this).cancel(1001)
                Toast.makeText(this, "Notificação desativada", Toast.LENGTH_SHORT).show()
            }
            showPanelScreen()
        }
        root.addView(notify, buttonParams())

        root.addView(bottomNav("Painel"))
        updateInfo()
    }

    private fun updateInfo() {
        val data = Monitor.read(this)

        applyData(
            data.storageUsedPercent,
            storagePercent,
            storageStatus,
            storageBar,
            storageTotal,
            storageUsed,
            storageFree,
            data.storageTotal,
            data.storageUsed,
            data.storageFree,
            true
        )

        applyData(
            data.memoryUsedPercent,
            memoryPercent,
            memoryStatus,
            memoryBar,
            memoryTotal,
            memoryUsed,
            memoryFree,
            data.memoryTotal,
            data.memoryUsed,
            data.memoryFree,
            false
        )

        alertText.text = if (data.storageUsedPercent > greenLimit) {
            "⚠ Alerta: armazenamento acima de $greenLimit%. Recomendado liberar espaço."
        } else {
            "✓ Armazenamento em nível normal."
        }

        timeText.text = "◉ Atualizado: ${Monitor.time(data.readAt)}"

        if (notificationEnabled) {
            Monitor.showNotification(this, greenLimit, yellowLimit)
        }
    }

    private fun applyData(
        percent: Int,
        percentText: TextView,
        statusText: TextView,
        bar: ProgressBar,
        totalView: TextView,
        usedView: TextView,
        freeView: TextView,
        total: Long,
        used: Long,
        free: Long,
        isStorage: Boolean
    ) {
        val color = Monitor.statusColor(percent, greenLimit, yellowLimit)
        val label = Monitor.statusLabel(percent, greenLimit, yellowLimit)

        percentText.text = if (isStorage) "$percent% usado" else "$percent% usada"
        statusText.text = label
        statusText.setTextColor(Monitor.statusTextColor(percent, greenLimit, yellowLimit))
        statusText.background = rounded(Monitor.statusLightColor(percent, greenLimit, yellowLimit), color, dp(18))

        bar.progress = percent
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(226, 230, 235))

        totalView.text = Monitor.format(total)
        usedView.text = Monitor.format(used)
        freeView.text = Monitor.format(free)
    }

    private fun showConfigScreen() {
        baseScreen()

        val title = screenTitle("Configurações")
        root.addView(title)

        root.addView(configCard())

        val info = infoCard(
            "Atualização",
            "Tela aberta: a cada 30 segundos\n" +
            "Segundo plano: controlado pelo Android\n" +
            "Notificação fixa: ${if (notificationEnabled) "ativada" else "desativada"}"
        )
        root.addView(info)

        val reset = Button(this)
        reset.text = "RESTAURAR PADRÃO"
        reset.setOnClickListener {
            greenLimit = 89
            yellowLimit = 96
            notificationEnabled = true
            saveSettings()
            Toast.makeText(this, "Padrão restaurado", Toast.LENGTH_SHORT).show()
            showConfigScreen()
        }
        root.addView(reset, buttonParams())

        root.addView(bottomNav("Config."))
    }

    private fun configCard(): LinearLayout {
        val box = card(Color.WHITE, Color.rgb(225, 230, 240))

        val h = TextView(this)
        h.text = "Cores e Limites"
        h.textSize = 20f
        h.setTypeface(null, Typeface.BOLD)
        h.setTextColor(Color.rgb(14, 26, 56))
        h.setPadding(0, 0, 0, dp(14))
        box.addView(h)

        box.addView(limitRow("🟢 Verde até", greenLimit, 1, 95) {
            greenLimit = it
            if (yellowLimit <= greenLimit) yellowLimit = greenLimit + 1
            saveSettings()
            showConfigScreen()
        })

        box.addView(limitRow("🟡 Amarelo até", yellowLimit, greenLimit + 1, 99) {
            yellowLimit = it
            saveSettings()
            showConfigScreen()
        })

        val red = TextView(this)
        red.text = "🔴 Vermelho acima de ${yellowLimit + 1}%"
        red.textSize = 17f
        red.setTextColor(Color.rgb(14, 26, 56))
        red.setPadding(0, dp(12), 0, dp(12))
        box.addView(red)

        val notificationButton = Button(this)
        notificationButton.text = if (notificationEnabled) "DESATIVAR NOTIFICAÇÃO FIXA" else "ATIVAR NOTIFICAÇÃO FIXA"
        notificationButton.setOnClickListener {
            notificationEnabled = !notificationEnabled
            saveSettings()
            if (notificationEnabled) Monitor.showNotification(this, greenLimit, yellowLimit)
            else NotificationManagerCompat.from(this).cancel(1001)
            showConfigScreen()
        }
        box.addView(notificationButton, buttonParams())

        return box
    }

    private fun limitRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): LinearLayout {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(8), 0, dp(8))

        val title = TextView(this)
        title.text = "$label: $value%"
        title.textSize = 17f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        container.addView(title)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val minus = Button(this)
        minus.text = "−"
        minus.setOnClickListener {
            if (value > min) onChange(value - 1)
        }

        val number = TextView(this)
        number.text = "$value%"
        number.textSize = 20f
        number.setTypeface(null, Typeface.BOLD)
        number.gravity = Gravity.CENTER
        number.setTextColor(Color.rgb(14, 26, 56))

        val plus = Button(this)
        plus.text = "+"
        plus.setOnClickListener {
            if (value < max) onChange(value + 1)
        }

        row.addView(minus, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(number, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(row)

        return container
    }

    private fun showWidgetScreen() {
        baseScreen()

        val title = screenTitle("Widget")
        root.addView(title)

        val preview = card(Color.WHITE, Color.rgb(210, 220, 240))

        val top = TextView(this)
        top.text = "Prévia do widget"
        top.textSize = 20f
        top.setTypeface(null, Typeface.BOLD)
        top.setTextColor(Color.rgb(14, 26, 56))
        preview.addView(top)

        val data = Monitor.read(this)

        val line1 = TextView(this)
        line1.text = "Armaz. ${data.storageUsedPercent}%"
        line1.textSize = 18f
        line1.setTypeface(null, Typeface.BOLD)
        line1.setPadding(0, dp(20), 0, dp(6))
        preview.addView(line1)

        val bar1 = progress()
        bar1.progress = data.storageUsedPercent
        bar1.progressTintList = ColorStateList.valueOf(Monitor.statusColor(data.storageUsedPercent, greenLimit, yellowLimit))
        preview.addView(bar1)

        val line2 = TextView(this)
        line2.text = "RAM ${data.memoryUsedPercent}%"
        line2.textSize = 18f
        line2.setTypeface(null, Typeface.BOLD)
        line2.setPadding(0, dp(20), 0, dp(6))
        preview.addView(line2)

        val bar2 = progress()
        bar2.progress = data.memoryUsedPercent
        bar2.progressTintList = ColorStateList.valueOf(Monitor.statusColor(data.memoryUsedPercent, greenLimit, yellowLimit))
        preview.addView(bar2)

        val free = TextView(this)
        free.text = "Livre: ${Monitor.format(data.storageFree)} no aparelho"
        free.textSize = 16f
        free.setPadding(0, dp(18), 0, 0)
        preview.addView(free)

        root.addView(preview)

        val explanation = infoCard(
            "Próxima fase",
            "Na versão seguinte vamos criar o widget real para colocar na tela inicial do Android.\n\n" +
            "Ele ficará como um relógio, mostrando armazenamento e RAM sem abrir o app."
        )
        root.addView(explanation)

        root.addView(bottomNav("Widget"))
    }

    private fun bottomNav(active: String): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(10), dp(10), dp(10), dp(10))
        nav.background = rounded(Color.WHITE, Color.rgb(230, 235, 245), dp(22))

        nav.addView(navItem("📊\nPainel", active == "Painel") { showPanelScreen() })
        nav.addView(navItem("▦\nWidget", active == "Widget") { showWidgetScreen() })
        nav.addView(navItem("⚙\nConfig.", active == "Config.") { showConfigScreen() })

        val navParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        navParams.setMargins(0, dp(8), 0, 0)
        nav.layoutParams = navParams

        return nav
    }

    private fun navItem(textValue: String, active: Boolean, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.rgb(20, 92, 210) else Color.rgb(80, 90, 110))
            background = if (active) rounded(Color.rgb(232, 241, 255), Color.TRANSPARENT, dp(16)) else null
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
            setTextColor(Color.rgb(14, 26, 56))
            setPadding(0, 0, 0, dp(20))
        }
    }

    private fun infoCard(title: String, body: String): LinearLayout {
        val box = card(Color.WHITE, Color.rgb(225, 230, 240))
        val t = TextView(this)
        t.text = title
        t.textSize = 20f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.rgb(14, 26, 56))
        box.addView(t)

        val b = TextView(this)
        b.text = body
        b.textSize = 16f
        b.setPadding(0, dp(12), 0, 0)
        b.setTextColor(Color.rgb(45, 55, 75))
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
        iconView.background = rounded(
            if (warning) Color.rgb(255, 193, 7) else Color.rgb(58, 201, 78),
            Color.TRANSPARENT,
            dp(16)
        )

        val iconParams = LinearLayout.LayoutParams(dp(64), dp(64))
        iconParams.setMargins(0, 0, dp(14), 0)
        row.addView(iconView, iconParams)

        val titleView = TextView(this)
        titleView.text = title
        titleView.textSize = 17f
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setTextColor(Color.rgb(14, 26, 56))
        row.addView(titleView)

        return row
    }

    private fun rowPercent(percent: TextView, status: TextView): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))

        val left = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(percent, left)
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
        l.setTextColor(Color.rgb(80, 90, 110))

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

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(16))
        v.layoutParams = params
        return v
    }

    private fun percentText() = TextView(this).apply {
        textSize = 34f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(14, 26, 56))
    }

    private fun pillText() = TextView(this).apply {
        textSize = 13f
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun metricText() = TextView(this).apply {
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(14, 26, 56))
    }

    private fun progress() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        minHeight = dp(12)
        progress = 0
    }

    private fun buttonParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
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
    private const val CHANNEL_ID = "monitor_channel"
    private const val NOTIFICATION_ID = 1001

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

        return MonitorData(
            storageTotal,
            storageFree,
            storageUsed,
            storageUsedPercent,
            memoryTotal,
            memoryFree,
            memoryUsed,
            memoryUsedPercent,
            System.currentTimeMillis()
        )
    }

    fun format(bytes: Long): String {
        val gb = bytes.toDouble() / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1) String.format(Locale("pt", "BR"), "%.1f GB", gb)
        else String.format(Locale("pt", "BR"), "%.0f MB", bytes.toDouble() / 1024.0 / 1024.0)
    }

    fun time(millis: Long): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date(millis))
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitor de armazenamento e memória",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Mostra armazenamento e memória RAM"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showNotification(context: Context, greenLimit: Int = 89, yellowLimit: Int = 96) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }

        val data = read(context)
        val storageStatus = statusLabel(data.storageUsedPercent, greenLimit, yellowLimit)
        val memoryStatus = statusLabel(data.memoryUsedPercent, greenLimit, yellowLimit)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Armaz. ${data.storageUsedPercent}% $storageStatus • RAM ${data.memoryUsedPercent}% $memoryStatus")
            .setContentText("Usado: ${format(data.storageUsed)} de ${format(data.storageTotal)} • RAM: ${format(data.memoryUsed)} de ${format(data.memoryTotal)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "monitor_periodic_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class MonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Monitor.showNotification(applicationContext)
        return Result.success()
    }
}

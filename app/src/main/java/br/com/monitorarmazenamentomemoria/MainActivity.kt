package br.com.monitorarmazenamentomemoria

import android.Manifest
import android.app.*
import android.content.Context
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
        Monitor.createChannel(this)
        Monitor.schedule(this)
        requestNotificationPermission()
        buildScreen()
        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        handler.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(246, 248, 252))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(28), dp(18), dp(18))
        root.gravity = Gravity.CENTER_HORIZONTAL

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
        notify.text = "MOSTRAR NOTIFICAÇÃO FIXA"
        notify.setOnClickListener { Monitor.showNotification(this) }
        root.addView(notify, buttonParams())

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        nav.setPadding(dp(10), dp(10), dp(10), dp(10))
        nav.background = rounded(Color.WHITE, Color.rgb(230, 235, 245), dp(22))
        nav.addView(navItem("📊\nPainel", true))
        nav.addView(navItem("▦\nWidget", false))
        nav.addView(navItem("⚙\nConfig.", false))
        val navParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        navParams.setMargins(0, dp(8), 0, 0)
        root.addView(nav, navParams)

        scroll.addView(root)
        setContentView(scroll)
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

        alertText.text = if (data.storageUsedPercent >= 90) {
            "⚠ Alerta: armazenamento acima de 90%. Recomendado liberar espaço."
        } else {
            "✓ Armazenamento em nível normal."
        }

        timeText.text = "◉ Atualizado: ${Monitor.time(data.readAt)}"
        Monitor.showNotification(this)
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
        val color = Monitor.statusColor(percent)
        val label = Monitor.statusLabel(percent)

        percentText.text = if (isStorage) "$percent% usado" else "$percent% usada"
        statusText.text = label
        statusText.setTextColor(Monitor.statusTextColor(percent))
        statusText.background = rounded(Monitor.statusLightColor(percent), color, dp(18))

        bar.progress = percent
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(226, 230, 235))

        totalView.text = Monitor.format(total)
        usedView.text = Monitor.format(used)
        freeView.text = Monitor.format(free)
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

    private fun navItem(textValue: String, active: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.rgb(20, 92, 210) else Color.rgb(80, 90, 110))
            background = if (active) rounded(Color.rgb(232, 241, 255), Color.TRANSPARENT, dp(16)) else null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
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

    fun statusColor(percent: Int): Int {
        return when {
            percent >= 97 -> Color.rgb(235, 67, 53)
            percent >= 90 -> Color.rgb(255, 193, 7)
            else -> Color.rgb(58, 201, 78)
        }
    }

    fun statusLightColor(percent: Int): Int {
        return when {
            percent >= 97 -> Color.rgb(255, 235, 232)
            percent >= 90 -> Color.rgb(255, 248, 220)
            else -> Color.rgb(232, 255, 236)
        }
    }

    fun statusTextColor(percent: Int): Int {
        return when {
            percent >= 97 -> Color.rgb(130, 42, 32)
            percent >= 90 -> Color.rgb(120, 82, 0)
            else -> Color.rgb(36, 118, 48)
        }
    }

    fun statusLabel(percent: Int): String {
        return when {
            percent >= 97 -> "Crítico"
            percent >= 90 -> "Atenção"
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

    fun showNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }

        val data = read(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Armazenamento ${data.storageUsedPercent}% • RAM ${data.memoryUsedPercent}%")
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

package br.com.monitorarmazenamentomemoria

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
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

    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var memoryText: TextView
    private lateinit var memoryBar: ProgressBar
    private lateinit var detailsText: TextView
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
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(48, 56, 48, 48)

        val title = TextView(this)
        title.text = "Monitor de Armazenamento e Memória"
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        root.addView(title)

        root.addView(section("ARMAZENAMENTO"))
        storageText = bigText()
        storageBar = progress()
        root.addView(storageText)
        root.addView(storageBar)

        root.addView(section("MEMÓRIA RAM"))
        memoryText = bigText()
        memoryBar = progress()
        root.addView(memoryText)
        root.addView(memoryBar)

        detailsText = infoText()
        timeText = infoText()
        root.addView(detailsText)
        root.addView(timeText)

        val refresh = Button(this)
        refresh.text = "Atualizar agora"
        refresh.setOnClickListener { updateInfo() }
        root.addView(refresh)

        val notify = Button(this)
        notify.text = "Mostrar notificação fixa"
        notify.setOnClickListener { Monitor.showNotification(this) }
        root.addView(notify)

        setContentView(root)
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(0, 32, 0, 0)
    }

    private fun bigText() = TextView(this).apply {
        textSize = 34f
        gravity = Gravity.CENTER
    }

    private fun infoText() = TextView(this).apply {
        textSize = 17f
        setPadding(0, 14, 0, 0)
    }

    private fun progress() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
    }

    private fun updateInfo() {
        val data = Monitor.read(this)

        storageText.text = "${data.storageUsedPercent}% usado"
        storageBar.progress = data.storageUsedPercent

        memoryText.text = "${data.memoryUsedPercent}% usada"
        memoryBar.progress = data.memoryUsedPercent

        detailsText.text =
            "Armazenamento livre: ${Monitor.format(data.storageFree)}\n" +
            "Armazenamento usado: ${Monitor.format(data.storageUsed)}\n" +
            "RAM livre: ${Monitor.format(data.memoryFree)}\n" +
            "RAM usada: ${Monitor.format(data.memoryUsed)}"

        timeText.text = "Última leitura: ${Monitor.time(data.readAt)}"

        Monitor.showNotification(this)
    }

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
            .setContentText("Livre: ${format(data.storageFree)} no aparelho • ${format(data.memoryFree)} RAM")
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

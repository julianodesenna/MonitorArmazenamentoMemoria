package br.com.monitorarmazenamentomemoria

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.core.app.NotificationManagerCompat

class MonitorPanelActivity : Activity() {
    private var greenLimit = 89
    private var yellowLimit = 96

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
        greenLimit = prefs.getInt("greenLimit", 89)
        yellowLimit = prefs.getInt("yellowLimit", 96)

        buildPanel()
        MonitorWidgetProvider.updateAll(this)
        Monitor.showNotification(this, greenLimit, yellowLimit)
    }

    private fun buildPanel() {
        val data = Monitor.read(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(24), dp(24), dp(24))
        root.background = rounded(Color.WHITE, Color.rgb(220, 225, 235), dp(28))

        val title = TextView(this)
        title.text = "Monitor do aparelho"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(14, 26, 56))
        root.addView(title)

        root.addView(line("Armazenamento", data.storageUsedPercent, data.storageUsed, data.storageTotal))
        root.addView(line("Memória RAM", data.memoryUsedPercent, data.memoryUsed, data.memoryTotal))

        val free = TextView(this)
        free.text = "Livre no aparelho: ${Monitor.format(data.storageFree)}\nAtualizado: ${Monitor.time(data.readAt)}"
        free.textSize = 15f
        free.setTextColor(Color.rgb(69, 80, 100))
        free.setPadding(0, dp(16), 0, dp(16))
        root.addView(free)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL

        val refresh = Button(this)
        refresh.text = "Atualizar"
        refresh.setOnClickListener {
            MonitorWidgetProvider.updateAll(this)
            Monitor.showNotification(this, greenLimit, yellowLimit)
            buildPanel()
        }

        val close = Button(this)
        close.text = "Fechar"
        close.setOnClickListener { finish() }

        buttons.addView(refresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        setContentView(root)
    }

    private fun line(name: String, percent: Int, used: Long, total: Long): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16), 0, 0)

        val top = TextView(this)
        top.text = "$name $percent% • ${Monitor.statusLabel(percent, greenLimit, yellowLimit)}"
        top.textSize = 19f
        top.setTypeface(null, Typeface.BOLD)
        top.setTextColor(Monitor.statusColor(percent, greenLimit, yellowLimit))
        box.addView(top)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progress = percent
        bar.progressTintList = android.content.res.ColorStateList.valueOf(Monitor.statusColor(percent, greenLimit, yellowLimit))
        box.addView(bar)

        val detail = TextView(this)
        detail.text = "${Monitor.format(used)} usados de ${Monitor.format(total)}"
        detail.textSize = 14f
        detail.setTextColor(Color.rgb(69, 80, 100))
        box.addView(detail)

        return box
    }

    private fun rounded(bg: Int, stroke: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(bg)
        d.cornerRadius = radius.toFloat()
        d.setStroke(dp(1), stroke)
        return d
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

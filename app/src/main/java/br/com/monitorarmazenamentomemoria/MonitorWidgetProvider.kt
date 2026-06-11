package br.com.monitorarmazenamentomemoria

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class MonitorWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) updateAll(context)
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "br.com.monitorarmazenamentomemoria.REFRESH_WIDGET"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MonitorWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun isDark(context: Context): Boolean {
            val prefs = context.getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
            return when (prefs.getString("themeMode", "auto") ?: "auto") {
                "dark" -> true
                "light" -> false
                else -> {
                    val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    night == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
            val greenLimit = prefs.getInt("greenLimit", 89)
            val yellowLimit = prefs.getInt("yellowLimit", 96)
            val data = Monitor.read(context)

            val dark = isDark(context)
            val views = RemoteViews(context.packageName, R.layout.widget_monitor)

            val bg = if (dark) Color.rgb(25, 31, 48) else Color.WHITE
            val main = if (dark) Color.WHITE else Color.rgb(14, 26, 56)
            val sub = if (dark) Color.rgb(190, 200, 220) else Color.rgb(69, 80, 100)

            views.setInt(R.id.widgetRoot, "setBackgroundColor", bg)
            views.setTextColor(R.id.widgetTitle, main)
            views.setTextColor(R.id.widgetFree, sub)
            views.setTextColor(R.id.widgetUpdated, Color.rgb(35, 92, 170))

            views.setTextViewText(R.id.widgetTitle, "Monitor")
            views.setTextViewText(R.id.widgetStorage, "ARM ${data.storageUsedPercent}% • ${Monitor.statusLabel(data.storageUsedPercent, greenLimit, yellowLimit)}")
            views.setTextViewText(R.id.widgetMemory, "RAM ${data.memoryUsedPercent}% • ${Monitor.statusLabel(data.memoryUsedPercent, greenLimit, yellowLimit)}")
            views.setTextViewText(R.id.widgetFree, "Livre: ${Monitor.format(data.storageFree)}")
            views.setTextViewText(R.id.widgetUpdated, "toque para abrir • ${Monitor.shortTime(data.readAt)}")

            views.setTextColor(R.id.widgetStorage, Monitor.statusColor(data.storageUsedPercent, greenLimit, yellowLimit))
            views.setTextColor(R.id.widgetMemory, Monitor.statusColor(data.memoryUsedPercent, greenLimit, yellowLimit))

            val panelIntent = Intent(context, MonitorPanelActivity::class.java)
            val panelPendingIntent = PendingIntent.getActivity(context, 2101, panelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widgetRoot, panelPendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }
}

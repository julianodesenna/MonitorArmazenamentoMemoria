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
        for (id in ids) {
            updateWidget(context, manager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_REFRESH_WIDGET) {
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "br.com.monitorarmazenamentomemoria.REFRESH_WIDGET"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MonitorWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)

            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("monitor_config", Context.MODE_PRIVATE)
            val greenLimit = prefs.getInt("greenLimit", 89)
            val yellowLimit = prefs.getInt("yellowLimit", 96)

            val data = Monitor.read(context)

            val views = RemoteViews(context.packageName, R.layout.widget_monitor)

            val storageStatus = Monitor.statusLabel(data.storageUsedPercent, greenLimit, yellowLimit)
            val memoryStatus = Monitor.statusLabel(data.memoryUsedPercent, greenLimit, yellowLimit)

            val storageColor = Monitor.statusColor(data.storageUsedPercent, greenLimit, yellowLimit)
            val memoryColor = Monitor.statusColor(data.memoryUsedPercent, greenLimit, yellowLimit)

            views.setTextViewText(R.id.widgetTitle, "Monitor do aparelho")
            views.setTextViewText(R.id.widgetStorage, "Armaz. ${data.storageUsedPercent}% • $storageStatus")
            views.setTextViewText(R.id.widgetMemory, "RAM ${data.memoryUsedPercent}% • $memoryStatus")
            views.setTextViewText(R.id.widgetFree, "Livre: ${Monitor.format(data.storageFree)} no aparelho")
            views.setTextViewText(R.id.widgetUpdated, "Atualizado: ${Monitor.time(data.readAt)}")

            views.setTextColor(R.id.widgetStorage, storageColor)
            views.setTextColor(R.id.widgetMemory, memoryColor)

            views.setProgressBar(R.id.widgetStorageBar, 100, data.storageUsedPercent, false)
            views.setProgressBar(R.id.widgetMemoryBar, 100, data.memoryUsedPercent, false)

            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context,
                2001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widgetRoot, openPendingIntent)

            val refreshIntent = Intent(context, MonitorWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }

            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                2002,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widgetRefresh, refreshPendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }
}

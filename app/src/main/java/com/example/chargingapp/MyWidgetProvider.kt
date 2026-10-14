package com.example.chargingapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        /**
         * 供 App 内部调用的全局刷新方法。
         * 每次数据变化后调用一次。
         */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, MyWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 查本月统计
            val db = DatabaseHelper(context)
            val all = db.getAll()

            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val prefix = "${year}年${month.toString().padStart(2, '0')}月"

            val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
            var count = 0
            var total = 0.0
            for (r in all) {
                try {
                    val d = fmt.parse(r.date) ?: continue
                    val c = Calendar.getInstance().apply { time = d }
                    if (c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) + 1 == month) {
                        count++
                        total += r.amount
                    }
                } catch (_: Exception) { }
            }

            views.setTextViewText(R.id.widget_title, "$prefix 充电统计")
            views.setTextViewText(R.id.widget_count, "次数: $count")
            views.setTextViewText(R.id.widget_amount, "¥%.2f".format(total))

            // 点击小部件打开 App
            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}

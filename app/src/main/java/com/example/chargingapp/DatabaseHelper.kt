package com.example.chargingapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Record(
    val id: Long,
    val date: String,
    val amount: Double
)

data class StatItem(
    val period: String,
    val count: Int,
    val total: Double
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "charging.db"
        const val DB_VERSION = 1
        const val TABLE = "records"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                amount REAL NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(date: String, amount: Double): Long {
        val values = ContentValues().apply {
            put("date", date)
            put("amount", amount)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    fun insertBatch(records: List<Pair<String, Double>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((date, amount) in records) {
                val values = ContentValues().apply {
                    put("date", date)
                    put("amount", amount)
                }
                db.insert(TABLE, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAll(): List<Record> {
        val list = mutableListOf<Record>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, date, amount FROM $TABLE ORDER BY date DESC",
            null
        )
        while (cursor.moveToNext()) {
            list.add(Record(cursor.getLong(0), cursor.getString(1), cursor.getDouble(2)))
        }
        cursor.close()
        return list
    }

    fun getStats(period: String): List<StatItem> {
        val all = getAll()
        val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
        val grouped = all.groupBy { r ->
            try {
                val d = fmt.parse(r.date) ?: return@groupBy "未知"
                val cal = Calendar.getInstance().apply { time = d }
                when (period) {
                    "周" -> {
                        val week = cal.get(Calendar.WEEK_OF_YEAR)
                        // 补零，保证字符串排序正确
                        "${cal.get(Calendar.YEAR)}年第${week.toString().padStart(2, '0')}周"
                    }
                    "月" -> {
                        val m = cal.get(Calendar.MONTH) + 1
                        "${cal.get(Calendar.YEAR)}年${m.toString().padStart(2, '0')}月"
                    }
                    "季度" -> {
                        val q = (cal.get(Calendar.MONTH) / 3) + 1
                        "${cal.get(Calendar.YEAR)}年第${q}季度"
                    }
                    "年" -> "${cal.get(Calendar.YEAR)}年"
                    else -> "未知"
                }
            } catch (e: Exception) {
                "未知"
            }
        }
        return grouped.map { (k, v) ->
            StatItem(k, v.size, v.sumOf { it.amount })
        }.sortedByDescending { it.period }
    }

    fun exportTo(file: File) {
        val all = getAll()
        file.outputStream().bufferedWriter().use { w ->
            w.write("id\tdate\tamount\n")
            for (r in all) {
                w.write("${r.id}\t${r.date}\t${r.amount}\n")
            }
        }
    }

    fun importFrom(file: File) {
        val lines = file.readLines()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, null, null)
            for (line in lines.drop(1)) {
                val parts = line.split("\t")
                if (parts.size >= 3) {
                    val values = ContentValues().apply {
                        put("date", parts[1])
                        put("amount", parts[2].toDoubleOrNull() ?: 0.0)
                    }
                    db.insert(TABLE, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

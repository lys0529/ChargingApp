package com.example.chargingapp

import android.content.Context
import android.net.Uri
import android.util.Log
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.SimpleDateFormat
import java.util.Locale

object ExcelHelper {
    private const val TAG = "ExcelHelper"

    fun importFromExcel(context: Context, uri: Uri): List<Pair<String, Double>> {
        val records = mutableListOf<Pair<String, Double>>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val wb = WorkbookFactory.create(input)
                val sheet = wb.getSheetAt(0)
                Log.d(TAG, "lastRowNum=${sheet.lastRowNum}")

                for (i in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(i)
                    if (row == null) {
                        Log.d(TAG, "Row $i: null")
                        continue
                    }
                    val dateCell = row.getCell(0)
                    val amountCell = row.getCell(1)
                    if (dateCell == null || amountCell == null) {
                        Log.d(TAG, "Row $i: dateCell or amountCell null")
                        continue
                    }

                    val dateStr = parseDateCell(dateCell)
                    val amount = parseAmountCell(amountCell)

                    Log.d(TAG, "Row $i: type=${dateCell.cellType} parsed='$dateStr' amount=$amount")

                    if (dateStr.isNullOrBlank()) continue
                    if (!looksLikeDate(dateStr)) continue

                    records.add(dateStr to amount)
                }
                wb.close()
            } ?: Log.e(TAG, "openInputStream null")
        } catch (e: Exception) {
            Log.e(TAG, "import error", e)
        }
        Log.d(TAG, "Total = ${records.size}")
        return records
    }

    private fun parseDateCell(cell: org.apache.poi.ss.usermodel.Cell): String? {
        return when (cell.cellType) {
            CellType.STRING -> normalizeDate(cell.stringCellValue)
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
                        .format(cell.dateCellValue)
                } else {
                    val num = cell.numericCellValue
                    val asLong = num.toLong()
                    val s = asLong.toString()
                    if (s.length == 8) {
                        "${s.substring(0, 4)}.${s.substring(4, 6)}.${s.substring(6, 8)}"
                    } else if (num > 20000 && num < 60000) {
                        val date = DateUtil.getJavaDate(num)
                        SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(date)
                    } else null
                }
            }
            CellType.FORMULA -> {
                try {
                    normalizeDate(cell.stringCellValue)
                } catch (e: Exception) {
                    try {
                        val d = cell.numericCellValue
                        val date = DateUtil.getJavaDate(d)
                        SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(date)
                    } catch (e2: Exception) {
                        null
                    }
                }
            }
            else -> null
        }
    }

    private fun normalizeDate(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val cn = Regex("""(\d{4})\D+(\d{1,2})\D+(\d{1,2})\D*""")
        val m = cn.find(t) ?: return null
        val y = m.groupValues[1]
        val mo = m.groupValues[2].padStart(2, '0')
        val d = m.groupValues[3].padStart(2, '0')
        return "$y.$mo.$d"
    }

    private fun parseAmountCell(cell: org.apache.poi.ss.usermodel.Cell): Double {
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> {
                cell.stringCellValue
                    .replace("¥", "")
                    .replace(",", "")
                    .trim()
                    .toDoubleOrNull() ?: 0.0
            }
            CellType.FORMULA -> try { cell.numericCellValue } catch (e: Exception) { 0.0 }
            else -> 0.0
        }
    }

    private fun looksLikeDate(s: String): Boolean {
        return Regex("""^\d{4}\.\d{2}\.\d{2}$""").matches(s)
    }
}

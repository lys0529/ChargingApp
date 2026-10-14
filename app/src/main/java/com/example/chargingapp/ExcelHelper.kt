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

    // 你的表格: A列=序号, B列=日期, C列=金额
    private const val COL_DATE = 1
    private const val COL_AMOUNT = 2

    fun importFromExcel(context: Context, uri: Uri): List<Pair<String, Double>> {
        val records = mutableListOf<Pair<String, Double>>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val wb = WorkbookFactory.create(input)
                val evaluator = wb.creationHelper.createFormulaEvaluator()
                val sheet = wb.getSheetAt(0)
                Log.d(TAG, "lastRowNum=${sheet.lastRowNum}")

                for (i in 0..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val dateCell = row.getCell(COL_DATE) ?: continue
                    val amountCell = row.getCell(COL_AMOUNT) ?: continue

                    // 先算公式
                    val evalDate = if (dateCell.cellType == CellType.FORMULA) evaluator.evaluate(dateCell) else dateCell
                    val evalAmount = if (amountCell.cellType == CellType.FORMULA) evaluator.evaluate(amountCell) else amountCell

                    val dateStr = parseDateCell(evalDate)
                    val amount = parseAmountCell(evalAmount)

                    Log.d(TAG, "Row $i: rawDateType=${dateCell.cellType} date='$dateStr' amount=$amount")

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

    private fun parseDateCell(cell: org.apache.poi.ss.usermodel.Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> normalizeDate(cell.stringCellValue)
            CellType.NUMERIC -> {
                val num = cell.numericCellValue
                when {
                    // 情况1: 是 Excel 日期格式
                    DateUtil.isCellDateFormatted(cell) -> {
                        SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())
                            .format(cell.dateCellValue)
                    }
                    // 情况2: 8位整数 20250325
                    num >= 19000101 && num <= 21001231 -> {
                        val s = num.toLong().toString()
                        "${s.substring(0, 4)}.${s.substring(4, 6)}.${s.substring(6, 8)}"
                    }
                    // 情况3: Excel 日期序列号 (1900-01-01=1)
                    num > 20000 && num < 80000 -> {
                        val date = DateUtil.getJavaDate(num)
                        SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(date)
                    }
                    else -> null
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

    private fun parseAmountCell(cell: org.apache.poi.ss.usermodel.Cell?): Double {
        if (cell == null) return 0.0
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue
                .replace("¥", "")
                .replace(",", "")
                .trim()
                .toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun looksLikeDate(s: String): Boolean {
        return Regex("""^\d{4}\.\d{2}\.\d{2}$""").matches(s)
    }
}

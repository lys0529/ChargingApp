package com.example.chargingapp

import android.content.Context
import android.net.Uri
import android.util.Log
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.CellValue
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaEvaluator
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

                    val dateStr = parseDateCell(dateCell, evaluator)
                    val amount = parseAmountCell(amountCell, evaluator)

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

    private fun parseDateCell(cell: Cell, evaluator: FormulaEvaluator): String? {
        val type = cell.cellType
        return when (type) {
            CellType.STRING -> normalizeDate(cell.stringCellValue)
            CellType.NUMERIC -> parseNumericDate(cell)
            CellType.FORMULA -> {
                // 用 evaluator 求出公式结果
                val value: CellValue = evaluator.evaluate(cell)
                if (value == null) return null
                when (value.cellType) {
                    CellType.STRING -> normalizeDate(value.stringValue)
                    CellType.NUMERIC -> {
                        // CellValue 的数字，尝试按 8 位整数和序列号解释
                        parseNumericValue(value.numberValue)
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun parseNumericDate(cell: Cell): String? {
        return if (DateUtil.isCellDateFormatted(cell)) {
            SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(cell.dateCellValue)
        } else {
            parseNumericValue(cell.numericCellValue)
        }
    }

    private fun parseNumericValue(num: Double): String? {
        return when {
            // 8位整数 20250325
            num >= 19000101 && num <= 21001231 -> {
                val s = num.toLong().toString()
                "${s.substring(0, 4)}.${s.substring(4, 6)}.${s.substring(6, 8)}"
            }
            // Excel 日期序列号
            num > 20000 && num < 80000 -> {
                val date = DateUtil.getJavaDate(num)
                SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(date)
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

    private fun parseAmountCell(cell: Cell, evaluator: FormulaEvaluator): Double {
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue
                .replace("¥", "")
                .replace(",", "")
                .trim()
                .toDoubleOrNull() ?: 0.0
            CellType.FORMULA -> {
                val value: CellValue = evaluator.evaluate(cell)
                if (value == null) 0.0
                else when (value.cellType) {
                    CellType.NUMERIC -> value.numberValue
                    CellType.STRING -> value.stringValue.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }
            else -> 0.0
        }
    }

    private fun looksLikeDate(s: String): Boolean {
        return Regex("""^\d{4}\.\d{2}\.\d{2}$""").matches(s)
    }
}

package com.example.chargingapp

import android.content.Context
import android.net.Uri
import android.util.Log
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.SimpleDateFormat

object ExcelHelper {
    private const val TAG = "ExcelHelper"

    fun importFromExcel(context: Context, uri: Uri): List<Pair<String, Double>> {
        val records = mutableListOf<Pair<String, Double>>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val wb = WorkbookFactory.create(input)
                Log.d(TAG, "Sheet count = ${wb.numberOfSheets}")
                val sheet = wb.getSheetAt(0)
                Log.d(TAG, "Sheet name = ${sheet.sheetName}")
                Log.d(TAG, "lastRowNum = ${sheet.lastRowNum}")
                Log.d(TAG, "firstRowNum = ${sheet.firstRowNum}")

                for (i in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: run {
                        Log.d(TAG, "Row $i is null, skip")
                        continue
                    }
                    val dateCell = row.getCell(0)
                    val amountCell = row.getCell(1)

                    Log.d(TAG, "Row $i dateCellType = ${dateCell?.cellType}, amountCellType = ${amountCell?.cellType}")

                    val dateStr = when (dateCell?.cellType) {
                        CellType.STRING -> dateCell.stringCellValue
                        CellType.NUMERIC -> {
                            if (DateUtil.isCellDateFormatted(dateCell)) {
                                SimpleDateFormat("yyyy.MM.dd").format(dateCell.dateCellValue)
                            } else {
                                // 数字型日期，尝试自己解析（比如 20250325）
                                val num = dateCell.numericCellValue
                                Log.d(TAG, "Row $i numeric date = $num")
                                val s = num.toLong().toString()
                                if (s.length == 8) {
                                    "${s.substring(0,4)}.${s.substring(4,6)}.${s.substring(6,8)}"
                                } else s
                            }
                        }
                        CellType.FORMULA -> dateCell.stringCellValue
                        else -> ""
                    }

                    val amount = when (amountCell?.cellType) {
                        CellType.NUMERIC -> amountCell.numericCellValue
                        CellType.STRING -> amountCell.stringCellValue.toDoubleOrNull() ?: 0.0
                        CellType.FORMULA -> amountCell.numericCellValue
                        else -> 0.0
                    }

                    Log.d(TAG, "Row $i parsed: date='$dateStr', amount=$amount")

                    if (dateStr.isNotBlank()) {
                        records.add(dateStr to amount)
                    }
                }
                wb.close()
            } ?: Log.e(TAG, "openInputStream returned null")
        } catch (e: Exception) {
            Log.e(TAG, "importFromExcel error", e)
        }
        Log.d(TAG, "Total parsed records = ${records.size}")
        return records
    }
}

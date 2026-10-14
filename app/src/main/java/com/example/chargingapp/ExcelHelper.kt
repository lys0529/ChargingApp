package com.example.chargingapp

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.SimpleDateFormat

object ExcelHelper {
    fun importFromExcel(context: Context, uri: Uri): List<Pair<String, Double>> {
        val records = mutableListOf<Pair<String, Double>>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val wb = WorkbookFactory.create(input)
                val sheet = wb.getSheetAt(0)
                for (i in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    val dateCell = row.getCell(0)
                    val amountCell = row.getCell(1)
                    val dateStr = when (dateCell?.cellType) {
                        CellType.STRING -> dateCell.stringCellValue
                        CellType.NUMERIC -> {
                            if (DateUtil.isCellDateFormatted(dateCell)) {
                                SimpleDateFormat("yyyy.MM.dd").format(dateCell.dateCellValue)
                            } else dateCell.numericCellValue.toString()
                        }
                        else -> ""
                    }
                    val amount = when (amountCell?.cellType) {
                        CellType.NUMERIC -> amountCell.numericCellValue
                        CellType.STRING -> amountCell.stringCellValue.toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                    if (dateStr.isNotBlank()) records.add(dateStr to amount)
                }
                wb.close()
            }
        } catch (e: Exception) { e.printStackTrace() }
        return records
    }
}

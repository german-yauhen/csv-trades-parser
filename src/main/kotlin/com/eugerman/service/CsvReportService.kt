package com.eugerman.service

import Trade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

class CsvReportService {

    private val cellTradeFunctions: Map<String, Pair<CellType, (Trade) -> Any>> = mapOf(
        ACTION to Pair(CellType.STRING, Trade::eventType),
        DATE to Pair(CellType.STRING, Trade::tradeDate),
        PRICE to Pair(CellType.NUMERIC, Trade::price),
        QUANTITY to Pair(CellType.NUMERIC, Trade::quantity),
        TOTAL to Pair(CellType.NUMERIC, Trade::total),
        FEE to Pair(CellType.NUMERIC, Trade::fee),
        ORDER to Pair(CellType.NUMERIC, Trade::bookedAmount),
        EXR to Pair(CellType.NUMERIC, Trade::plnExchangeRate),
        EXR_DATE to Pair(CellType.STRING, Trade::plnExchangeRateDate),
        TOTAL_PLN to Pair(CellType.NUMERIC) { trade -> trade.total.times(trade.plnExchangeRate) },
        FEE_PLN to Pair(CellType.NUMERIC) { trade -> trade.fee.times(trade.plnExchangeRate) },
        ORDER_PLN to Pair(CellType.NUMERIC) { trade -> trade.bookedAmount.times(trade.plnExchangeRate) }
    )

    private val summaryCells = listOf(QUANTITY, TOTAL, FEE, ORDER, TOTAL_PLN, FEE_PLN, ORDER_PLN)

    suspend fun createReport(trades: List<Trade>): File {
        val groupBy: Map<String, List<Trade>> = trades.groupBy { it.symbol }
        val file = File("src/main/resources/report${System.currentTimeMillis()}.xlsx")
        XSSFWorkbook().use {
            groupBy.forEach { group -> toExcelSheet(it, group) }
            withContext(Dispatchers.IO) {
                FileOutputStream(file).use { fileOut ->
                    it.write(fileOut)
                }
            }
        }
        return file
    }

    private fun toExcelSheet(workbook: XSSFWorkbook, shareEntry: Map.Entry<String, List<Trade>>): Sheet {
        val sheet = workbook.createSheet(shareEntry.key)
        val arial12 = workbook.createFont().apply {
            fontHeightInPoints = 12
        }
        val cellStyle = workbook.createCellStyle().apply {
            setFont(arial12)
            setAlignment(HorizontalAlignment.CENTER)
        }
        val headerRow = sheet.createRow(0)
        for ((index, cellTradeFunction) in cellTradeFunctions.entries.withIndex()) {
            headerRow.createCell(index).apply {
                setCellStyle(cellStyle)
                setCellValue(cellTradeFunction.key)
            }
            sheet.setColumnWidth(index, 14 * 256)
        }

        sheet.createFreezePane(0, 1)

        val trades = shareEntry.value
        for ((index, trade) in trades.sortedBy { it.tradeDate }.withIndex()) {
            val tradeRow = sheet.createRow(headerRow.rowNum.plus(index.plus(1)))
            for ((index, cellTradeFunction) in cellTradeFunctions.entries.withIndex()) {
                tradeRow.createCell(index).apply {
                    val (type, tradeFun) = cellTradeFunction.value
                    val value = tradeFun(trade)
                    setCellStyle(cellStyle)
                    setCellType(type)
                    if (type == CellType.NUMERIC) {
                        setCellValue(value.toString().toDouble())
                    } else {
                        setCellValue(value.toString())
                    }
                }
            }
        }
        val summaryRow = sheet.createRow(sheet.lastRowNum.plus(1))
        val summaryCellsWithIndex = cellTradeFunctions.entries
            .mapIndexed { index, entry -> entry.key to index }
            .filter { (cellName, _) -> cellName in summaryCells }
            .toMap()
        summaryRow.createCell(0).apply {
            setCellStyle(cellStyle)
            setCellValue(SUMMARY)
        }
        for (summaryCellWithIndex in summaryCellsWithIndex) {
            val columnIndex = summaryCellWithIndex.value
            val columnLetter = CellReference.convertNumToColString(columnIndex)
            val formula = "SUM(${columnLetter}${headerRow.rowNum + 2}:${columnLetter}${trades.size + 1})"
            summaryRow.createCell(columnIndex).apply {
                setCellType(CellType.NUMERIC)
                setCellStyle(cellStyle)
                setCellFormula(formula)
            }
        }
        return sheet
    }

    companion object {
        private const val ACTION = "Action"
        private const val DATE = "Date"
        private const val PRICE = "Price"
        private const val QUANTITY = "Quantity"
        private const val TOTAL = "Total $"
        private const val FEE = "Fee $"
        private const val ORDER = "Order $"
        private const val EXR = "EXR"
        private const val EXR_DATE = "EXR Date"
        private const val TOTAL_PLN = "Total PLN"
        private const val FEE_PLN = "Fee PLN"
        private const val ORDER_PLN = "Order PLN"
        private const val SUMMARY = "Summary"
    }
}

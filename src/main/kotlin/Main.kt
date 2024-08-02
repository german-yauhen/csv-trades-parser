import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private val objectMapper = ObjectMapper().registerKotlinModule()
private val client = OkHttpClient()
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

fun main() {
    val path = Paths.get("src/main/resources/trades.csv")
    val reader = Files.newBufferedReader(path)
    val csvFormat = CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(true)
        .build()
    val csvParser = csvFormat.parse(reader)
    val trades = csvParser.filterNotNull().filter { it["Type"] == "Trade" }.map { createTradeFromRecord(it) }.toList()
    val groupBy: Map<String, List<Trade>> = trades.groupBy { it.symbol }
    val workbook = XSSFWorkbook()
    groupBy.forEach { toExcelSheet(workbook, it) }
    FileOutputStream("src/main/resources/${System.currentTimeMillis()}.xlsx").use { fileOut ->
        workbook.write(fileOut)
    }
    workbook.close()
}

private fun toExcelSheet(workbook: XSSFWorkbook, shareEntry: Map.Entry<String, List<Trade>>): Sheet {
    val sheet = workbook.createSheet(shareEntry.key)
    val arial12 = workbook.createFont().apply {
        fontName = "Arial"
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
//        sheet.setColumnWidth(index, 128)
    }
    val trades = shareEntry.value
    for ((index, trade) in trades.withIndex()) {
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
        .filter { (cellName, _) ->  cellName in summaryCells }
        .toMap()
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

private fun createTradeFromRecord(record: CSVRecord): Trade {
    val (eventType, quantity, price) = extractEventData(record["Event"])
    val tradeDate = LocalDate.parse(record["Trade Date"], DateTimeFormatter.ofPattern("dd-MMM-yyyy"))
    val currency = record["Instrument currency"]
    val (previousWorkingDate, exchangeRate) = getExchangeRateOfPreviousWorkingDate(currency, tradeDate)
    val bookedAmount = record["Booked Amount"].toDouble().absoluteValue
    val total = price.times(quantity)
    val trade = Trade(
        tradeDate = tradeDate,
        instrument = record["Instrument"],
        isin = record["Instrument ISIN"],
        currency = currency,
        exchange = record["Exchange Description"],
        symbol = record["Instrument Symbol"].substringBefore(":"),
        eventType = eventType,
        quantity = quantity,
        price = price,
        total = total,
        bookedAmount = bookedAmount,
        fee = bookedAmount.minus(total).toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toDouble(),
        plnExchangeRateDate = previousWorkingDate,
        plnExchangeRate = exchangeRate
    )
    return trade
}

private fun extractEventData(event: String): Triple<String, Int, Double> {
    val regex = """(Buy|Sell)\s+(\d+)\s+@\s+([\d.]+)""".toRegex()
    val matchResult = regex.find(event)
    return if (matchResult != null) {
        val (action, quantity, price) = matchResult.destructured
        Triple(action, quantity.toInt(), price.toDouble())
    } else {
        Triple(event, 0, 0.0)
    }
}

private fun getExchangeRateOfPreviousWorkingDate(currency: String, tradeDate: LocalDate): Pair<LocalDate, Double> {
    var previousWorkingDate = getPreviousWorkingDate(tradeDate)
    var exchangeRate: Double? = getExchangeRate(currency, previousWorkingDate)
    while (exchangeRate == null) {
        previousWorkingDate = getPreviousWorkingDate(previousWorkingDate)
        exchangeRate = getExchangeRate(currency, previousWorkingDate)
    }
    return Pair(previousWorkingDate, exchangeRate)
}

private fun getPreviousWorkingDate(tradeDate: LocalDate) = when (tradeDate.dayOfWeek) {
    DayOfWeek.MONDAY -> tradeDate.minusDays(3)
    DayOfWeek.SUNDAY -> tradeDate.minusDays(2)
    else -> tradeDate.minusDays(1)
}

private fun getExchangeRate(currency: String, currencyRateDate: LocalDate): Double? {
    val date = DateTimeFormatter.ISO_DATE.format(currencyRateDate)
    val plnUrl = "http://api.nbp.pl/api/exchangerates/rates/a/$currency/$date"
    val request = Request.Builder()
        .get()
        .url(plnUrl)
        .header("Accept", "application/json")
        .build()
    client.newCall(request).execute()
        .takeIf { it.isSuccessful }
        .use {
            val json = objectMapper.readTree(it?.body?.string())
            return json?.get("rates")?.first()?.get("mid")?.asDouble()
        }
}


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.AbstractMap.SimpleEntry
import kotlin.math.absoluteValue

private val objectMapper = ObjectMapper().registerKotlinModule()
private val client = OkHttpClient()

private val cellNames = arrayOf(
    "Buy / Dividends / Sell",
    "Date",
    "Price",
    "Quantity",
    "Total $",
    "Fee $",
    "Order $",
    "Exchange Rate USD/PLN",
    "Exchange Rate Date",
    "Total PLN",
    "Fee PLN",
    "Order PLN"
)

private val cellTradeFunctions: Map<String, (Trade) -> Any> = mapOf(
    "Buy / Dividends / Sell" to Trade::eventType,
    "Date" to Trade::tradeDate,
    "Price" to Trade::price,
    "Quantity" to Trade::quantity,
    "Total $" to Trade::total,
    "Fee $" to Trade::fee,
    "Order $" to Trade::bookedAmount,
    "Exchange Rate USD/PLN" to Trade::plnExchangeRate,
    "Exchange Rate Date" to Trade::plnExchangeRateDate,
    "Total PLN" to { trade -> trade.total.times(trade.plnExchangeRate) },
    "Fee PLN" to { trade -> trade.fee.times(trade.plnExchangeRate) },
    "Order PLN" to { trade -> trade.bookedAmount.times(trade.plnExchangeRate) }
)

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

//    val groupBy: Map<String, List<Trade>> = trades.groupBy { it.symbol }
    val groupBy: Map<String, List<Trade>> = trades.first().let {
        mapOf(it.symbol to listOf(it))
    }

    val workbook = XSSFWorkbook()

    groupBy.forEach {
        toExcelSheet(workbook, it)
    }

    FileOutputStream("src/main/resources/result.xlsx").use { fileOut ->
        workbook.write(fileOut)
    }
    workbook.close()
}

private fun toExcelSheet(workbook: XSSFWorkbook, shareEntry: Map.Entry<String, List<Trade>>): Sheet {
    val sheet = workbook.createSheet(shareEntry.key)
    val headerRow = sheet.createRow(0)
    for ((index, cellTradeFunction) in cellTradeFunctions.entries.withIndex()) {
        headerRow.createCell(index).also {
            it.setCellValue(cellTradeFunction.key)
        }
    }
    for ((index, trade) in shareEntry.value.withIndex()) {
        val tradeRow = sheet.createRow(headerRow.rowNum.plus(index.plus(1)))
        for ((index, cellTradeFunction) in cellTradeFunctions.entries.withIndex()) {
            tradeRow.createCell(index, CellType.STRING).also {
                it.setCellValue(cellTradeFunction.value(trade).toString())
            }
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


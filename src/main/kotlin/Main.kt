import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Paths
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private val objectMapper = ObjectMapper().registerKotlinModule()
private val client = OkHttpClient()

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

    trades.groupBy { it.symbol }
}

private fun createTradeFromRecord(record: CSVRecord): Trade {
    val (eventType, quantity, price) = extractEventData(record["Event"])
    val tradeDate = LocalDate.parse(record["Trade Date"], DateTimeFormatter.ofPattern("dd-MMM-yyyy"))
    val currency = record["Instrument currency"]
    val total = record["Booked Amount"].toDouble().absoluteValue
    val trade = Trade(
        tradeDate = tradeDate,
        instrument = record["Instrument"],
        isin = record["Instrument ISIN"],
        currency = currency,
        exchange = record["Exchange Description"],
        symbol = record["Instrument Symbol"].substringBefore(":"),
        total = total,
        eventType = eventType,
        quantity = quantity,
        price = price,
        plnExchangeRate = getExchangeRateOfPreviousWorkingDate(currency, tradeDate),
        fee = total.minus(price.times(quantity)).toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toDouble()
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

private fun getExchangeRateOfPreviousWorkingDate(currency: String, tradeDate: LocalDate): Double {
    var previousWorkingDate = getPreviousWorkingDate(tradeDate)
    var exchangeRate: Double? = getExchangeRate(currency, previousWorkingDate)
    while (exchangeRate == null) {
        previousWorkingDate = getPreviousWorkingDate(previousWorkingDate)
        exchangeRate = getExchangeRate(currency, previousWorkingDate)
    }
    return exchangeRate
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

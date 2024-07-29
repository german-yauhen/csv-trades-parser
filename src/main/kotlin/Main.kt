import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun main() {
    val path = Paths.get("src/main/resources/trades.csv")
    val reader = Files.newBufferedReader(path)
    val csvFormat = CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(true)
        .build()

    val csvParser = csvFormat.parse(reader)

    val list = csvParser.map(::createTradeFromRecord).toList()

    list.forEach {x -> println(x)}
}

private fun createTradeFromRecord(record: CSVRecord): Trade {
    val (eventType, quantity, price) = extractEventData(record["Event"])
    val trade = Trade(
        tradeDate = LocalDate.parse(record["Trade Date"], DateTimeFormatter.ofPattern("dd-MMM-yyyy")),
        type = record["Type"],
        instrument = record["Instrument"],
        instrumentIsin = record["Instrument ISIN"],
        instrumentCurrency = record["Instrument currency"],
        exchangeDescription = record["Exchange Description"],
        instrumentSymbol = record["Instrument Symbol"]?.substringBefore(":"),
        eventType = eventType,
        quantity = quantity,
        price = price,
        bookedAmount = record["Booked Amount"].toDouble()
    )
    return trade
}

private fun extractEventData(event: String): Triple<String, Int?, Double?> {
    val regex = """(Buy|Sell)\s+(\d+)\s+@\s+([\d.]+)""".toRegex()
    val matchResult = regex.find(event)
    return if (matchResult != null) {
        val (action, quantity, price) = matchResult.destructured
        Triple(action, quantity.toInt(), price.toDouble())
    } else {
        Triple(event, null, null)
    }
}

private fun getExchangeRate(currency: String, tradeDate: LocalDate): Double {
    val date = DateTimeFormatter.ISO_DATE.format(tradeDate)
    val plnUrl = "http://api.nbp.pl/api/exchangerates/rates/a/$currency/$date/?format=json"

//    val client = OkHttpClient()
    return 0.0
}
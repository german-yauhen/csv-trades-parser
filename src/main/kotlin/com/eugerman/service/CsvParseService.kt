package com.eugerman.service

import Trade
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

class CsvParseService {

    private val exchangeRateService = PlnExchangeRateService()

    private val csvFormat = CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(true)
        .build()

    suspend fun parse(reader: Reader): List<Trade> =
        csvFormat.parse(reader)
            .filterNotNull()
            .filter { it["Type"] == "Trade" }
            .map { createTradeFromRecord(it) }
            .toList()

    private suspend fun createTradeFromRecord(record: CSVRecord): Trade {
        val (eventType, quantity, price) = extractEventData(record["Event"])
        val tradeDate = LocalDate.parse(record["Trade Date"], DateTimeFormatter.ofPattern("dd-MMM-yyyy"))
        val currency = record["Instrument currency"]
        val (previousWorkingDate, exchangeRate) =
            exchangeRateService.getExchangeRateOfPreviousWorkingDate(currency, tradeDate)
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
}
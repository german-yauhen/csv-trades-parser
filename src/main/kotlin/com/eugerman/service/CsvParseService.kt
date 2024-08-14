package com.eugerman.service

import Trade
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
        val conversionRate = record["Conversion Rate"].toBigDecimal()
        val amount = if (BigDecimal.ONE == conversionRate) {
            record["Amount"].toBigDecimal()
        } else {
            record["Amount"].toBigDecimal().div(conversionRate).abs()
        }
        val total = price.times(quantity).toBigDecimal()
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
            total = total.setScale(2, RoundingMode.HALF_EVEN).toDouble(),
            amount = amount.setScale(2, RoundingMode.HALF_EVEN).toDouble(),
            fee = amount.minus(total).setScale(2, RoundingMode.HALF_EVEN).toDouble(),
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
package com.eugerman.service

import Trade
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import java.math.BigDecimal
import java.math.MathContext
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

    suspend fun parse(reader: Reader): List<Trade> {
        val csvParser = csvFormat.parse(reader)
        return csvParser
            .filterNotNull()
            .filter { it["Type"] == "Trade" }
            .map { createTradeFromRecord(it) }
            .toList()
    }

    private suspend fun createTradeFromRecord(record: CSVRecord): Trade {
        val quantitySigned = record["Quantity"].toInt()
        val eventType = """^(Buy|Sell)""".toRegex().find(record["Event"])?.value
        if (eventType == null || (eventType == "Buy" && quantitySigned <= 0) || (eventType == "Sell" && quantitySigned >= 0)) {
            throw IllegalArgumentException()
        }
        val quantity = quantitySigned.absoluteValue
        val tradeDate = LocalDate.parse(record["Trade Date"], DateTimeFormatter.ofPattern("dd-MMM-yyyy"))
        val price = record["Price"].filter { it.isDigit() || it == '.' }.toDouble()
        val currency = record["Instrument currency"]
        val (previousWorkingDate, exchangeRate) =
            exchangeRateService.getExchangeRateOfPreviousWorkingDate(currency, tradeDate)
        val conversionRate = record["Conversion Rate"].toBigDecimal()
        val orderPrice = if (BigDecimal.ONE == conversionRate) {
            record["Booked Amount"].toBigDecimal()
        } else {
            record["Booked Amount"].toBigDecimal().divide(conversionRate, MathContext.DECIMAL32)
        }.setScale(2, RoundingMode.HALF_EVEN).toDouble()
        val sharesPrice = price.times(quantity).toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toDouble()
        val fee = record["Total cost"].toDouble().absoluteValue
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
            sharesPrice = sharesPrice,
            orderPrice = orderPrice,
            fee = fee,
            plnExchangeRateDate = previousWorkingDate,
            plnExchangeRate = exchangeRate
        )
        return trade
    }

}
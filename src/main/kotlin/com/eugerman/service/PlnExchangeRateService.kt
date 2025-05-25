package com.eugerman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PlnExchangeRateService {

    @Deprecated("Decide to use Ktor instead")
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Deprecated("Decide to use Ktor instead", ReplaceWith("com.eugerman.service.PlnExchangeRateService.httpClient"))
    private val okHttpClient = OkHttpClient()

    private val httpClient = HttpClient(Java) {
        install(ContentNegotiation) { json() }
    }

    suspend fun getExchangeRateOfPreviousWorkingDate(currency: String, tradeDate: LocalDate): Pair<LocalDate, Double> {
        var previousWorkingDate = getPreviousWorkingDate(tradeDate)
        var exchangeRate: Double? = getExchangeRate(currency, previousWorkingDate)
        while (exchangeRate == null) {
            previousWorkingDate = getPreviousWorkingDate(previousWorkingDate)
            exchangeRate = getExchangeRate(currency, previousWorkingDate)
        }
        return Pair(previousWorkingDate, exchangeRate)
    }

    /*
    The json that contains two fields. The first field named as "holidays" is an array of public holidays in Poland for 2024 in the format "dd-MM-yyyy", the second field named as "format" is the format of the date. Display only pure json response.
     */
    /*
    The json that contains an array of public holidays in Poland for 2024 in the format in the format dd-MM-yyyy. Display only pure json response.
     */
    fun getPreviousWorkingDate(tradeDate: LocalDate) = when (tradeDate.dayOfWeek) {
        DayOfWeek.MONDAY -> tradeDate.minusDays(3)
        DayOfWeek.SUNDAY -> tradeDate.minusDays(2)
        else -> tradeDate.minusDays(1)
    }

    private suspend fun getExchangeRate(currency: String, currencyRateDate: LocalDate): Double? {
        val date = DateTimeFormatter.ISO_DATE.format(currencyRateDate)
        val plnUrl = "http://api.nbp.pl/api/exchangerates/rates/a"
        val httpResponse = httpClient.get(plnUrl) {
            url {
                appendPathSegments(currency, date, encodeSlash = true)
            }
            headers {
                accept(ContentType.parse("application/json"))
            }
        }
        return if (httpResponse.status.isSuccess()) {
            httpResponse.body<JsonObject>()["rates"]!!.jsonArray.first().jsonObject["mid"]!!.jsonPrimitive.double
        } else {
            null
        }
    }

    @Deprecated(
        "Decide to use Ktor instead",
        ReplaceWith("com.eugerman.service.PlnExchangeRateService.getExchangeRate")
    )
    private fun getExchangeRateUsingOkHttp(currency: String, currencyRateDate: LocalDate): Double? {
        val date = DateTimeFormatter.ISO_DATE.format(currencyRateDate)
        val plnUrl = "http://api.nbp.pl/api/exchangerates/rates/a/$currency/$date"
        val request = Request.Builder()
            .get()
            .url(plnUrl)
            .header("Accept", "application/json")
            .build()
        val response = okHttpClient.newCall(request).execute()
        return if (response.isSuccessful) {
            objectMapper.readTree(response.body?.string())!!["rates"].first()!!["mid"].asDouble()
        } else {
            null
        }
    }
}
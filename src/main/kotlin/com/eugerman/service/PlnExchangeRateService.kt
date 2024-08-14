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
        httpResponse
            .takeIf { it.status.isSuccess() }
            ?.body<JsonObject>()
            .let {
                return it!!["rates"]!!.jsonArray.first().jsonObject["mid"]!!.jsonPrimitive.double
            }
    }

    @Deprecated("Decide to use Ktor instead", ReplaceWith("com.eugerman.service.PlnExchangeRateService.getExchangeRate"))
    private fun getExchangeRateUsingOkHttp(currency: String, currencyRateDate: LocalDate): Double? {
        val date = DateTimeFormatter.ISO_DATE.format(currencyRateDate)
        val plnUrl = "http://api.nbp.pl/api/exchangerates/rates/a/$currency/$date"
        val request = Request.Builder()
            .get()
            .url(plnUrl)
            .header("Accept", "application/json")
            .build()
        okHttpClient.newCall(request).execute()
            .takeIf { it.isSuccessful }
            .use {
                val json = objectMapper.readTree(it?.body?.string())
                return json!!["rates"].first()!!["mid"].asDouble()
            }
    }
}
import java.time.LocalDate

data class Trade (
    val tradeDate: LocalDate,
    val instrument: String,
    val isin: String,
    val currency: String,
    val exchange: String,
    val symbol: String,
    val eventType: String,
    val quantity: Int,
    val price: Double,
    val total: Double,
    val bookedAmount: Double,
    val fee: Double,
    val plnExchangeRate: Double,
    val plnExchangeRateDate: LocalDate
)
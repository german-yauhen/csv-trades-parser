import java.time.LocalDate

data class Trade(
    val tradeDate: LocalDate,
    val type: String,
    val instrument: String?,
    val instrumentIsin: String?,
    val instrumentCurrency: String?,
    val exchangeDescription: String?,
    val instrumentSymbol: String?,
    val eventType: String,
    val quantity: Int?,
    val price: Double?,
    val bookedAmount: Double
)
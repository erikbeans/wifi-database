package com.clickbeans.wifidatabase.domain

object DonationPolicy {

    const val MIN_CONFIRMED_WORKING = 3
    const val MAX_ASKS_PER_LIFETIME = 2
    const val CONFIRMS_BETWEEN_ASKS = 10

    fun shouldAsk(
        neverAsk: Boolean,
        askCount: Int,
        successfulConfirms: Int,
        confirmsSinceLastAsk: Int,
    ): Boolean = !neverAsk &&
        askCount < MAX_ASKS_PER_LIFETIME &&
        successfulConfirms >= MIN_CONFIRMED_WORKING &&
        (askCount == 0 || confirmsSinceLastAsk >= CONFIRMS_BETWEEN_ASKS)
}

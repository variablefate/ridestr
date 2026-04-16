package com.ridestr.common.fiat

import com.ridestr.common.nostr.events.FiatFare

fun FiatFare.isUsd(): Boolean = currency.equals("USD", ignoreCase = true)

fun FiatFare.formatDisplayAmount(): String =
    if (isUsd()) {
        "$$amount"
    } else {
        "${currency.uppercase()} $amount"
    }

fun FiatFare.usdAmountOrNull(): Double? = if (isUsd()) amount.toDoubleOrNull() else null

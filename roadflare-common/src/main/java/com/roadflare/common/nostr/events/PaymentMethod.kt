package com.roadflare.common.nostr.events

/**
 * Payment methods supported by RoadFlare.
 * Fiat only — matches ridestr wire format exactly.
 * Crypto methods (CASHU, LIGHTNING, STRIKE) removed per RoadFlare design.
 */
enum class PaymentMethod(val value: String, val displayName: String = value) {
    FIAT_CASH("fiat_cash", "Cash"),
    ZELLE("zelle", "Zelle"),
    PAYPAL("paypal", "PayPal"),
    CASH_APP("cash_app", "Cash App"),
    VENMO("venmo", "Venmo"),
    CASH("cash", "Cash");

    companion object {
        /** Alternate payment methods available for RoadFlare rides */
        val ROADFLARE_ALTERNATE_METHODS = listOf(ZELLE, PAYPAL, CASH_APP, VENMO, CASH)

        fun fromString(s: String): PaymentMethod? =
            entries.find { it.value == s }

        fun fromStringList(list: List<String>): List<PaymentMethod> =
            list.mapNotNull { fromString(it) }

        fun toStringList(methods: List<PaymentMethod>): List<String> =
            methods.map { it.value }

        /**
         * Find the best common fiat payment method by walking the rider's priority list.
         * Returns the first rider method that also appears in the driver's list, or null.
         */
        fun findBestCommonFiatMethod(
            riderMethods: List<String>,
            driverMethods: List<String>
        ): String? {
            val driverSet = driverMethods.map { it.trim().lowercase() }.toSet()
            return riderMethods.firstOrNull { it.trim().lowercase() in driverSet }
        }
    }
}

package com.uhmk.pos.core.model

/**
 * Which selling price a sale line used. Stored per line so historical reports stay correct even
 * after prices change.
 */
enum class PriceTier {
    STUDENT,
    REGULAR;

    companion object {
        fun from(raw: String?): PriceTier = entries.firstOrNull { it.name == raw } ?: STUDENT
    }
}

enum class UserRole {
    ADMIN,
    STAFF;

    val isAdmin: Boolean get() = this == ADMIN

    companion object {
        fun from(raw: String?): UserRole = entries.firstOrNull { it.name == raw } ?: STAFF
    }
}

/** How the order leaves the counter. Historical/tally sales use [UNSPECIFIED]. */
enum class OrderType(val label: String) {
    DINE_IN("Dine-in"),
    TAKEOUT("Takeout"),
    UNSPECIFIED("Not set");

    companion object {
        fun from(raw: String?): OrderType = entries.firstOrNull { it.name == raw } ?: UNSPECIFIED
    }
}

enum class SaleStatus(val label: String) {
    COMPLETED("Completed"),
    VOIDED("Voided"),
    RETURNED("Returned"),
}

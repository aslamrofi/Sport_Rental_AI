package com.sportrental.ai.ui.gallery

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class RentalHistory(
    val itemName: String? = "",
    val location: String? = "",
    val dailyRate: String? = "", // Or Int? if stored as number
    val totalPrice: String? = "",
    val startDate: String? = "",
    val endDate: String? = "",
    val status: String? = "",
    val userId: String? = "",
    val rentalDays: Int? = 0,
    val timestamp: Long? = 0L
) {
    // Firebase needs this empty constructor
    constructor() : this("", "", "", "", "", "", "", "", 0, 0L)
}
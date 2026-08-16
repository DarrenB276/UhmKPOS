package com.uhmk.pos.core.db

import androidx.room.TypeConverter
import com.uhmk.pos.core.model.PriceTier
import com.uhmk.pos.core.model.UserRole

class Converters {
    @TypeConverter
    fun tierToString(value: PriceTier): String = value.name

    @TypeConverter
    fun stringToTier(value: String?): PriceTier = PriceTier.from(value)

    @TypeConverter
    fun roleToString(value: UserRole): String = value.name

    @TypeConverter
    fun stringToRole(value: String?): UserRole = UserRole.from(value)
}

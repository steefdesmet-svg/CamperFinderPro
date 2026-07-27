package com.steefdesmet.camperfinderpro

data class CamperPlace(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val distanceKm: Double,
    val fee: String?,
    val capacity: String?,
    val electricity: String?,
    val water: String?,
    val sanitaryDump: String?,
    val maxstay: String?,
    val website: String?
)

package no.mlz.shortener.routes

import kotlinx.serialization.Serializable

@Serializable
data class CreateLinkRequest(
    val targetUrl: String,
    val expiresAt: String? = null,
)

@Serializable
data class CreateLinkResponse(
    val code: String,
    val shortUrl: String,
    // Shown exactly once; never retrievable again.
    val ownerToken: String,
)

@Serializable
data class DailyCountDto(val date: String, val count: Long)

@Serializable
data class StatsResponse(
    val totalClicks: Long,
    val last7Days: List<DailyCountDto>,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val correlationId: String,
)

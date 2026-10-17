package com.openmaps.app.core.reporting

import com.openmaps.app.core.location.UserLocation

enum class DriverReportType {
    Police,
    Traffic,
    Crash,
    LaneClosed,
    Construction,
    RoadHazard,
    Flooding,
    DisabledVehicle,
    ObjectOnRoad,
}

data class DriverReport(
    val type: DriverReportType,
    val location: UserLocation,
    val createdAtMillis: Long,
)

sealed class DriverReportException(message: String) : Exception(message) {
    data object Unavailable : DriverReportException("Driver reporting provider is not configured")
}

/**
 * Provider-neutral community-report seam. UI code submits only this model; a future moderation/
 * incident provider can be added without changing the navigation feature.
 */
interface DriverReportRepository {
    suspend fun submit(report: DriverReport): Result<Unit>
    suspend fun confirm(reportId: String): Result<Unit>
    suspend fun dismiss(reportId: String): Result<Unit>
}

/** Production-safe default until a real, moderated incident provider is connected. */
class UnavailableDriverReportRepository : DriverReportRepository {
    override suspend fun submit(report: DriverReport): Result<Unit> =
        Result.failure(DriverReportException.Unavailable)

    override suspend fun confirm(reportId: String): Result<Unit> =
        Result.failure(DriverReportException.Unavailable)

    override suspend fun dismiss(reportId: String): Result<Unit> =
        Result.failure(DriverReportException.Unavailable)
}

package cz.majkey.pocasicesko.data

import java.time.Instant

internal enum class StaticFeedState {
    DIAGNOSTIC,
    PRODUCTION,
}

internal data class StaticFeedGrid(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double,
    val step: Double,
    val tileStep: Double,
)

internal data class StaticFeedSource(
    val sourceId: String,
    val modelId: String,
)

internal data class StaticForecastManifest(
    val schemaVersion: Int,
    val state: StaticFeedState,
    val runId: String,
    val generatedAt: Instant,
    val expiresAt: Instant,
    val grid: StaticFeedGrid,
    val sources: List<StaticFeedSource>,
    val tileChecksums: Map<String, String>,
    val calibrationChecksum: String?,
    val datasetManifestHash: String?,
) {
    fun isUsableAt(now: Instant): Boolean =
        state == StaticFeedState.PRODUCTION &&
            !now.isBefore(generatedAt) && now.isBefore(expiresAt) &&
            tileChecksums.isNotEmpty() && calibrationChecksum != null && datasetManifestHash != null
}

internal data class StaticModelValue(
    val sourceId: String,
    val modelId: String,
    val runTime: Instant,
    val validTime: Instant,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double,
    val variable: String,
    val value: Double?,
    val unit: String,
)

internal data class StaticForecastTile(
    val runId: String,
    val tileX: Int,
    val tileY: Int,
    val values: List<StaticModelValue>,
)

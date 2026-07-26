package com.beettechnologies.posly.flags

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.db.FeatureFlagsTable
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest

sealed class CreateFlagResult {
    data class Success(val flag: FeatureFlag) : CreateFlagResult()
    data object DuplicateKey : CreateFlagResult()
}

sealed class UpdateFlagResult {
    data class Updated(val flag: FeatureFlag) : UpdateFlagResult()
    data object NotFound : UpdateFlagResult()
    data object InvalidPercentage : UpdateFlagResult()
}

private fun rowToFeatureFlag(row: ResultRow) = FeatureFlag(
    id = row[FeatureFlagsTable.id],
    key = row[FeatureFlagsTable.key],
    description = row[FeatureFlagsTable.description],
    enabled = row[FeatureFlagsTable.enabled],
    rolloutPercentage = row[FeatureFlagsTable.rolloutPercentage],
    enabledStoreIds = row[FeatureFlagsTable.enabledStoreIds].toSet(),
    createdAt = row[FeatureFlagsTable.createdAt],
    updatedAt = row[FeatureFlagsTable.updatedAt]
)

/**
 * Internal feature-flag service (no LaunchDarkly/similar credentials exist in this environment -
 * see SECURITY_COMPLIANCE.md-style disclosed-limitation precedent from prior tickets). Every
 * [evaluate] call reads the current row directly - no caching layer - so a toggle takes effect on
 * the very next evaluation, anywhere, with zero delay.
 */
class FeatureFlagService(private val meterRegistry: MeterRegistry? = null) {

    fun createFlag(key: String, description: String, enabled: Boolean = false, rolloutPercentage: Int = 0): CreateFlagResult = transaction {
        if (!FeatureFlagsTable.selectAll().where { FeatureFlagsTable.key eq key }.empty()) {
            return@transaction CreateFlagResult.DuplicateKey
        }
        val flag = FeatureFlag(key = key, description = description, enabled = enabled, rolloutPercentage = rolloutPercentage)
        FeatureFlagsTable.insert {
            it[id] = flag.id
            it[FeatureFlagsTable.key] = flag.key
            it[FeatureFlagsTable.description] = flag.description
            it[FeatureFlagsTable.enabled] = flag.enabled
            it[FeatureFlagsTable.rolloutPercentage] = flag.rolloutPercentage
            it[FeatureFlagsTable.enabledStoreIds] = flag.enabledStoreIds.toList()
            it[createdAt] = flag.createdAt
            it[updatedAt] = flag.updatedAt
        }
        AuditService.record(AuditEvent.FEATURE_FLAG_CREATED, detail = "key=${flag.key} enabled=${flag.enabled} rolloutPercentage=${flag.rolloutPercentage}")
        CreateFlagResult.Success(flag)
    }

    fun listFlags(): List<FeatureFlag> = transaction {
        FeatureFlagsTable.selectAll().map { rowToFeatureFlag(it) }
    }

    fun getFlag(key: String): FeatureFlag? = transaction {
        FeatureFlagsTable.selectAll().where { FeatureFlagsTable.key eq key }.singleOrNull()?.let { rowToFeatureFlag(it) }
    }

    fun updateFlag(
        key: String,
        enabled: Boolean? = null,
        rolloutPercentage: Int? = null,
        enabledStoreIds: Set<String>? = null,
        actorUserId: String? = null
    ): UpdateFlagResult = transaction {
        if (rolloutPercentage != null && rolloutPercentage !in 0..100) return@transaction UpdateFlagResult.InvalidPercentage
        val existing = FeatureFlagsTable.selectAll().where { FeatureFlagsTable.key eq key }.singleOrNull()?.let { rowToFeatureFlag(it) }
            ?: return@transaction UpdateFlagResult.NotFound

        val changes = mutableListOf<String>()
        if (enabled != null && enabled != existing.enabled) changes += "enabled: ${existing.enabled}->$enabled"
        if (rolloutPercentage != null && rolloutPercentage != existing.rolloutPercentage) changes += "rolloutPercentage: ${existing.rolloutPercentage}->$rolloutPercentage"
        if (enabledStoreIds != null && enabledStoreIds != existing.enabledStoreIds) changes += "enabledStoreIds: ${existing.enabledStoreIds}->$enabledStoreIds"

        val updated = existing.copy(
            enabled = enabled ?: existing.enabled,
            rolloutPercentage = rolloutPercentage ?: existing.rolloutPercentage,
            enabledStoreIds = enabledStoreIds ?: existing.enabledStoreIds,
            updatedAt = System.currentTimeMillis()
        )
        FeatureFlagsTable.update({ FeatureFlagsTable.key eq key }) {
            it[FeatureFlagsTable.enabled] = updated.enabled
            it[FeatureFlagsTable.rolloutPercentage] = updated.rolloutPercentage
            it[FeatureFlagsTable.enabledStoreIds] = updated.enabledStoreIds.toList()
            it[updatedAt] = updated.updatedAt
        }
        if (changes.isNotEmpty()) {
            AuditService.record(AuditEvent.FEATURE_FLAG_UPDATED, userId = actorUserId, detail = "key=$key ${changes.joinToString(", ")}")
        }
        UpdateFlagResult.Updated(updated)
    }

    /**
     * Evaluates [key] for [storeId]: the kill switch (`enabled=false`) always wins, then an
     * explicit per-store override, then a deterministic percentage bucket - the same (flag, store)
     * pair always lands in the same bucket, so a store's evaluation never flickers between
     * requests at a fixed rollout percentage.
     */
    fun evaluate(key: String, storeId: String): FlagEvaluation {
        val flag = getFlag(key) ?: return FlagEvaluation(enabled = false, reason = EvaluationReason.FLAG_NOT_FOUND).also { record(key, false) }
        val evaluation = when {
            !flag.enabled -> FlagEvaluation(enabled = false, reason = EvaluationReason.KILL_SWITCH_OFF)
            storeId in flag.enabledStoreIds -> FlagEvaluation(enabled = true, reason = EvaluationReason.STORE_OVERRIDE)
            else -> FlagEvaluation(enabled = bucket(key, storeId) < flag.rolloutPercentage, reason = EvaluationReason.PERCENTAGE_ROLLOUT)
        }
        record(key, evaluation.enabled)
        return evaluation
    }

    private fun record(key: String, enabled: Boolean) {
        meterRegistry?.counter("feature_flag_evaluations", "flag", key, "result", if (enabled) "ON" else "OFF")?.increment()
    }

    companion object {
        /** A stable 0-99 bucket for a (flagKey, storeId) pair, derived from a SHA-256 digest so the same pair always lands in the same bucket. */
        fun bucket(flagKey: String, storeId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest("$flagKey:$storeId".toByteArray())
            val value = ((digest[0].toInt() and 0xFF) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
            return (value and 0x7FFFFFFF) % 100
        }
    }
}

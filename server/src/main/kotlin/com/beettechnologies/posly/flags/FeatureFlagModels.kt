package com.beettechnologies.posly.flags

import java.util.UUID

data class FeatureFlag(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    val description: String,
    val enabled: Boolean = false,
    val rolloutPercentage: Int = 0,
    val enabledStoreIds: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class EvaluationReason { FLAG_NOT_FOUND, KILL_SWITCH_OFF, STORE_OVERRIDE, PERCENTAGE_ROLLOUT }

data class FlagEvaluation(val enabled: Boolean, val reason: EvaluationReason)

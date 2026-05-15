package com.zcam.core.domain.config

enum class EventDetectionSensitivity(
    val wireName: String,
    val label: String
) {
    LOW("low", "Low"),
    BALANCED("balanced", "Balanced"),
    HIGH("high", "High");

    companion object {
        fun fromWireName(value: String?): EventDetectionSensitivity {
            return entries.firstOrNull { sensitivity ->
                sensitivity.wireName.equals(value?.trim(), ignoreCase = true) ||
                    sensitivity.name.equals(value?.trim(), ignoreCase = true)
            } ?: BALANCED
        }
    }
}

package com.zcam.core.domain.config

enum class RearCameraLens(
    val wireName: String,
    val label: String
) {
    MAIN(
        wireName = "main",
        label = "Main camera"
    ),
    ULTRA_WIDE(
        wireName = "ultra_wide",
        label = "Ultra-wide camera"
    );

    companion object {
        fun fromWireName(value: String?): RearCameraLens {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { lens ->
                lens.wireName.equals(normalized, ignoreCase = true) ||
                    lens.name.equals(normalized, ignoreCase = true)
            } ?: MAIN
        }
    }
}

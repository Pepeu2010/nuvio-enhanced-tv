package com.nuvio.tv.domain.model

/** Local per-profile navigation preference; not an addition to the official sync contract. */
enum class AnimationIntensity(val fraction: Float) {
    SUBTLE(0.65f), STANDARD(1f), CINEMATIC(1.25f);

    fun durationMillis(duration: Int): Int = (duration.coerceAtLeast(0) * fraction).toInt()
    fun scale(value: Float): Float = 1f + (value - 1f) * fraction

    companion object {
        fun fromName(value: String?): AnimationIntensity = entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

data class UiMotionPolicy(
    val mode: NavigationMotion = NavigationMotion.FULL,
    val intensity: AnimationIntensity = AnimationIntensity.STANDARD,
) {
    val allowsSpatialEffects: Boolean get() = mode.allowsSpatialEffects
    fun durationMillis(duration: Int): Int = mode.durationMillis(intensity.durationMillis(duration))
    fun scale(value: Float): Float = if (allowsSpatialEffects) intensity.scale(value) else 1f
}

enum class NavigationMotion {
    FULL, REDUCED, OFF;

    val allowsSpatialEffects: Boolean get() = this == FULL

    fun durationMillis(fullDuration: Int): Int = when (this) {
        FULL -> fullDuration.coerceAtLeast(0)
        REDUCED -> fullDuration.coerceIn(0, 120)
        OFF -> 0
    }

    companion object {
        fun fromName(value: String?): NavigationMotion =
            entries.firstOrNull { it.name == value } ?: FULL
    }
}

enum class AppTheme(val displayName: String) {
    CUSTOM("Custom"),
    GOLD("Gold"),
    JADE("Jade"),
    ROSE_GOLD("Rose Gold"),
    ARCTIC_BLUE("Arctic Blue"),
    GRAPHITE("Graphite"),
    CRIMSON("Crimson"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    ROSE("Rose"),
    WHITE("White")
}

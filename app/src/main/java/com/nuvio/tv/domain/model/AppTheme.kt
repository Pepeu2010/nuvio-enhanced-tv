package com.nuvio.tv.domain.model

/** Local per-profile navigation preference; not an addition to the official sync contract. */
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

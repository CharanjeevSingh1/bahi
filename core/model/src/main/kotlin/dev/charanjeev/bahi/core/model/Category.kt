package dev.charanjeev.bahi.core.model

data class Category(
    val id: String,
    val name: String,
    val parentId: String? = null,
    /** ARGB. Kept as Int so :core:model stays free of Android types. */
    val colorArgb: Int,
    val iconKey: String,
    val isSystemDefined: Boolean = false,
)

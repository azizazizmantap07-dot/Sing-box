package com.liquid.box.compose.util

import androidx.compose.ui.graphics.vector.ImageVector
import com.liquid.box.compose.util.icons.MaterialIconsLibrary

data class ProfileIcon(val id: String, val icon: ImageVector, val label: String)

object ProfileIcons {
    val availableIcons: List<ProfileIcon>
        get() = MaterialIconsLibrary.getAllIcons()

    fun getIconById(id: String?): ImageVector? {
        if (id == null) return null
        return MaterialIconsLibrary.getIconById(id)
    }

    fun getCategoryForIcon(iconId: String): String? =
        MaterialIconsLibrary.getCategoryForIcon(iconId)

    fun searchIcons(query: String): List<ProfileIcon> =
        MaterialIconsLibrary.searchIcons(query)

    fun getCategories() = MaterialIconsLibrary.categories
}

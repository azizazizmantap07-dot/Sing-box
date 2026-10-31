package com.liquid.box.compose.util.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.liquid.box.compose.util.ProfileIcon

/** Material Icons grouped by Material Design category. */
object MaterialIconsLibrary {
    val categories: List<IconCategory> =
        listOf(
            IconCategory("Action", ActionIcons.icons),
            IconCategory("Alert", AlertIcons.icons),
            IconCategory("Audio & Video", AVIcons.icons),
            IconCategory("Communication", CommunicationIcons.icons),
            IconCategory("Content", ContentIcons.icons),
            IconCategory("Device", DeviceIcons.icons),
            IconCategory("Editor", EditorIcons.icons),
            IconCategory("File", FileIcons.icons),
            IconCategory("Hardware", HardwareIcons.icons),
            IconCategory("Image", ImageIcons.icons),
            IconCategory("Maps", MapsIcons.icons),
            IconCategory("Navigation", NavigationIcons.icons),
            IconCategory("Notification", NotificationIcons.icons),
            IconCategory("Places", PlacesIcons.icons),
            IconCategory("Social", SocialIcons.icons),
            IconCategory("Toggle", ToggleIcons.icons),
        )

    private val allIconsCache: List<ProfileIcon> by lazy { categories.flatMap { it.icons } }
    private val iconsById: Map<String, ProfileIcon> by lazy { allIconsCache.associateBy { it.id } }

    fun getAllIcons(): List<ProfileIcon> = allIconsCache

    fun getIconById(id: String): ImageVector? = iconsById[id]?.icon

    fun getCategoryForIcon(iconId: String): String? =
        categories.firstOrNull { category -> category.icons.any { it.id == iconId } }?.name

    fun searchIcons(query: String): List<ProfileIcon> {
        if (query.isBlank()) return allIconsCache
        val q = query.lowercase()
        return allIconsCache.filter {
            it.id.contains(q) || it.label.lowercase().contains(q)
        }
    }
}

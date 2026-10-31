package com.liquid.box.compose.util.icons

import com.liquid.box.compose.util.ProfileIcon

data class IconCategory(val name: String, val icons: List<ProfileIcon>) {
    val size: Int get() = icons.size
}

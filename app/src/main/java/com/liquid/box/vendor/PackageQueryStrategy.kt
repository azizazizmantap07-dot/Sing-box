package com.liquid.box.vendor

sealed class PackageQueryStrategy {
    data object Root : PackageQueryStrategy()
    data object Direct : PackageQueryStrategy()
}

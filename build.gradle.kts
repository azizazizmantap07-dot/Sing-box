plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.github.triplet.play") version "4.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    alias(libs.plugins.spotless) apply false
    // detekt 2.x: fixes ReportingExtension.file() deprecation on Gradle 9+
    id("dev.detekt") version "2.0.0-alpha.6"
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("${projectDir}/config/detekt/detekt.yml")
    baseline = file("${projectDir}/config/detekt/baseline.xml")
    source.setFrom("app/src/main/java", "app/src/main/kotlin")
}

dependencies {
    // ktlint wrapper renamed in detekt 2.x
    "detektPlugins"("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
}

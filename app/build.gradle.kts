import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin support is built into AGP 9+; do not apply org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.github.triplet.play")
    alias(libs.plugins.spotless)
}

fun getProps(propName: String): String {
    System.getenv(propName)?.takeIf { it.isNotEmpty() }?.let { return it }
    val propsInEnv = System.getenv("LOCAL_PROPERTIES")
    if (propsInEnv != null) {
        val props = Properties()
        props.load(ByteArrayInputStream(Base64.getDecoder().decode(propsInEnv)))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

fun getVersionProps(propName: String): String {
    val propsFile = rootProject.file("version.properties")
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        val value = props.getProperty(propName)
        if (value != null) {
            return value
        }
    }
    return ""
}

android {
    namespace = "com.liquid.box"
    compileSdk = 37
    compileSdkMinor = 1

    ndkVersion = "28.0.13004108"

    System.getenv("ANDROID_NDK_HOME")?.let { ndkPath = it }

    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "${projectDir}/schemas")
    }

    defaultConfig {
        applicationId = "com.liquid.box"
        minSdk = 24
        targetSdk = 37
        versionCode = getVersionProps("VERSION_CODE").toInt()
        versionName = getVersionProps("VERSION_NAME")
        base.archivesName.set("LiquidBox-${versionName}")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = getProps("KEYSTORE_PASS")
            keyAlias = getProps("ALIAS_NAME")
            keyPassword = getProps("ALIAS_PASS")
        }
    }

    buildTypes {
        debug {
            if (getProps("KEYSTORE_PASS").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            vcsInfo.include = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    flavorDimensions += "vendor"
    productFlavors {
        create("play")
        create("other")
        create("otherLegacy") {
            // 23 is the floor of Haze (real backdrop blur) and of the AndroidX / Compose / Material
            // releases used below. API 21-22 devices are no longer covered by this flavor.
            minSdk = 23
        }
    }

    // Flavor-specific compat sources live under src/<flavor>/java (AGP discovers them
    // automatically). other/play use tree-sitter (minSdk 24); otherLegacy (minSdk 23) keeps
    // EditorKit + libbox-legacy, but shares the Compose / Window compat shims with other/play.

    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by android-tree-sitter
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        fatal += "NewApi"
    }

}

// AGP 9+ Variant API: rename APK outputs (replaces applicationVariants)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val current = output.outputFileName.get()
            var fileName = current
            fileName = fileName.replace("-release", "")
            fileName = fileName.replace("-play", "-play")
            fileName = fileName.replace("-otherLegacy", "-legacy-android-6")
            fileName = fileName.replace("-other", "")
            output.outputFileName.set(fileName)
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // libbox
    "playImplementation"(files("libs/libbox.aar"))
    "otherImplementation"(files("libs/libbox.aar"))
    "otherLegacyImplementation"(files("libs/libbox-legacy.aar"))

    // Shared by every flavor (all of them have minSdk >= 23).
    // Navigation stays on 2.9.x: 2.10+ requires minSdk 24, which would exclude otherLegacy.
    val lifecycleVersion = "2.11.0"
    val roomVersion = "2.8.4"
    val workVersion = "2.11.2"
    val cameraVersion = "1.6.1"
    val browserVersion = "1.10.0"
    val webkitVersion = "1.16.0"
    val coreVersion = "1.19.0"
    val materialVersion = "1.14.0"

    // Common dependencies (no API level difference)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.8")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.8")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.6.0-android")

    // play / other
    "playImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    "playImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    "playImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    "playImplementation"("androidx.room:room-runtime:$roomVersion")
    "playImplementation"("androidx.work:work-runtime-ktx:$workVersion")
    "playImplementation"("androidx.camera:camera-view:$cameraVersion")
    "playImplementation"("androidx.camera:camera-lifecycle:$cameraVersion")
    "playImplementation"("androidx.camera:camera-camera2:$cameraVersion")
    "playImplementation"("androidx.browser:browser:$browserVersion")
    "playImplementation"("androidx.webkit:webkit:$webkitVersion")
    "playImplementation"("androidx.core:core-ktx:$coreVersion")
    "playImplementation"("com.google.android.material:material:$materialVersion")
    "playAnnotationProcessor"("androidx.room:room-compiler:$roomVersion")
    "kspPlay"("androidx.room:room-compiler:$roomVersion")

    "otherImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    "otherImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    "otherImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    "otherImplementation"("androidx.room:room-runtime:$roomVersion")
    "otherImplementation"("androidx.work:work-runtime-ktx:$workVersion")
    "otherImplementation"("androidx.camera:camera-view:$cameraVersion")
    "otherImplementation"("androidx.camera:camera-lifecycle:$cameraVersion")
    "otherImplementation"("androidx.camera:camera-camera2:$cameraVersion")
    "otherImplementation"("androidx.browser:browser:$browserVersion")
    "otherImplementation"("androidx.webkit:webkit:$webkitVersion")
    "otherImplementation"("androidx.core:core-ktx:$coreVersion")
    "otherImplementation"("com.google.android.material:material:$materialVersion")
    "kspOther"("androidx.room:room-compiler:$roomVersion")

    // otherLegacy (minSdk 23; same versions as play / other)
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-process:$lifecycleVersion")
    "otherLegacyImplementation"("androidx.room:room-runtime:$roomVersion")
    "otherLegacyImplementation"("androidx.work:work-runtime-ktx:$workVersion")
    "otherLegacyImplementation"("androidx.camera:camera-view:$cameraVersion")
    "otherLegacyImplementation"("androidx.camera:camera-lifecycle:$cameraVersion")
    "otherLegacyImplementation"("androidx.camera:camera-camera2:$cameraVersion")
    "otherLegacyImplementation"("androidx.browser:browser:$browserVersion")
    "otherLegacyImplementation"("androidx.webkit:webkit:$webkitVersion")
    "otherLegacyImplementation"("androidx.core:core-ktx:$coreVersion")
    "otherLegacyImplementation"("com.google.android.material:material:$materialVersion")
    "kspOtherLegacy"("androidx.room:room-compiler:$roomVersion")

    // Configuration editor: sora-editor (tree-sitter) for API 24+, EditorKit kept for legacy
    val soraVersion = "0.23.6"
    val treeSitterVersion = "4.3.2"
    "playImplementation"("io.github.Rosemoe.sora-editor:editor:$soraVersion")
    "playImplementation"("io.github.Rosemoe.sora-editor:language-treesitter:$soraVersion")
    "playImplementation"("com.itsaky.androidide.treesitter:android-tree-sitter:$treeSitterVersion")
    "playImplementation"("com.itsaky.androidide.treesitter:tree-sitter-json:$treeSitterVersion")
    "otherImplementation"("io.github.Rosemoe.sora-editor:editor:$soraVersion")
    "otherImplementation"("io.github.Rosemoe.sora-editor:language-treesitter:$soraVersion")
    "otherImplementation"("com.itsaky.androidide.treesitter:android-tree-sitter:$treeSitterVersion")
    "otherImplementation"("com.itsaky.androidide.treesitter:tree-sitter-json:$treeSitterVersion")
    "otherLegacyImplementation"("com.blacksquircle.ui:editorkit:2.2.0")
    "otherLegacyImplementation"("com.blacksquircle.ui:language-json:2.2.0")

    // Play Store specific
    "playImplementation"("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // libsu for ROOT package query (all flavors)
    val libsuVersion = "6.0.0"
    "playImplementation"("com.github.topjohnwu.libsu:core:$libsuVersion")
    "playImplementation"("com.github.topjohnwu.libsu:service:$libsuVersion")
    "otherImplementation"("com.github.topjohnwu.libsu:core:$libsuVersion")
    "otherImplementation"("com.github.topjohnwu.libsu:service:$libsuVersion")
    "otherLegacyImplementation"("com.github.topjohnwu.libsu:core:$libsuVersion")
    "otherLegacyImplementation"("com.github.topjohnwu.libsu:service:$libsuVersion")

    // Compose dependencies - play / other
    // Haze 2.0.x is built against Compose 1.12.1, which is what BOM 2026.09.00 maps to.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    val activityVersion = "1.13.0"
    val lifecycleComposeVersion = "2.11.0"

    "playImplementation"(composeBom)
    "playImplementation"("androidx.compose.material3:material3")
    "playImplementation"("androidx.compose.material3.adaptive:adaptive")
    "playImplementation"("androidx.compose.ui:ui")
    "playImplementation"("androidx.compose.ui:ui-tooling-preview")
    "playImplementation"("androidx.compose.material:material-icons-extended")
    "playImplementation"("androidx.activity:activity-compose:$activityVersion")
    "playImplementation"("androidx.navigation:navigation-compose:2.9.8")
    "playImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion")
    "playImplementation"("androidx.compose.runtime:runtime-livedata")

    // Real backdrop blur for glass sheets / navigation (Haze 2).
    // Included on every flavor (all have minSdk >= 23, Haze's floor). Blur is on by default
    // from Android 12; on API 23-30 Haze draws a translucent scrim unless blurEnabled is
    // set. LocalHazeState stays optional, so blur is skipped when no source is provided.
    val hazeVersion = "2.0.1"
    "playImplementation"("dev.chrisbanes.haze:haze:$hazeVersion")
    "playImplementation"("dev.chrisbanes.haze:haze-blur:$hazeVersion")
    "otherImplementation"("dev.chrisbanes.haze:haze:$hazeVersion")
    "otherImplementation"("dev.chrisbanes.haze:haze-blur:$hazeVersion")
    "otherLegacyImplementation"("dev.chrisbanes.haze:haze:$hazeVersion")
    "otherLegacyImplementation"("dev.chrisbanes.haze:haze-blur:$hazeVersion")

    "otherImplementation"(composeBom)
    "otherImplementation"("androidx.compose.material3:material3")
    "otherImplementation"("androidx.compose.material3.adaptive:adaptive")
    "otherImplementation"("androidx.compose.ui:ui")
    "otherImplementation"("androidx.compose.ui:ui-tooling-preview")
    "otherImplementation"("androidx.compose.material:material-icons-extended")
    "otherImplementation"("androidx.activity:activity-compose:$activityVersion")
    "otherImplementation"("androidx.navigation:navigation-compose:2.9.8")
    "otherImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion")
    "otherImplementation"("androidx.compose.runtime:runtime-livedata")

    // Compose dependencies - otherLegacy

    "otherLegacyImplementation"(composeBom)
    "otherLegacyImplementation"("androidx.compose.material3:material3")
    "otherLegacyImplementation"("androidx.compose.material3.adaptive:adaptive")
    "otherLegacyImplementation"("androidx.compose.ui:ui")
    "otherLegacyImplementation"("androidx.compose.ui:ui-tooling-preview")
    "otherLegacyImplementation"("androidx.compose.material:material-icons-extended")
    "otherLegacyImplementation"("androidx.activity:activity-compose:$activityVersion")
    "otherLegacyImplementation"("androidx.navigation:navigation-compose:2.9.8")
    "otherLegacyImplementation"("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleComposeVersion")
    "otherLegacyImplementation"("androidx.compose.runtime:runtime-livedata")

    // Debug/Test dependencies
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "androidTestPlayImplementation"(composeBom)
    "androidTestOtherImplementation"(composeBom)
    "androidTestOtherLegacyImplementation"(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Common Compose-related libraries
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation("com.github.jeziellago:compose-markdown:0.7.2")
    implementation("org.kodein.emoji:emoji-kt:2.5.0")

    // Terminal emulator
    val libghosttyVersion = "0.1.0-alpha01"
    implementation("io.github.sagernet:libghostty-android:$libghosttyVersion")
    implementation("io.github.sagernet:libghostty-android-extras:$libghosttyVersion")
    "playImplementation"("io.github.sagernet:libghostty-android-compose:$libghosttyVersion")
    "otherImplementation"("io.github.sagernet:libghostty-android-compose:$libghosttyVersion")
    "otherLegacyImplementation"("io.github.sagernet:libghostty-android-compose-legacy:$libghosttyVersion")
}

val playCredentialsJSON = rootProject.file("service-account-credentials.json")
if (playCredentialsJSON.exists()) {
    play {
        serviceAccountCredentials.set(playCredentialsJSON)
        defaultToAppBundles.set(true)
        val version = getVersionProps("VERSION_NAME")
        track.set(
            if (version.contains("alpha") || version.contains("beta")/* || version.contains("rc")*/) {
                "beta"
            } else {
                "production"
            }
        )
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_blank-line-before-declaration" to "disabled",
                "ktlint_standard_blank-line-between-when-conditions" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
            ))
    }
    java {
        target("src/**/*.java")
        googleJavaFormat()
    }
}

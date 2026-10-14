import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ───────────── секреты сборки: переменная окружения (CI) → local.properties (локально) ─────────────
// local.properties по умолчанию в .gitignore — в репозиторий секрет не попадёт.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun buildSecret(name: String): String = (System.getenv(name) ?: localProps.getProperty(name) ?: "").trim()
/** Строка → корректный Java-литерал для buildConfigField (экранируем \ и "). */
fun String.asJavaLiteral(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val appHmacSecret = buildSecret("APP_HMAC_SECRET")
val apiBaseUrl = buildSecret("API_BASE_URL")
val yandexClientId = buildSecret("YANDEX_CLIENT_ID")

// Релиз без секрета/адреса API бесполезен (все запросы получат 403) — падаем сразу, а не в рантайме.
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    require(appHmacSecret.isNotEmpty()) { "APP_HMAC_SECRET не задан (local.properties или переменная окружения)" }
    require(yandexClientId.isNotEmpty()) { "YANDEX_CLIENT_ID не задан (local.properties или переменная окружения)" }
    require(apiBaseUrl.startsWith("https://")) { "API_BASE_URL должен начинаться с https:// (local.properties или переменная окружения)" }
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.v2rayez.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.v2rayez.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        resourceConfigurations += listOf("en", "ru")
        ndk { abiFilters += listOf("arm64-v8a") }

        // ─── ШАГ 2: секрет подписи запросов и адрес API попадают в BuildConfig ───
        buildConfigField("String", "APP_HMAC_SECRET", appHmacSecret.asJavaLiteral())
        buildConfigField("String", "API_BASE_URL", apiBaseUrl.asJavaLiteral())
        // ID приложения Яндекс OAuth (публичный; client_secret хранится только на backend)
        buildConfigField("String", "YANDEX_CLIENT_ID", yandexClientId.asJavaLiteral())
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else null
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // ШАГ 2: AGP 8+ по умолчанию НЕ генерирует BuildConfig
    }
    testOptions { unitTests { isReturnDefaultValues = true } }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true }
    }

    lint { abortOnError = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)   // ШАГ 2: collectAsStateWithLifecycle, LifecycleEventEffect
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // ШАГ 2
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.androidx.browser)                      // CustomTabsIntent

    // Vendored Xray core (gomobile AAR с in-core TUN handler)
    implementation(files("libs/libv2ray.aar"))

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}

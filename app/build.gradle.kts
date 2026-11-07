import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.elevenflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.elevenflow"
        minSdk = 28
        targetSdk = 34
        versionCode = 20
        versionName = "0.20"
        // Optional personal fallback key from secrets.properties (git-ignored). Public builds: pass -PnoKey,
        // and users paste their own key in the app instead.
        val key = if (project.hasProperty("noKey")) "" else secrets.getProperty("ELEVENLABS_API_KEY", "")
        buildConfigField("String", "ELEVEN_KEY", "\"$key\"")
        // Optional personal fallback webhook (HOOK_URL in secrets.properties); public builds leave it empty.
        val hook = if (project.hasProperty("noKey")) "" else secrets.getProperty("HOOK_URL", "")
        buildConfigField("String", "HOOK_URL", "\"$hook\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket client
    testImplementation("junit:junit:4.13.2")
}

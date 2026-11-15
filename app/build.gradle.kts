plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// El commit con que se compila y su fecha: la app los enseña en Ajustes y al principio del registro.
fun git(vararg args: String): String = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "desconocido" }

val gitCommit = git("rev-parse", "--short", "HEAD")
val gitCommitDate = git("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d %H:%M")

android {
    namespace = "com.rabie.bmwobd"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rabie.bmwobd"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_COMMIT_DATE", "\"$gitCommitDate\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose") version "1.7.3"
}


dependencies {
    implementation(compose.desktop.currentOs)
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.microsoft.playwright:playwright:1.55.0")
}

compose.desktop {
    application {
        mainClass = "com.lilac.anime.desktop.MainKt"
    }
}

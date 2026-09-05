plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lilac.anime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lilac.anime"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "0.2.6"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation:1.6.8")

    implementation("androidx.navigation:navigation-compose:2.9.4")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")


    // Compose Foundation & Material3 최신 버전으로 통일
    implementation("androidx.compose.foundation:foundation:1.7.0") // 또는 최신 stable 버전
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-ripple:1.7.0")

    // 2. 뒤에 버전 번호(:1.6.8 등)를 명시해 줍니다. (Replay10, Forward10 등 아이콘 사용 필수)
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Media3 is retained only as a legacy-download compatibility layer. Playback is libmpv.
    implementation("androidx.media3:media3-common:1.3.1")
    // Existing offline/download pipeline still uses Media3. Playback itself is now libmpv.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jsoup:jsoup:1.23.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // Retrofit2
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    // (선택) 만약 JSON 파싱을 위해 Gson 변환기를 사용하신다면 아래 줄도 추가해 주세요.
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    
    implementation("dev.jdtech.mpv:libmpv:1.0.0")

}
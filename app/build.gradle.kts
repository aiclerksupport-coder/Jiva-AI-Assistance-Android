plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jiva.assistance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiva.assistance"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // These are public Firebase app identifiers, NEVER a server Gemini key.
    val gatewayUrl = (project.findProperty("jivaGatewayUrl") as? String).orEmpty()
    val firebaseProject = (project.findProperty("jivaFirebaseProjectId") as? String).orEmpty()
    val firebaseApp = (project.findProperty("jivaFirebaseAppId") as? String).orEmpty()
    val firebaseWebKey = (project.findProperty("jivaFirebaseWebApiKey") as? String).orEmpty()
    val forceGateway = (project.findProperty("jivaRequireGateway") as? String) == "true"
    fun stringLiteral(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    defaultConfig {
        buildConfigField("String", "JIVA_GATEWAY_URL", stringLiteral(gatewayUrl))
        buildConfigField("String", "JIVA_FIREBASE_PROJECT_ID", stringLiteral(firebaseProject))
        buildConfigField("String", "JIVA_FIREBASE_APP_ID", stringLiteral(firebaseApp))
        buildConfigField("String", "JIVA_FIREBASE_WEB_API_KEY", stringLiteral(firebaseWebKey))
        buildConfigField("boolean", "JIVA_REQUIRE_GATEWAY", forceGateway.toString())
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-appcheck")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("ai.picovoice:porcupine-android:4.0.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

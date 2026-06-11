plugins {
    id("com.android.application")
}

android {
    namespace = "br.com.monitorarmazenamentomemoria"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.monitorarmazenamentomemoria"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
}

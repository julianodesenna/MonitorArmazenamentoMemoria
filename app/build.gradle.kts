plugins {
    id("com.android.application")
}

android {
    namespace = "br.com.monitorarmazenamentomemoria"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.monitorarmazenamentomemoria"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "1.7.2"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
}

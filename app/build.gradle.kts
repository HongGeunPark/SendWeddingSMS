plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.honggeun.sendweddingsms"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.honggeun.sendweddingsms"
        minSdk = 24
        // 34로 두어 Android 15의 강제 전체화면(edge-to-edge) 레이아웃을 피한다.
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        // 저장소에 들어 있는 고정 키로 서명해서, 새 버전을 지우지 않고 덮어 설치할 수 있게 한다.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

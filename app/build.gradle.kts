plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.windowdocent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.windowdocent"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // CI에서 만든 서명키를 환경변수로 받음. 없으면(로컬) debug 키로 서명.
    val ksPath = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    // 웹 프로토타입(prototype/index.html)을 그대로 앱 화면으로 씀
    sourceSets["main"].assets.srcDirs("../prototype")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 화면 브리지(JSON)를 실제로 돌리기 위한 org.json 구현 (기기에서는 안드로이드에 들어 있는 것을 씀)
    testImplementation("org.json:json:20240303")
}

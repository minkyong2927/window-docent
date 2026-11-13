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
        versionCode = 8
        versionName = "0.7.1"
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
    // 임의 자료(sample-data/)도 같은 assets 맨 위에 (화면이 scenario-a.js 를 읽음)
    sourceSets["main"].assets.srcDirs("../prototype", "../sample-data")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Pleos Connect SDK: 차량 신호(방향지시등 · 조향각 · 브레이크 · 도어 · 좌석) · 내비 다음 안내 · 위치(속도 계산) · 음성 출력
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    // 해설을 소리로 (문장마다 · 일시정지가 없어 멈춤 · 이어 말하기는 Narrator 가 함)
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // 음성 명령 (기기 안 인식 ON_DEVICE 로만 · 차량 마이크 음성을 바깥 음성 인식으로 보내지 않음)
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    // SDK 가 1.9 판 kotlin-reflect 를 끌어와서 우리 Kotlin 판에 맞춤 (공식 샘플과 같은 방식)
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")

    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 화면 브리지(JSON)를 실제로 돌리기 위한 org.json 구현 (기기에서는 안드로이드에 들어 있는 것을 씀)
    testImplementation("org.json:json:20240303")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.thsvkd.curfew"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thsvkd.curfew"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 서명 자격 증명은 저장소가 아니라 ~/.gradle/gradle.properties 에 둔다. 키가 없는
    // 환경에서도 체크아웃과 디버그 빌드는 그대로 되도록, 없으면 서명 설정을 만들지 않는다.
    val keystorePath = providers.gradleProperty("CURFEW_KEYSTORE").orNull
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("CURFEW_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("CURFEW_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("CURFEW_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8을 켜지 않는다. 줄어드는 몫이 번들한 서체 앞에서 미미한 반면,
            // Room과 Compose의 리플렉션 경로를 새로 검증해야 한다.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

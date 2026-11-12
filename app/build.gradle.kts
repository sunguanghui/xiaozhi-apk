plugins {
    alias(libs.plugins.android.application)
}

// CI 可通过 VERSION_CODE / VERSION_NAME 注入递增版本号，避免所有 Release 都是 versionCode=1
val injectedVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val injectedVersionName = System.getenv("VERSION_NAME") ?: "1.0.0"

android {
    namespace = "com.lhht.xiaozhi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lhht.xiaozhi.ai"
        minSdk = 21
        targetSdk = 35
        versionCode = injectedVersionCode
        versionName = injectedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 为不同CPU架构编译native库
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
            }
        }
    }

    // Release 签名：从环境变量读取，适配 CI；本地未配置时退回 debug 签名
    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 release 签名配置时使用，否则退回 debug 签名（仅供本地测试）
            val releaseSigning = signingConfigs.findByName("release")
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.recyclerview)
    implementation(libs.viewpager2)
    implementation(libs.java.websocket)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

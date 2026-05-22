plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.example.adaptivellm"
    compileSdk = 36

    ndkVersion = "29.0.13113456"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.example.adaptivellm"
        minSdk = 28
        targetSdk = 36
        versionCode = 19
        versionName = "1.1.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_VULKAN=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig нужен в Kotlin'е для access к versionCode (используется в
        // persist test cache key — Stage 2). В AGP 8.x по умолчанию выключен.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    // AppCompatDelegate.setApplicationLocales — per-app locale override (Stage 7).
    // НЕ используем AppCompat-темы — только runtime locale switching API.
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Markdown rendering
    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.latex)
    implementation(libs.markwon.inline.parser)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.firebase.firestore)

    // Networking
    implementation(libs.okhttp)

    // ONNX Runtime + Extensions (Stage 3 — embedding модель USER2-small).
    // onnxruntime-android 1.22+: official Microsoft, supports INT8 quantized models,
    //   ~13 MB AAR. Версия 1.22+ имеет 16 KB page alignment fix (Play Store requirement).
    // onnxruntime-extensions-android: custom ops библиотека от Microsoft, включающая
    //   HuggingFace tokenizers как ONNX graph nodes. Это позволяет нам embedded
    //   tokenizer прямо в .onnx модель — input строка, output embedding. Не нужна
    //   отдельная JNI обёртка для tokenizer'а (DJL HF tokenizers не поддерживает
    //   Android arm64, а pure-Kotlin BPE — много кода и риск багов).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")


}

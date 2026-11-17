plugins {
    id("com.android.application")
}

android {
    namespace = "com.chessassist"
    compileSdk = 37
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.chessassist"
        minSdk = 27
        targetSdk = 34
        versionCode = 8
        versionName = "0.6.1"
        ndk {
            // Stockfish builds for arm64 (NNUE needs NEON); armeabi-v7a optional but slow.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-O3"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Stockfish is exec()'d from nativeLibraryDir (not dlopen'd), so the .so
            // must be physically extracted by the package installer (extractNativeLibs).
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    // Worker/bridge JVM regressions stub Android logging/UI only; engine backends
    // are explicit fakes and outgoing autoplay JSON uses a real JSON implementation.
    testOptions.unitTests.isReturnDefaultValues = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.merges += "META-INF/xposed/*"
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":module"))
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("io.github.libxposed:api:102.0.0")
}

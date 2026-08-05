plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kingzcheung.xime.plugin.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    
    api(kotlin("stdlib"))
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    api(platform(libs.androidx.compose.bom))
    api("androidx.compose.runtime:runtime")
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.lifecycle.runtime.compose)

    // Lua 脚本插件运行时（沙箱执行 main.lua，替代 DEX 加载）
    api("org.luaj:luaj-jse:3.0.1")
    // manifest.yaml 解析（Lua 模式插件元数据）
    api("org.yaml:snakeyaml:2.2")
    
    testImplementation("junit:junit:4.13.2")
}

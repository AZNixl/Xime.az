import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kingzcheung.xime.plugin.funasr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kingzcheung.xime.plugin.funasr_asr"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
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
}

android.applicationVariants.all {
    val pluginName = "funasr-asr"
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName = "$pluginName-$versionName.xipk"
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    compileOnly(project(":plugin-core"))
    // stdlib / okhttp 跟随宿主：插件运行在宿主进程，父加载器（宿主 classloader）优先，
    // 运行时解析到宿主那份（stdlib 2.4.10 / okhttp 5.4.0），插件不自带（减小 APK）。
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    compileOnly("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation(project(":plugin-core"))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    testImplementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
}

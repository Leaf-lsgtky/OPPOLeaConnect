import leaconnect.PackageModuleTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.leaf.leaconnect"
    // Google 只发布 android-37.0 这种小版本目录，没有裸的 android-37；
    // CI 上 sdkmanager 能装到的就是 37.0，这里必须一起声明 minor 才找得到平台。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.github.leaf.leaconnect"
        minSdk = 26
        targetSdk = 34
        versionCode = 79
        versionName = "7.0"
    }

    buildTypes {
        release {
            // R8 是开着的，但入口类和 provider 必须按名字 keep：LSPosed 用
            // Class.forName(java_init.list 里的名字) 加载 Core，改名就等于模块不加载；
            // Core 反射摸的全是框架成员，框架类不在本 APK 内，不受裁剪影响。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Xposed API 只能是编译期依赖：桩类一旦进 classes.dex，LSPosed 会直接
    // 拒绝加载模块（日志："The Xposed API classes are compiled into the module's APK"）。
    compileOnly(project(":xposed-stubs"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 状态卡的水印图标用 Material 的 CheckCircleOutline / ErrorOutline，
    // 和 KernelSU HomeMiuix.kt 一致；Miuix 自带的 Ok 只是一枚对勾，不是那个圆环。
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val packageModule = tasks.register<PackageModuleTask>("packageModule") {
    unsignedApk.set(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
    metaRoot.set(file("src/main/xposed-meta"))
    keystore.set(Signing.file(project))
    keyAlias.set(Signing.prop("keyAlias", "KEY_ALIAS"))
    storePassword.set(Signing.prop("storePassword", "KEYSTORE_PASSWORD"))
    keyPassword.set(Signing.prop("keyPassword", "KEY_PASSWORD"))
    buildTools.set(AndroidSdk.buildTools(project))
    outputApk.set(layout.buildDirectory.file(
        "outputs/apk/module/OPPOLeaConnect-v${android.defaultConfig.versionName}.apk"))
    dependsOn("assembleRelease")
}

tasks.register("moduleApk") {
    group = "build"
    description = "产出带 LSPosed 元数据并已签名的模块 APK"
    dependsOn(packageModule)
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.xianyu.vediodownload"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xianyu.vediodownload"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    lint {
        // 国内访问 dl.google.com 的 lint 依赖偶尔 TLS 握手失败；
        // Lint 与打包无关，关掉可避免 release 构建卡在 lintVitalAnalyzeRelease。
        abortOnError = false
        checkReleaseBuilds = false
        checkDependencies = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// 强行禁用 lintVitalRelease 任务：国内访问 dl.google.com 下载 lint 工具链
// (intellij-core / kotlin-compiler / uast 等) 时常 TLS 握手失败，
// 而 lint 与打包无关，跳过即可。直接禁用任务比 lint{} 配置更可靠。
tasks.matching { it.name == "lintVitalAnalyzeRelease" || it.name == "lintVitalReportRelease" }
    .configureEach { enabled = false }
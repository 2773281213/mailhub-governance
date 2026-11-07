// 本机模式的邮件引擎：纯 Kotlin/JVM 模块（包名 com.mailhub.core），不依赖 Android SDK。
// 单元测试直接在 JVM 上跑（不需要 Robolectric），所以打包 SQLite 的 FTS5 trigram 分词
// 和 Angus Mail 的中文 MIME 解析都能在 :core:test 里真实跑到。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    // 本机和 CI 都只有 JDK 21，也没有配置工具链自动下载，所以不用 jvmToolchain(...)：
    // 用当前 JDK 编译，只把字节码目标固定成 17，与 :app 的 compileOptions 保持一致。
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // androidx.sqlite 是 KMP 制品：在 :core 里解析到 -jvm 变体（自带 windows_x64 等本地库），
    // 在 :app 里解析到 -android 变体（AAR 里带四种 ABI 的 libsqliteJni.so）。
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    // MIME 解析用 Jakarta Mail API 与 Eclipse Angus 实现（版本与 angus-mail 的 pom 对齐）。
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

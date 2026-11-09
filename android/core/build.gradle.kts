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
    // src/test 的测试同时在设备上回放（见 :app 的 androidTest 源码集）；
    // 依赖 GreenMail 这类桌面专用库的测试放 src/jvmTest，设备构建不会编译它们。
    sourceSets.getByName("test").kotlin.srcDir("src/jvmTest/kotlin")
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
    // 读取 Python 导出的数据表（html.unescape 的实体表等）；金标测试也用它解析 JSON。
    // 只用 JsonElement API，不需要序列化编译器插件。
    implementation(libs.kotlinx.serialization.json)
    // 同步协调器（SyncCoordinator）用协程做并发控制：Semaphore 限流、每个账户一把 Mutex。
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // 本地 IMAP 服务器（IMAPS + 自签名证书）。它声明的 org.eclipse.angus:jakarta.mail 是
    // 含 jakarta.mail-api 类的胖 jar，会和本模块的 jakarta.mail-api 2.1.5 打架，所以排除；
    // GreenMail 需要的 JavaMail 实现由本模块已有的 angus-mail 2.0.5 提供（版本相同）。
    testImplementation(libs.greenmail) {
        exclude(group = "org.eclipse.angus", module = "jakarta.mail")
    }
}

tasks.test {
    useJUnit()
    // Angus 只在类加载时读一次这两个属性。GreenMail 的测试和 MimeSmokeTest 在同一个 JVM 里跑，
    // 谁先加载 MimeBodyPart 谁说了算，所以在 JVM 启动时就定死。
    systemProperty("mail.mime.decodefilename", "true")
    systemProperty("mail.mime.decodeparameters", "true")
}

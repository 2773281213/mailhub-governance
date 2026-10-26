plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 内置 Kotlin，不需要再 apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.mailhub.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mailhub.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.android)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * 风格守卫：扫描 src/main/java 下的 Kotlin 源码，命中任何一条瑞士风格禁令就让构建失败。
 * 有意不剥离注释——注释里的命中同样算，规则简单可靠。
 */
private data class SwissGuardRule(
    val name: String,
    val regex: Regex,
    val exceptions: Set<String>,
)

val swissGuard by tasks.registering {
    group = "verification"
    description = "扫描 Kotlin 源码，拦截违反瑞士风格设计规范的内容。"

    val sources = fileTree("src/main/java") { include("**/*.kt") }
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val rules = listOf(
            SwissGuardRule("圆角", Regex("RoundedCornerShape"), emptySet()),
            SwissGuardRule("圆形", Regex("CircleShape"), setOf("GeoShapes.kt")),
            SwissGuardRule("阴影", Regex("\\.shadow\\(|elevation"), emptySet()),
            SwissGuardRule("渐变", Regex("Brush\\.\\w*Gradient"), emptySet()),
            SwissGuardRule("居中或两端对齐的文字", Regex("TextAlign\\.(Center|Justify)"), emptySet()),
            SwissGuardRule(
                "居中布局",
                Regex("Alignment\\.(Center|CenterHorizontally|TopCenter|BottomCenter)\\b|Arrangement\\.Center\\b"),
                setOf("GeoShapes.kt"),
            ),
            SwissGuardRule("颜色字面量", Regex("Color\\(0x"), setOf("SwissPalette.kt")),
            SwissGuardRule(
                "透明度",
                Regex("\\.copy\\(alpha|\\.alpha\\(|graphicsLayer\\s*\\{[^}]*alpha"),
                emptySet(),
            ),
            SwissGuardRule("多余字重", Regex("FontWeight\\.(?!Normal\\b|Bold\\b)\\w+"), emptySet()),
            SwissGuardRule("Material 库", Regex("androidx\\.compose\\.material"), emptySet()),
        )

        val violations = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            val relative = file.relativeTo(project.projectDir).invariantSeparatorsPath
            file.readLines().forEachIndexed { index, line ->
                rules.forEach { rule ->
                    if (rule.regex.containsMatchIn(line) && file.name !in rule.exceptions) {
                        violations += "$relative:${index + 1}: [${rule.name}] ${line.trim()}"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "swissGuard 发现 ${violations.size} 处违反瑞士风格规范的内容：\n" +
                    violations.joinToString("\n"),
            )
        }
        println("swissGuard: 扫描 ${sources.files.size} 个 Kotlin 文件，未发现问题。")
    }
}

tasks.named("preBuild") {
    dependsOn(swissGuard)
}

// Robolectric 4.17 在 JDK 21 上跑 SDK 36 需要打开这两个 JDK 内部包：
// SDK 36 的 ApplicationSharedMemory 通过 jdk.internal.access.SharedSecrets 写 FileDescriptor。
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

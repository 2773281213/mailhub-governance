import java.io.File

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 内置 Kotlin，不需要再 apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.mailhub.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mailhub.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.3.0"
        // 设备上的 instrumented test 由 AndroidJUnitRunner 执行；:core 的测试源码直接挂在
        // androidTest 源码集上（见下面的 sourceSets），所以它同时也是 :core 的「手机回归」入口。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 发布签名只从环境变量读，仓库里不出现任何密码、别名或密钥库路径。
        // 四项齐全才注册 release 签名配置；缺任意一项就不注册，release 包照常产出，只是不签名。
        val keystoreFile = providers.environmentVariable("MAILHUB_KEYSTORE_FILE").orNull
        val keystorePassword = providers.environmentVariable("MAILHUB_KEYSTORE_PASSWORD").orNull
        val keyAlias = providers.environmentVariable("MAILHUB_KEY_ALIAS").orNull
        val keyPassword = providers.environmentVariable("MAILHUB_KEY_PASSWORD").orNull
        if (!keystoreFile.isNullOrBlank() &&
            !keystorePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            // 发布包开启 R8 代码压缩与资源压缩；AGP 8.0 起 R8 默认就是 full mode。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 只有环境变量齐全、signingConfigs 里注册了 release 时才签名，否则保持未签名。
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 本机模式打包了 SQLite（四种 ABI 各一份 libsqliteJni.so，共约 4.9 MB），
    // 不拆分的话 release 包会从 2.2 MB 涨到 7.1 MB（LOCAL_MODE.md 3.2）。
    // 按 ABI 拆成独立 APK，另出一份包含全部 ABI 的 universal 包方便侧载。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 设备测试直接复用 :core 的测试源码和测试资源，不复制任何一行测试代码：
    // 以后往 android/core/src/test 里新增的测试会自动在设备上跑。
    // 要用桌面专用依赖（GreenMail）的测试放 android/core/src/jvmTest，这里不编译；
    // 个别只是读仓库文件的测试加 @JvmOnly，由 android/scripts/core-on-device.ps1 用 runner 参数 notAnnotation 排除。
    sourceSets {
        getByName("androidTest") {
            kotlin.srcDir("../core/src/test/kotlin")
            // 金标语料（golden/*.json）和 MIME 夹具（mime/*.eml）用类加载器读取，
            // 所以挂到 androidTest 的 java 资源目录，最终打进测试 APK。
            resources.srcDirs("../core/src/test/resources")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // lint 报 error 一律让构建失败；warning 只记录不阻断（否则会挡住大量历史告警）。
    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    // angus-mail / jakarta.mail-api / angus-activation / jakarta.activation-api 四个 jar
    // 各自带一份 META-INF/LICENSE.md 和 META-INF/NOTICE.md，合并 Java 资源时按重名冲突处理。
    // 许可证并不相同：前两个是 EPL-2.0 或 GPL-2.0 加类路径例外，后两个是 EDL-1.0（BSD-3-Clause），
    // 后者要求二进制分发保留版权与免责声明，所以用 merges 把几份拼在一起，不能只留一份（R1 审核）。
    packaging {
        resources {
            merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
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
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(project(":core"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.android)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    // 设备测试：:core 的测试源码跑在 ART 上，所以测试编译期需要 :core 本身和它用到的库。
    // 这些依赖在 App 的运行时类路径上已经有了（:core 的 implementation 依赖），
    // 这里显式写出来是为了让 androidTest 的编译类路径也拿到它们。
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(project(":core"))
    androidTestImplementation(libs.androidx.sqlite)
    androidTestImplementation(libs.androidx.sqlite.bundled)
    androidTestImplementation(libs.jakarta.mail.api)
    androidTestImplementation(libs.angus.mail)
    androidTestImplementation(libs.angus.activation)
    androidTestImplementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * 把 :core 的产物（jar）加进 androidTest 编译的 friend paths。
 *
 * :core 的测试源码里用到了 :core 的 internal 声明（`Db.Row.long(Int)`、`splitSqlScript` 等）。
 * Kotlin 的 internal 只对同一个「模块」（即同一次编译）可见：:core 自己的 test 源集是靠
 * KGP 自动设置的 friend paths 才看得到 main 的 internal，而 :app 的 androidTest 编译不是
 * :core 的 friend 模块。这里按同一机制显式补上，是「androidTest 直接复用 :core 测试源码」
 * 的必要前提；否则那几个测试只能在 :core:test 里跑，等于要在设备上漏掉它们。
 */
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.startsWith("compile") && name.endsWith("AndroidTestKotlin")) {
        // friend path 必须指到「编译类路径上那一份」:core：androidTest 的类路径上是
        // :core 的 jar（build/libs/core.jar），不是 build/classes/kotlin/main 目录。
        // 两者指向同一份代码但被编译器当成不同的模块，指错目录 internal 仍然不可见。
        val coreJar = project(":core").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        dependsOn(":core:jar")
        compilerOptions.freeCompilerArgs.add(
            "-Xfriend-paths=" + coreJar.get().asFile.invariantSeparatorsPath,
        )
    }
}

/**
 * 风格守卫：扫描 Kotlin 源码和资源 XML，命中任何一条瑞士风格禁令就让构建失败。
 *
 * 规则和扫描逻辑抽成函数，`swissGuard`（扫真实源码）和 `swissGuardSelfTest`
 * （扫样例目录，断言每条规则都能命中）共用同一套实现，避免规则悄悄失效。
 * 匹配前会先剥掉 Kotlin 的行注释和块注释（XML 剥掉 XML 注释），
 * 所以注释里的字样不会误判。豁免按「项目相对完整路径」匹配。
 */
private data class SwissGuardRule(
    val name: String,
    val pattern: Regex,
    /** 豁免的文件，按项目相对路径（例如 src/main/java/com/mailhub/client/ui/swiss/SwissPalette.kt）。 */
    val exceptions: Set<String> = emptySet(),
    /** 只对匹配该路径的规则生效，例如只检查 ui/screens 下的硬编码间距。 */
    val pathPattern: Regex? = null,
)

private val swissPalette = "src/main/java/com/mailhub/client/ui/swiss/SwissPalette.kt"

private val swissGuardRules: List<SwissGuardRule> = listOf(
    SwissGuardRule("圆角", Regex("RoundedCornerShape")),
    SwissGuardRule("圆形", Regex("CircleShape")),
    SwissGuardRule("阴影", Regex("\\.shadow\\(|elevation")),
    SwissGuardRule("渐变画刷", Regex("Brush\\.\\w*Gradient")),
    SwissGuardRule("资源 XML 阴影与高程", Regex("android:elevation|shadowColor")),
    SwissGuardRule("资源 XML 渐变", Regex("<gradient")),
    SwissGuardRule("居中或两端对齐的文字", Regex("TextAlign\\.(Center|Justify)")),
    SwissGuardRule("右对齐或末尾对齐的文字", Regex("TextAlign\\.(End|Right)")),
    SwissGuardRule(
        "居中布局",
        Regex("Alignment\\.(Center|CenterHorizontally|TopCenter|BottomCenter)\\b|Arrangement\\.Center\\b"),
    ),
    SwissGuardRule(
        "边缘对齐布局",
        Regex("Alignment\\.(CenterStart|CenterEnd|TopEnd|BottomEnd|CenterVertically)\\b"),
    ),
    SwissGuardRule("均分排列", Regex("Arrangement\\.Space(Evenly|Around)")),
    SwissGuardRule("颜色字面量", Regex("Color\\(0x"), exceptions = setOf(swissPalette)),
    SwissGuardRule(
        "颜色常量",
        Regex("Color\\.(White|Black|Gray|Red|Transparent|LightGray|DarkGray|Blue|Green|Yellow|Cyan|Magenta)\\b"),
    ),
    SwissGuardRule("颜色分量构造", Regex("Color\\(\\s*red\\s*=")),
    SwissGuardRule("大写十六进制颜色", Regex("Color\\(0X")),
    SwissGuardRule("数值字重", Regex("FontWeight\\(")),
    SwissGuardRule("斜体", Regex("FontStyle\\.Italic")),
    SwissGuardRule("多余字重", Regex("FontWeight\\.(?!Normal\\b|Bold\\b)\\w+")),
    SwissGuardRule("切角与裁剪", Regex("CutCornerShape|\\.clip\\(")),
    SwissGuardRule("模糊", Regex("\\.blur\\(")),
    SwissGuardRule("自定义画刷", Regex("ShaderBrush")),
    SwissGuardRule("圆形绘制", Regex("drawCircle")),
    SwissGuardRule("位图资源", Regex("painterResource")),
    SwissGuardRule(
        "透明度",
        Regex("\\.copy\\(alpha|\\.alpha\\(|graphicsLayer\\s*\\{[^}]*alpha|alpha\\s*="),
    ),
    SwissGuardRule("Material 库", Regex("androidx\\.compose\\.material")),
    // 页面层只能用 SwissSpace token 和 cols，不准出现 12.dp 这种数字字面量。
    SwissGuardRule(
        "页面层硬编码间距",
        Regex("(?<![\\w.])\\d+(\\.\\d+)?\\.dp"),
        pathPattern = Regex("(^|/)ui/screens/"),
    ),
)

/** 剥掉注释，保留换行，保证报错行号不变。 */
private fun stripComments(source: String, isKotlin: Boolean): String {
    val blockStart = if (isKotlin) "/*" else "<!--"
    val blockEnd = if (isKotlin) "*/" else "-->"
    val lineMarker = if (isKotlin) "//" else null
    val stripped = StringBuilder(source.length)
    var index = 0
    while (index < source.length) {
        when {
            source.startsWith(blockStart, index) -> {
                val end = source.indexOf(blockEnd, index + blockStart.length)
                val stop = if (end < 0) source.length else end + blockEnd.length
                for (position in index until stop) {
                    if (source[position] == '\n') stripped.append('\n')
                }
                index = stop
            }

            lineMarker != null && source.startsWith(lineMarker, index) -> {
                val end = source.indexOf('\n', index)
                index = if (end < 0) source.length else end
            }

            else -> {
                stripped.append(source[index])
                index++
            }
        }
    }
    return stripped.toString()
}

/**
 * 对一组文件跑一遍规则，返回「违规描述列表」和「命中的规则名集合」。
 * 自检任务用后者判断每条规则是否仍然有效。
 */
private fun collectSwissGuardViolations(
    files: List<File>,
    projectDir: File,
    rules: List<SwissGuardRule>,
): Pair<List<String>, Set<String>> {
    val violations = mutableListOf<String>()
    val hits = mutableSetOf<String>()
    files.sortedBy { it.path }.forEach { file ->
        val relative = file.relativeTo(projectDir).invariantSeparatorsPath
        val text = stripComments(file.readText(), isKotlin = file.extension == "kt")
        text.lines().forEachIndexed { lineIndex, line ->
            for (rule in rules) {
                if (relative in rule.exceptions) continue
                if (rule.pathPattern != null && !rule.pathPattern.containsMatchIn(relative)) continue
                if (!rule.pattern.containsMatchIn(line)) continue
                hits += rule.name
                violations += "$relative:${lineIndex + 1}: [${rule.name}] ${line.trim()}"
            }
        }
    }
    return violations to hits
}

val swissGuard by tasks.registering {
    group = "verification"
    description = "扫描 Kotlin 源码与资源 XML，拦截违反瑞士风格设计规范的内容。"

    val sources = files(
        fileTree("src/main") { include("**/*.kt") },
        fileTree("src/debug") { include("**/*.kt") },
        fileTree("src/main/res") { include("**/*.xml") },
    )
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val (violations, _) = collectSwissGuardViolations(
            files = sources.files.toList(),
            projectDir = project.projectDir,
            rules = swissGuardRules,
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "swissGuard 发现 ${violations.size} 处违反瑞士风格规范的内容：\n" +
                    violations.joinToString("\n"),
            )
        }
        println("swissGuard: 扫描 ${sources.files.size} 个文件，未发现问题。")
    }
}

val swissGuardSelfTest by tasks.registering {
    group = "verification"
    description = "用样例目录验证 swissGuard 的每条规则都能命中，防止规则悄悄失效。"

    val fixtures = fileTree("swissguard-fixtures") {
        include("**/*.kt", "**/*.xml")
    }
    inputs.files(fixtures).withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val fixtureFiles = fixtures.files.toList()
        val (violations, hits) = collectSwissGuardViolations(
            files = fixtureFiles,
            projectDir = project.projectDir,
            rules = swissGuardRules,
        )
        val missing = swissGuardRules.map { it.name }.filterNot { it in hits }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "swissGuardSelfTest 失败：以下规则在样例里一次都没命中，规则可能已经失效：\n" +
                    missing.joinToString("\n") { " - $it" },
            )
        }
        println(
            "swissGuardSelfTest: ${swissGuardRules.size} 条规则全部命中，" +
                "样例 ${fixtureFiles.size} 个文件里共 ${violations.size} 处违规。",
        )
    }
}

tasks.named("preBuild") {
    dependsOn(swissGuard)
}

tasks.named("check") {
    dependsOn(swissGuardSelfTest)
}

// Robolectric 4.17 在 JDK 21 上跑 SDK 36 需要打开这两个 JDK 内部包：
// SDK 36 的 ApplicationSharedMemory 通过 jdk.internal.access.SharedSecrets 写 FileDescriptor。
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

# androidTest：设备上跑 `:core` 的测试

这个目录本身没有测试代码，只有这份说明。`:app` 的 androidTest 源码集在
`android/app/build.gradle.kts` 的 `sourceSets` 里直接指向：

- `android/core/src/test/kotlin`（`:core` 的测试源码）
- `android/core/src/test/resources`（金标语料 `golden/*.json` 和 MIME 夹具 `mime/*.eml`）

所以**不复制任何测试代码**：往 `android/core/src/test` 里新增的测试，会在下一次
`:app:connectedDebugAndroidTest` 时自动在设备上跑一遍，和 `:core:test` 在桌面上跑的是同一份源码。
测试资源作为 java 资源打进测试 APK，`Golden.load` 和 `MimeSmokeTest` 里的
`getResourceAsStream("/golden/...")`、`getResourceAsStream("/mime/...")` 在设备上照常可读。

## 为什么需要 friend paths

`:core` 的少量测试用到了 `:core` 主源码里的 `internal` 声明（例如 `Db.Row.long(Int)`、
`splitSqlScript`）。Kotlin 的 `internal` 只对同一次编译可见；`:core` 自己的 test 源集是
KGP 自动给它设了 friend paths 才看得到 `main` 的 `internal`，而 `:app` 的 androidTest 编译
不是。因此 `android/app/build.gradle.kts` 给 `compileDebugAndroidTestKotlin` 补了
`-Xfriend-paths`（指向编译类路径上那一份 `:core` jar），与 KGP 给单元测试用的机制一致。

## 只能在桌面 JVM 上跑的测试

约定：给这类测试类加 JUnit 4 的类别标记

```kotlin
@Category(JvmOnly::class)
class GreenMailSmokeTest { ... }
```

`JvmOnly` 是 `android/core/src/test/kotlin/com/mailhub/core/JvmOnly.kt` 里的标记接口。
instrumented 运行时用 runner 参数排除它：

```
-e notAnnotation com.mailhub.core.JvmOnly
```

脚本里写成 `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.mailhub.core.JvmOnly`。
JUnit 4 只在收到类别过滤参数时才理会 `@Category`，所以不排除时这些测试照常执行，`:core:test` 的行为不变。

判断标准是「要不要桌面 JVM 独有能力」：依赖 `java.nio.file`（minSdk 26 起 Android 也有）、
`java.util.concurrent`、`kotlin.concurrent.thread` 的测试都能在设备上跑；只有像 L7 用 GreenMail
起本地 SMTP/IMAP 服务这种 Android 上没有等价实现的，才标 `JvmOnly`。

## 怎么跑

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android\scripts\core-on-device.ps1
```

脚本默认对 MuMu 模拟器 `127.0.0.1:16384` 执行，设备不在线时输出
`blocked：没有可用设备` 并以退出码 2 结束。设备是否开机由用户负责，脚本不会启动模拟器。

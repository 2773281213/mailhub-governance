package com.mailhub.core

/**
 * 「只能在桌面 JVM 上跑」的测试类别标记，配合 JUnit 4 的 `@Category(JvmOnly::class)` 使用。
 *
 * `:core` 的测试默认都要能在 Android 运行时上跑：`:app` 的 `androidTest` 源码集直接指向
 * `android/core/src/test/kotlin` 和 `android/core/src/test/resources`，在设备上原样编译执行。
 * 只有确实依赖桌面 JVM 独有能力的测试才标这个类别，目前的典型例子是 L7 用 GreenMail
 * 起本地 SMTP/IMAP 服务的那批测试（Android 上没有等价实现）。
 *
 * instrumented 运行时用 runner 参数排除它们：
 * `-e notAnnotation com.mailhub.core.JvmOnly`
 * （脚本里写成 `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.mailhub.core.JvmOnly`，
 * 见 `android/scripts/core-on-device.ps1`）。
 *
 * JUnit 4 只在收到类别过滤参数时才理会 `@Category`，所以不加排除参数时这些测试照常执行，
 * `:core:test` 在桌面上的行为不变。
 */
interface JvmOnly

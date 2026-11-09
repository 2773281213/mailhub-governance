package com.mailhub.core

/**
 * 「只能在桌面 JVM 上跑」的测试标记，加在测试类或测试方法上。
 *
 * `:core` 的测试默认都要能在 Android 运行时上跑：`:app` 的 `androidTest` 源码集直接指向
 * `android/core/src/test/kotlin` 和 `android/core/src/test/resources`，在设备上原样编译执行。
 * 只有确实依赖桌面 JVM 独有能力的测试才标它，例如读仓库里的源码文件、起 GreenMail。
 *
 * instrumented 运行时用 runner 参数排除：`-e notAnnotation com.mailhub.core.JvmOnly`
 * （脚本里写成 `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.mailhub.core.JvmOnly`，
 * 见 `android/scripts/core-on-device.ps1`）。AndroidJUnitRunner 的这个参数只认注解，不认
 * JUnit 的 `@Category`，所以这里必须是注解类。`:core:test` 在桌面上不受影响。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class JvmOnly

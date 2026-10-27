// AGP 9.0 自带 Kotlin 支持（内置 KGP 2.2.10），因此模块里不再 apply org.jetbrains.kotlin.android。
// 这里只把 KGP 拉到与 libs.versions.toml 里相同的版本（AGP 9.0 发布说明里的“升级 KGP”做法），
// 保证 Kotlin 编译器与 org.jetbrains.kotlin.plugin.compose / serialization 版本一致。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

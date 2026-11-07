pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MailHubAndroid"
include(":app")
// 本机模式的邮件引擎：纯 Kotlin/JVM，单元测试在本机 JVM 上直接跑。
include(":core")

# MailHub release 构建的 R8 保留规则。
#
# 写规则之前先把三个库的产物（jar / aar）打开，看它们自带了哪些 R8 / ProGuard 规则
# （版本取自 gradle/libs.versions.toml）：
#
# 1) com.squareup.retrofit2:retrofit:3.0.0（jar）
#      META-INF/proguard/retrofit2.pro
#      关键三条：
#        -keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
#        -if interface * { @retrofit2.http.* <methods>; }
#        -keep,allowobfuscation interface <1>
#      带 @retrofit2.http 注解的方法所在的接口会被整体保留，suspend 函数也命中：
#      Kotlin 把注解放到方法上（字节码里是 messages(Continuation) 这种签名），
#      规则的 -if 只看方法注解，不看方法是不是 suspend。
#      文件里还带 -keepattributes Signature, InnerClasses, EnclosingMethod 和
#      RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations，
#      Retrofit 解析返回类型泛型、方法注解、参数注解都要靠这些属性。
#
# 2) com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0（jar）
#      META-INF 下没有任何 .pro 规则文件（只有 MANIFEST 和 kotlin_module）。
#
# 3) org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0（jar）
#      META-INF 下没有任何 .pro 规则文件。
#      规则由它的传递依赖 kotlinx-serialization-core:1.12.0 提供：
#        META-INF/com.android.tools/r8/kotlinx-serialization-common.pro
#        META-INF/com.android.tools/r8/kotlinx-serialization-r8.pro
#        META-INF/proguard/kotlinx-serialization-common.pro（与第一份内容相同）
#      common.pro 保留 @Serializable 类的 static Companion 字段，并用
#      `-if @Serializable class ** { static **$* *; } / -keepclassmembers class <2>$<3> { ... serializer(...); }`
#      保留 Companion（含具名 companion）上的 serializer()；r8.pro 保留 @Serializable 类本身、
#      object 的 INSTANCE 和 **$$serializer 的 descriptor。
#
# 因此 retrofit 接口和 @Serializable DTO 这两类都不需要我们再写 -keep：
# 上面这些规则已经被 AGP 合并进 release 的 R8 配置（见
# app/build/outputs/mapping/release/configuration.txt 里 “META-INF/com.android.tools/r8/…”
# 的段落），实测 mapping.txt 里 5 个接口和全部 DTO 的 $$serializer / Companion 都在。
# 下面只补库规则确实没覆盖到的那一处。

# IntAsBooleanSerializer 是手写的 KSerializer（object），既没有 @Serializable 注解，
# 也不是编译器生成的 **$$serializer，上面两份 kotlinx-serialization 规则的 -if 条件都不匹配它；
# 它只在 Account$$serializer、AuditEntry$$serializer 里被静态引用，一旦 R8 认为这些引用可以内联，
# object 的单例字段或 decode/encode 方法就可能被删掉/改名，带
# @Serializable(with = IntAsBooleanSerializer::class) 的字段就会在反序列化时报错。
# 所以显式保留这个 object 的单例、构造入口和公开方法（descriptor、deserialize、serialize）。
-keep class com.mailhub.client.data.model.IntAsBooleanSerializer {
    public static ** INSTANCE;
    public <methods>;
}

# L1 先整体保留，让 R8 现在就追踪 :core 用到的 Angus Mail 与 SQLite 类，提前暴露缺类问题；
# L10 接入后再收窄。
-keep class com.mailhub.core.** { *; }

# Angus Mail 与 Jakarta Activation 按资源文件里写的类名反射创建对象，R8 看不到这些引用，必须显式保留
# （R1 审核指出；类名取自 angus-mail-2.0.5.jar 与 angus-activation-2.0.3.jar 里的注册文件）：
# - META-INF/javamail.default.providers：协议到 Store 类，用 (Session, URLName) 构造。本机模式只用 imap / imaps。
# - META-INF/mailcap：MIME 类型到 DataContentHandler，用无参构造。
# - META-INF/services/*：ServiceLoader 加载的提供者，用无参构造。jakarta.mail.Provider 的注册文件里
#   六个提供者都列着，ServiceLoader 遍历时会逐个实例化，所以全部保留，不能只留 IMAP 的。
# 这些规则只有在 release 包里真正收信时才能验证，见 LOCAL_MODE.md 的 L11 真机验收。
-keep class org.eclipse.angus.mail.imap.IMAPStore { <init>(jakarta.mail.Session, jakarta.mail.URLName); }
-keep class org.eclipse.angus.mail.imap.IMAPSSLStore { <init>(jakarta.mail.Session, jakarta.mail.URLName); }
# mailcap 只注册了下面五个处理器。不要写成 `* implements DataContentHandler`：那会把 image_gif 一类
# 引用 java.awt.Image 的处理器也留下来，Android 上没有 AWT，R8 会报缺类。
-keep class org.eclipse.angus.mail.handlers.text_plain { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.text_html { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.text_xml { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.multipart_mixed { public <init>(); }
-keep class org.eclipse.angus.mail.handlers.message_rfc822 { public <init>(); }
-keep class * extends jakarta.mail.Provider { public <init>(); }
-keep class org.eclipse.angus.mail.util.MailStreamProvider { public <init>(); }
-keep class org.eclipse.angus.activation.MailcapRegistryProviderImpl { public <init>(); }
-keep class org.eclipse.angus.activation.MimeTypeRegistryProviderImpl { public <init>(); }

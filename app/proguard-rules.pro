# Juiz：反射只指向 Android 框架类（隐藏的通话音频接口），不涉及本应用的类。
# kotlinx.serialization、OkHttp、SQLDelight 自带消费者规则。
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# 无障碍服务与各组件由清单引用，AGP 会自动保留；可序列化类保留生成的 serializer
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** serializer(...); }

# ════════════════════════════════════════════════════════════
# 拾音 PickUpMusic — R8 keep 规则（v1.2.0 阶段一：体积优化）
#
# 目标：开启 R8 代码裁剪，剔除 material-icons-extended ~24MB 未引用图标与死代码，
# 同时保护所有依赖反射 / 字符串加载的第三方库不被误删。
#
# 构建配置：proguard-android.txt（自带 -dontoptimize，不开优化 pass，最稳）+ 本文件。
# 混淆保留开启；mapping.txt 输出到 build/outputs/mapping/release/ 用于反混淆崩溃栈。
# 资源压缩 shrinkResources=true：本工程 res/ 无按名加载的资源（已核查），安全。
# Kuromoji 33MB 词表是 JAR classpath 资源，资源压缩器只处理 src/main/res，不受影响。
# ════════════════════════════════════════════════════════════

# ── 通用属性：泛型签名 / 注解 / 内部类 / 行号（Gson、反射、崩溃栈可读性）──
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions
-keepattributes Deprecated
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# ── Kuromoji IPADIC 振假名分词（最高风险点）──
# com.atilika.kuromoji 内部按字符串名加载词表条目与特征类，R8 静态分析看不到这些
# 引用会误删。整包保留。词表本身是 JAR 资源（R8 不动），此处保留的是它较小的运行时代码。
-keep class com.atilika.kuromoji.** { *; }
-keep class com.atilika.** { *; }

# ── Gson（本工程仅 JsonParser 手动解析，无 @SerializedName / TypeToken 数据绑定，风险低；整包保留作保险）──
-keep class com.google.gson.** { *; }

# ── Room（2.7 由 KSP 生成具体 DAO 实现，本身无反射；仍保留实体与 DAO）──
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.shiyin.music.data.db.** { *; }

# ── Media3 / ExoPlayer / MediaSession（自带 consumer rules，补保险）──
-keep class androidx.media3.** { *; }

# ── OkHttp（自带规则；platform / 加密 Provider 用反射，压 suppress 警告）──
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── reorderable 拖拽排序库（KMP，保险保留）──
-keep class sh.calvin.reorderable.** { *; }

# ── Kotlin 元数据 ──
-keep class kotlin.Metadata { *; }

# 注：本工程已核查无 Class.forName / getDeclared* / Parcelable / Serializable 反射点，
#     无按名加载的 res/ 资源。如未来引入上述任一，须在此补 keep。

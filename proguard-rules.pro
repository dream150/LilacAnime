# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- LilacAnime: R8(전체 모드) 유지 규칙 ---

# Cast SDK 는 매니페스트 meta-data(com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME)
# 값을 Class.forName 으로 읽어 인스턴스를 만든다. 지금은 AGP 가 매니페스트 기반으로 지켜주지만,
# 이 클래스가 난독화/제거되면 Google Cast 만 런타임에 조용히 실패하므로 명시적으로 고정한다.
-keep class com.lilac.anime.cast.CastOptionsProvider { *; }
-keep class * extends com.google.android.gms.cast.framework.OptionsProvider { *; }

# libmpv 는 자체 consumer 규칙(-keep class dev.jdtech.mpv.MPVLib)을 AAR 에 포함하고 있어 추가 규칙이
# 필요 없다. 네이티브 라이브러리를 로드하는 JNI 진입점이라 규칙을 지우지 말 것.
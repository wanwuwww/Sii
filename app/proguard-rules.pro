# ZXing 扫码依赖反射较少，保留以防裁剪异常
-keep class com.google.zxing.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# 网页侧按固定名字调用 window.sii / sii_gm / SiiSniff 等方法，混淆后接口会失效
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

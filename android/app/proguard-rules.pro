# ProGuard rules for Maozi TV
-keepclassmembers class * extends android.webkit.WebView {
    public *;
}
-keepclassmembers class com.tv.live.MainActivity {
    public *;
}
-dontwarn android.webkit.**

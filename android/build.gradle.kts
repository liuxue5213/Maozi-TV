// Top-level build file for Maozi-TV Android TV WebView wrapper
plugins {
    id("com.android.application") version "8.2.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

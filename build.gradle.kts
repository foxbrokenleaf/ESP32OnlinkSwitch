// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}
// 在项目根目录的 build.gradle.kts
buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:8.9.1")
    }
}
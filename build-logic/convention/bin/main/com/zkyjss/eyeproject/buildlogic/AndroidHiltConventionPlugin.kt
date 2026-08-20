package com.zkyjss.eyeproject.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt 约定插件：应用 Hilt + KSP 并添加 hilt-android / hilt-compiler 依赖。 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.dagger.hilt.android")
            pluginManager.apply("com.google.devtools.ksp")
            dependencies {
                add("implementation", "com.google.dagger:hilt-android:2.59.2")
                add("ksp", "com.google.dagger:hilt-android-compiler:2.59.2")
            }
        }
    }
}

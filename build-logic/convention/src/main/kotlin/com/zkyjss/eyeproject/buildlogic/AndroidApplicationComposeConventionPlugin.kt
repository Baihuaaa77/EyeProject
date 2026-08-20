package com.zkyjss.eyeproject.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Application 模块 Compose 约定插件：应用 Compose 编译器插件并开启 buildFeatures.compose。 */
class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<ApplicationExtension> {
                buildFeatures {
                    compose = true
                }
            }
        }
    }
}

package com.zkyjss.eyeproject.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Kotlin 序列化约定插件：应用 serialization 编译器插件，供 @Serializable 类型安全路由使用。 */
class AndroidKotlinSerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        }
    }
}

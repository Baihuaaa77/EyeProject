plugins {
    `kotlin-dsl`
}

group = "com.zkyjss.eyeproject.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.android.tools.build:gradle:9.3.0")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    implementation("com.google.dagger:hilt-android-gradle-plugin:2.59.2")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.2.10-2.0.2")
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "eyeproject.android.library"
            implementationClass = "com.zkyjss.eyeproject.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "eyeproject.android.application"
            implementationClass = "com.zkyjss.eyeproject.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "eyeproject.android.library.compose"
            implementationClass = "com.zkyjss.eyeproject.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "eyeproject.android.application.compose"
            implementationClass = "com.zkyjss.eyeproject.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "eyeproject.android.hilt"
            implementationClass = "com.zkyjss.eyeproject.buildlogic.AndroidHiltConventionPlugin"
        }
    }
}

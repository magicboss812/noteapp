plugins {
    `kotlin-dsl`
}

group = "dev.folio.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: the root build puts the real plugins on the classpath (apply false).
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.wire.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "folio.android.application"
            implementationClass = "dev.folio.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "folio.android.library"
            implementationClass = "dev.folio.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "folio.jvm.library"
            implementationClass = "dev.folio.buildlogic.JvmLibraryConventionPlugin"
        }
        register("compose") {
            id = "folio.compose"
            implementationClass = "dev.folio.buildlogic.ComposeConventionPlugin"
        }
        register("hilt") {
            id = "folio.hilt"
            implementationClass = "dev.folio.buildlogic.HiltConventionPlugin"
        }
        register("room") {
            id = "folio.room"
            implementationClass = "dev.folio.buildlogic.RoomConventionPlugin"
        }
        register("quality") {
            id = "folio.quality"
            implementationClass = "dev.folio.buildlogic.QualityConventionPlugin"
        }
        register("wire") {
            id = "folio.wire"
            implementationClass = "dev.folio.buildlogic.WireConventionPlugin"
        }
    }
}

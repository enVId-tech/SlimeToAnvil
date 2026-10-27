import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.etran"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Spigot 1.8 runs on Java 8, so everything is compiled for Java 8 bytecode and restricted to the
    // Java 8 API surface. The same jars run unchanged up to the newest servers (Java 21/25).
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll("-Xjdk-release=8", "-Xjvm-default=all")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `java-library`
}

val protobufCompilerVersion = libs.versions.protobuf.get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.protobuf.java)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufCompilerVersion"
    }
}

tasks.test {
    useJUnitPlatform()
    // These tests drive real coroutine timing (runBlocking + delays); running
    // the forks serially avoids the executor being overloaded by many
    // wall-clock-bound tests at once.
    maxParallelForks = 1
    testLogging {
        events("failed", "skipped")
    }
}

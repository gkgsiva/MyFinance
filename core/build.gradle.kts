import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    // Desktop PDFBox, the same code as pdfbox-android in the app, to test reading (password-protected) PDF statements.
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
}

tasks.test {
    useJUnit()
}

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.2")
}

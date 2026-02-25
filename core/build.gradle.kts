plugins {
    kotlin("jvm")
}

group = "co.kofixture"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotestProperty)
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation(libs.kotestRunner)
    testImplementation(libs.kotestAssertions)
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
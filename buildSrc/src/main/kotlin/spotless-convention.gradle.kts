package buildsrc.convention

plugins {
    id("com.diffplug.spotless")
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        ktlint().editorConfigOverride(mapOf(
            "max_line_length" to "120"
        ))
    }
}

package co.kofixture.core.utils

import co.kofixture.core.fixtureModule
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string

val coreModule = fixtureModule {
    register<String>(Arb.string(3..15))
    register<Int>(Arb.int(1..100))
    register<Email>(Arb.string(4..10).map { Email("$it@example.com") })
    registerOf(::User)
}

val productModule = fixtureModule {
    register<Tag>(Arb.string(3..10).map(::Tag))
    registerOf(::Project)
    registerOf(::ProjectName)
}

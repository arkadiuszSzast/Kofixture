package co.kofixture.core

import java.util.Collections
import java.util.WeakHashMap

/**
 * Holds the global state and registry management for Kofixture.
 *
 * It manages [FixtureRegistry] instances for each [KofixtureTest] spec,
 * ensuring they are properly initialized before tests and released after.
 */
object KofixtureContext {

    /**
     * Default modules to be used if a [KofixtureTest] doesn't specify its own.
     */
    var defaultModules: List<FixtureModule> = emptyList()

    @PublishedApi
    internal val registries: MutableMap<KofixtureTest, FixtureRegistry> =
        Collections.synchronizedMap(WeakHashMap())

    @PublishedApi
    internal fun registryFor(spec: KofixtureTest): FixtureRegistry = registries[spec] ?: error(
        "No fixture registry found for ${spec::class.simpleName}. " +
            "Make sure it implements KofixtureTest — beforeSpec should have built the registry.",
    )

    internal fun buildFor(spec: KofixtureTest) {
        val modules = spec.fixtureModules.ifEmpty { defaultModules }
        require(modules.isNotEmpty()) {
            "No fixture modules configured for ${spec::class.simpleName}. " +
                "Either set FixtureKit.defaultModules in ProjectConfig, " +
                "or override fixtureModules in the spec."
        }
        registries[spec] = fixtures { modules.forEach { includes(it) } }
    }

    internal fun releaseFor(spec: KofixtureTest) {
        registries.remove(spec)
    }
}

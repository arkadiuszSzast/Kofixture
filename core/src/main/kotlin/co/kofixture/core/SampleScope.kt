package co.kofixture.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import kotlin.reflect.KProperty1
import kotlin.reflect.typeOf

/**
 * DSL receiver for [FixtureRegistry.sample] and [FixtureRegistry.arb] block variants.
 *
 * Mirrors the top-level [override] functions as methods, collecting overrides
 * to apply during sampling.
 *
 * ```kotlin
 * registry.sample<Project> {
 *     override(Project::name, "FixedName")
 *     override<User>(adminUser)
 *     override(Project::memberCount, Arb.int(1..5))
 * }
 * ```
 */
class SampleScope
@PublishedApi
internal constructor() {
    @PublishedApi
    internal val overrides = mutableListOf<FixtureOverride>()

    @PublishedApi
    internal var collectionConfig: CollectionConfig? = null

    /**
     * Overrides collection size ranges for this sample only.
     * Falls back to the registry-level [CollectionConfig] when not set.
     *
     * ```kotlin
     * registry.sample<Project> {
     *     collections { listSize = 1..3 }
     * }
     * ```
     */
    fun collections(block: CollectionConfigBuilder.() -> Unit) {
        collectionConfig = CollectionConfigBuilder().apply(block).build()
    }

    /** Overrides all fields of type [T] with the given [Arb]. */
    inline fun <reified T> override(arb: Arb<T>) {
        overrides += FixtureOverride.TypeBased(type = typeOf<T>(), arb = arb)
    }

    /** Overrides all fields of type [T] with a constant [value]. */
    inline fun <reified T> override(value: T) {
        overrides += FixtureOverride.TypeBased(type = typeOf<T>(), arb = Arb.constant(value))
    }

    /** Overrides a specific [property] on [Owner] with the given [Arb]. */
    inline fun <reified Owner : Any, reified Prop> override(property: KProperty1<Owner, Prop>, arb: Arb<Prop>) {
        overrides +=
            FixtureOverride.Named(
                key = NamedOverrideKey(typeOf<Owner>(), property.name),
                arb = arb,
            )
    }

    /** Overrides a specific [property] on [Owner] with a constant [value]. */
    inline fun <reified Owner : Any, reified Prop> override(property: KProperty1<Owner, Prop>, value: Prop) {
        overrides +=
            FixtureOverride.Named(
                key = NamedOverrideKey(typeOf<Owner>(), property.name),
                arb = Arb.constant(value),
            )
    }
}

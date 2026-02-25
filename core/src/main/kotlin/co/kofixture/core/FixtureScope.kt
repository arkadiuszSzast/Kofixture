package co.kofixture.core

import io.kotest.property.Arb
import kotlin.reflect.KProperty1
import kotlin.reflect.typeOf

/**
 * Receiver for the [FixtureRegistryBuilder.register] factory lambda.
 *
 * Provides [get] for resolving Arbs from the registry during the wiring phase.
 * The result is an [Arb]<T> — typically produced by Kotest's `arbitrary {}`.
 *
 * ```kotlin
 * register<Project> {
 *     val nameArb  = get<ProjectName>() // resolve before arbitrary{}
 *     val ownerArb = get<User>()
 *     arbitrary {
 *         Project(name = nameArb.bind(), owner = ownerArb.bind())
 *     }
 * }
 * ```
 */
class FixtureScope<T> @PublishedApi internal constructor(
    @PublishedApi internal val registry: FixtureRegistry,
    @PublishedApi internal val active: ActiveOverrides,
) {
    /**
     * Resolves an [Arb]<R> from the registry, respecting active overrides.
     * Call this before `arbitrary {}` so Kotest can coordinate edge cases and shrinking.
     */
    inline fun <reified R> get(): Arb<R> = registry.resolve(typeOf<R>(), active)

    /**
     * Resolves an [Arb]<R> for a specific property, checking named overrides first.
     *
     * Use this instead of [get] when you want `override(Owner::field, ...)` to work
     * for types registered with the factory [FixtureRegistryBuilder.register] variant.
     *
     * Priority:
     * 1. Named override (`override(Project::name, ...)`)
     * 2. Type-based override (`override<ProjectName>(...)`)
     * 3. Registry lookup / auto-derivation
     *
     * ```kotlin
     * register<Project> {
     *     val nameArb  = get(Project::name)   // respects override(Project::name, ...)
     *     val ownerArb = get(Project::owner)  // respects override(Project::owner, ...)
     *     arbitrary {
     *         Project(name = nameArb.bind(), owner = ownerArb.bind())
     *     }
     * }
     * ```
     */
    inline fun <reified Owner : Any, reified R> get(property: KProperty1<Owner, R>): Arb<R> {
        val key = NamedOverrideKey(typeOf<Owner>(), property.name)
        @Suppress("UNCHECKED_CAST")
        return active.byName[key] as? Arb<R>
            ?: registry.resolve(typeOf<R>(), active)
    }
}
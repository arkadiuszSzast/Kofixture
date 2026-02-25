package co.kofixture.core

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.set
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

/**
 * Decomposed overrides propagated through the resolution graph.
 * Split once on entry to [FixtureRegistry.sample]/[FixtureRegistry.arb] so each lookup is O(1).
 */
@PublishedApi
internal data class ActiveOverrides(
    val byType: Map<KType, Arb<*>> = emptyMap(),
    val byName: Map<NamedOverrideKey, Arb<*>> = emptyMap(),
    val collectionConfig: CollectionConfig? = null,
) {
    companion object {
        @PublishedApi
        internal val EMPTY = ActiveOverrides()

        @PublishedApi
        internal fun from(overrides: Array<out FixtureOverride>): ActiveOverrides {
            if (overrides.isEmpty()) return EMPTY
            val byType = overrides.filterIsInstance<FixtureOverride.TypeBased>().associate { it.type to it.arb }
            val byName = overrides.filterIsInstance<FixtureOverride.Named>().associate { it.key to it.arb }
            return ActiveOverrides(byType, byName)
        }

        @PublishedApi
        internal fun from(scope: SampleScope): ActiveOverrides =
            from(scope.overrides.toTypedArray())
                .copy(collectionConfig = scope.collectionConfig)
    }
}

class FixtureRegistry @PublishedApi internal constructor(
    @PublishedApi internal val factories: Map<KType, (FixtureRegistry, ActiveOverrides) -> Arb<*>>,
    val collectionConfig: CollectionConfig = CollectionConfig(),
) {

    /**
     * Generates a single value, optionally overriding specific types or fields.
     *
     * ```kotlin
     * // type-based — all String fields get the same Arb
     * registry.sample<Person>(override<String>(Arb.string(1..3)))
     *
     * // named — only a specific field
     * registry.sample<Person>(override(Person::surname, "Kowalski"))
     *
     * // both at once — named wins over type-based for that field
     * registry.sample<Person>(
     *     override<String>(Arb.string(1..3)),
     *     override(Person::surname, "Kowalski"),
     * )
     * ```
     */
    inline fun <reified T> sample(vararg overrides: FixtureOverride): T =
        sampleInternal(typeOf<T>(), overrides, RandomSource.default())

    /** Generates a single value with a specific [RandomSource] for deterministic results. */
    inline fun <reified T> sample(
        randomSource: RandomSource,
        vararg overrides: FixtureOverride,
    ): T = sampleInternal(typeOf<T>(), overrides, randomSource)

    /**
     * Generates a single value with overrides defined in a [SampleScope] block.
     *
     * ```kotlin
     * val project = registry.sample<Project> {
     *     override(Project::name, "FixedName")
     *     override<User>(adminUser)
     *     override(Project::memberCount, Arb.int(1..5))
     * }
     * ```
     */
    inline fun <reified T> sample(block: SampleScope.() -> Unit): T =
        sampleFromScope(typeOf<T>(), SampleScope().apply(block), RandomSource.default())

    /**
     * Generates a single value with a specific [RandomSource] and overrides in a block.
     *
     * ```kotlin
     * val project = registry.sample<Project>(RandomSource.seeded(42L)) {
     *     override(Project::name, "FixedName")
     *     collections { listSize = 1..3 }
     * }
     * ```
     */
    inline fun <reified T> sample(randomSource: RandomSource, block: SampleScope.() -> Unit): T =
        sampleFromScope(typeOf<T>(), SampleScope().apply(block), randomSource)

    @PublishedApi
    internal fun <T> sampleFromScope(type: KType, scope: SampleScope, randomSource: RandomSource): T {
        @Suppress("UNCHECKED_CAST")
        return resolve<T>(type, ActiveOverrides.from(scope)).sample(randomSource).value
    }

    @PublishedApi
    internal fun <T> sampleInternal(
        type: KType,
        overrides: Array<out FixtureOverride>,
        randomSource: RandomSource,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return resolve<T>(type, ActiveOverrides.from(overrides)).sample(randomSource).value
    }

    /**
     * Returns an [Arb]<T> with optional overrides. Use with `checkAll {}`.
     *
     * ```kotlin
     * checkAll(registry.arb<Project>(override(Project::owner, adminUser))) { project ->
     *     project.owner shouldBe adminUser
     * }
     * ```
     */
    inline fun <reified T> arb(vararg overrides: FixtureOverride): Arb<T> =
        arbInternal(typeOf<T>(), overrides)

    /**
     * Returns an [Arb]<T> with overrides defined in a [SampleScope] block.
     *
     * ```kotlin
     * checkAll(
     *     registry.arb<Project> {
     *         override(Project::owner, adminUser)
     *         override(Project::memberCount, Arb.int(1..5))
     *     }
     * ) { project ->
     *     project.owner shouldBe adminUser
     * }
     * ```
     */
    inline fun <reified T> arb(block: SampleScope.() -> Unit): Arb<T> =
        resolve(typeOf<T>(), ActiveOverrides.from(SampleScope().apply(block)))

    @PublishedApi
    internal fun <T> arbInternal(
        type: KType,
        overrides: Array<out FixtureOverride>,
    ): Arb<T> = resolve(type, ActiveOverrides.from(overrides))

    /**
     * Recursive type resolver.
     *
     * Resolution order for every type:
     * 1. Type-based override from [ActiveOverrides.byType]
     * 2. Exact match in [factories]
     * 3. Nullable T?  → resolve(T).orNull()
     * 4. List / Set / Map / Collection / Iterable → recurse on type arguments
     * 5. Error with a descriptive message
     */
    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <T> resolve(type: KType, active: ActiveOverrides): Arb<T> {
        // 1. Type-based override
        active.byType[type]?.let { return it as Arb<T> }

        // 2. Exact match in factories
        factories[type]?.let { return it.invoke(this, active) as Arb<T> }

        // 3. Nullable: String? → resolve(String).orNull()
        if (type.isMarkedNullable) {
            val nonNull = type.withNullability(false)
            return resolve<Any>(nonNull, active).orNull() as Arb<T>
        }

        // 4. Known generic containers
        return tryDeriveContainer(type, active) ?: missingFixtureError(type)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> tryDeriveContainer(type: KType, active: ActiveOverrides): Arb<T>? {
        val classifier = type.classifier as? KClass<*> ?: return null
        val config = active.collectionConfig ?: collectionConfig
        return when (classifier) {
            List::class, Collection::class, Iterable::class ->
                Arb.list(resolve<Any?>(type.typeArg(0), active), config.listSize) as Arb<T>

            Set::class ->
                Arb.set(resolve<Any?>(type.typeArg(0), active), config.setSize) as Arb<T>

            Map::class ->
                Arb.map(
                    resolve<Any?>(type.typeArg(0), active),
                    resolve<Any?>(type.typeArg(1), active),
                    minSize = config.mapSize.first,
                    maxSize = config.mapSize.last,
                ) as Arb<T>

            else -> null
        }
    }

    private fun KType.typeArg(index: Int): KType =
        arguments.getOrNull(index)?.type
            ?: error("Star projection is not supported in auto-derivation. Type: $this")

    private fun missingFixtureError(type: KType): Nothing = error(
        buildString {
            appendLine("No fixture registered for type: $type")
            appendLine("Registered types:")
            factories.keys.forEach { appendLine("  - $it") }
            appendLine("Hint: for generic types, make sure all element types are registered.")
        }
    )

    fun registeredTypes(): Set<KType> = factories.keys
}

// ── FixtureModule ─────────────────────────────────────────────────────────────

/**
 * A reusable, composable unit of fixture registrations.
 *
 * Define modules per domain or Gradle module, then compose them in [fixtures].
 *
 * ```kotlin
 * // core/src/test/kotlin/CoreFixtures.kt
 * val coreFixtures = fixtureModule {
 *     register<String>(Arb.string(3..15))
 *     registerOf(::User)
 * }
 *
 * // product/src/test/kotlin/ProductFixtures.kt
 * val productFixtures = fixtureModule {
 *     registerOf(::Product)  // Product depends on User — comes from coreFixtures
 * }
 *
 * // In tests:
 * val registry = fixtures {
 *     includes(coreFixtures, productFixtures)
 * }
 * ```
 */
class FixtureModule @PublishedApi internal constructor(
    @PublishedApi internal val block: FixtureRegistryBuilder.() -> Unit,
)

/**
 * Creates a [FixtureModule] — a reusable block of fixture registrations.
 * Modules are not evaluated until included in a [fixtures] block.
 */
fun fixtureModule(block: FixtureRegistryBuilder.() -> Unit): FixtureModule = FixtureModule(block)

class FixtureRegistryBuilder @PublishedApi internal constructor() {

    @PublishedApi
    internal val factories = mutableMapOf<KType, (FixtureRegistry, ActiveOverrides) -> Arb<*>>()

    var collectionConfig: CollectionConfig = CollectionConfig()

    /**
     * Applies one or more [FixtureModule]s into this registry.
     * Registrations are applied in order — later modules override earlier ones for the same type.
     *
     * ```kotlin
     * val registry = fixtures {
     *     includes(coreFixtures, productFixtures)
     *     register<String>(Arb.string(1..5))  // can still add or override after includes
     * }
     * ```
     */
    fun includes(vararg modules: FixtureModule) {
        modules.forEach { it.block(this) }
    }

    /**
     * Registers a ready-made [Arb] for a simple type that needs no access to other fixtures.
     *
     * ```kotlin
     * register<ProjectName>(Arb.string(3..50).map(::ProjectName))
     * register<Int>(Arb.int(1..100))
     * ```
     */
    inline fun <reified T> register(arb: Arb<T>) {
        factories[typeOf<T>()] = { _, _ -> arb }
    }

    /**
     * Registers a factory for composite types that depend on other fixtures.
     *
     * The lambda receiver is [FixtureScope] which provides [FixtureScope.get] for
     * resolving Arbs from the registry. The lambda must return an [Arb]<T> —
     * typically constructed with Kotest's `arbitrary {}`.
     *
     * Call [FixtureScope.get] **before** `arbitrary {}` so Kotest can see all Arbs
     * upfront and coordinate edge cases and shrinking across the whole object.
     *
     * ```kotlin
     * register<Project> {
     *     val nameArb  = get<ProjectName>()
     *     val ownerArb = get<User>()
     *     arbitrary {
     *         Project(name = nameArb.bind(), owner = ownerArb.bind())
     *     }
     * }
     * ```
     *
     * **Note:** named overrides (`override(Project::name, ...)`) do not apply to types
     * registered this way — use [registerOf] where possible.
     */
    inline fun <reified T> register(crossinline factory: FixtureScope<T>.() -> Arb<T>) {
        factories[typeOf<T>()] = { registry, active ->
            FixtureScope<T>(registry, active).factory()
        }
    }

    /**
     * Registers a generator from a constructor reference — all parameters are
     * auto-wired from the registry.`.
     *
     * Automatically handles:
     * - Simple types registered in the registry
     * - Generic containers: `List<T>`, `Set<T>`, `Map<K,V>`
     * - Nullable types: `String?`
     * - **Named overrides**: `override(Person::surname, "Kowalski")`
     *
     * Override priority per parameter:
     * 1. Named override  (`override(Person::surname, ...)`)
     * 2. Type-based override (`override<String>(...)`)
     * 3. Registry lookup / auto-derivation
     *
     * ```kotlin
     * data class Person(val name: String, val surname: String)
     *
     * registerOf(::Person)
     *
     * // Later in a test:
     * registry.sample<Person>(
     *     override(Person::surname, "Kowalski")  // name remains random
     * )
     * ```
     */
    inline fun <reified T : Any> registerOf(constructor: KFunction<T>) {
        val ownerType = typeOf<T>()
        factories[ownerType] = { reg, active ->
            // Arbs resolved OUTSIDE arbitrary{} so Kotest can see them upfront —
            // edge cases and shrinking are coordinated across the whole object.
            val arbs: List<Arb<*>> = constructor.parameters.map { param ->
                val namedKey = NamedOverrideKey(ownerType, param.name!!)
                when {
                    // 1. Named override for this specific field
                    active.byName.containsKey(namedKey) -> active.byName[namedKey]!!
                    // 2. Type-based override for this parameter type
                    active.byType.containsKey(param.type) -> active.byType[param.type]!!
                    // 3. Normal resolution — registry + auto-derivation
                    else -> reg.resolve(param.type, active)
                }
            }
            arbitrary {
                @Suppress("UNCHECKED_CAST")
                val values = arbs.map { it.bind() }.toTypedArray()
                constructor.call(*values)
            }
        }
    }

    @PublishedApi
    internal fun buildRegistry(): FixtureRegistry = FixtureRegistry(factories.toMap(), collectionConfig)
}

/**
 * Creates a [FixtureRegistry] using the DSL.
 *
 * ```kotlin
 * val registry = fixtures {
 *     register<String>(Arb.string(5..20))
 *     register<Tag>(Arb.string(3..10).map(::Tag))
 *     registerOf(::User)     // List<Tag>, Map<String,Tag> are auto-derived
 *     registerOf(::Project)
 * }
 * ```
 */
fun fixtures(block: FixtureRegistryBuilder.() -> Unit): FixtureRegistry =
    FixtureRegistryBuilder().apply(block).buildRegistry()
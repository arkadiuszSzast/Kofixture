package co.kofixture.core

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.set
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

// ── Override ──────────────────────────────────────────────────────────────────

/**
 * Key for a named override — pairs the owner type with a parameter name.
 * Allows distinguishing `Person::name` from `Person::surname` even though both are `String`.
 *
 * @PublishedApi required because this type appears in public inline override() functions.
 */
@PublishedApi
internal data class NamedOverrideKey(
    val ownerType: KType,
    val paramName: String,
)

/**
 * Represents a single override applied during sampling.
 *
 * Two modes — exactly one must be set:
 * - **type-based**: replaces all fields of a given type
 * - **named**: replaces one specific field on a specific owner type
 */
class FixtureOverride @PublishedApi internal constructor(
    @PublishedApi internal val arb: Arb<*>,
    @PublishedApi internal val type: KType? = null,
    @PublishedApi internal val namedKey: NamedOverrideKey? = null,
) {
    init {
        require((type != null) xor (namedKey != null)) {
            "FixtureOverride must have either 'type' or 'namedKey' — not both and not neither."
        }
    }
}

// Type-based overrides

/** Overrides all fields of type [T] with the given [Arb]. */
inline fun <reified T> override(arb: Arb<T>): FixtureOverride =
    FixtureOverride(arb = arb, type = typeOf<T>())

/** Overrides all fields of type [T] with a constant [value]. */
inline fun <reified T> override(value: T): FixtureOverride =
    FixtureOverride(arb = Arb.constant(value), type = typeOf<T>())

// Named overrides

/** Overrides a specific [property] on [Owner] with the given [Arb]. */
inline fun <reified Owner : Any, reified Prop> override(
    property: KProperty1<Owner, Prop>,
    arb: Arb<Prop>,
): FixtureOverride = FixtureOverride(
    arb = arb,
    namedKey = NamedOverrideKey(typeOf<Owner>(), property.name),
)

/** Overrides a specific [property] on [Owner] with a constant [value]. */
inline fun <reified Owner : Any, reified Prop> override(
    property: KProperty1<Owner, Prop>,
    value: Prop,
): FixtureOverride = FixtureOverride(
    arb = Arb.constant(value),
    namedKey = NamedOverrideKey(typeOf<Owner>(), property.name),
)

// ── Collection config ─────────────────────────────────────────────────────────

/** Default size ranges used when auto-deriving collection types. */
data class CollectionConfig(
    val listSize: IntRange = 0..5,
    val setSize: IntRange = 0..5,
    val mapSize: IntRange = 0..5,
)

// ── Active overrides ──────────────────────────────────────────────────────────

/**
 * Decomposed overrides propagated through the resolution graph.
 * Split once on entry to sample()/arb() so each lookup is O(1).
 *
 * @PublishedApi required because this type is used in @PublishedApi internal
 * functions that are called from public inline functions.
 */
@PublishedApi
internal data class ActiveOverrides(
    val byType: Map<KType, Arb<*>> = emptyMap(),
    val byName: Map<NamedOverrideKey, Arb<*>> = emptyMap(),
) {
    companion object {
        @PublishedApi
        internal val EMPTY = ActiveOverrides()

        @PublishedApi
        internal fun from(overrides: Array<out FixtureOverride>): ActiveOverrides {
            if (overrides.isEmpty()) return EMPTY
            val byType = mutableMapOf<KType, Arb<*>>()
            val byName = mutableMapOf<NamedOverrideKey, Arb<*>>()
            for (o in overrides) {
                when {
                    o.type != null     -> byType[o.type] = o.arb
                    o.namedKey != null -> byName[o.namedKey] = o.arb
                }
            }
            return ActiveOverrides(byType, byName)
        }
    }
}

// ── Registry ──────────────────────────────────────────────────────────────────

class FixtureRegistry @PublishedApi internal constructor(
    @PublishedApi internal val factories: Map<KType, (FixtureRegistry, ActiveOverrides) -> Arb<*>>,
    val collectionConfig: CollectionConfig = CollectionConfig(),
) {

    // ── get() ─────────────────────────────────────────────────────────────────

    /**
     * Returns an [Arb]<T> for the registered type. Supports auto-derivation of
     * collections and nullable types.
     *
     * Call this **before** the `arbitrary {}` block when registering composite types
     * so Kotest can coordinate edge cases across all bound Arbs.
     */
    inline fun <reified T> get(): Arb<T> =
        resolve(typeOf<T>(), ActiveOverrides.EMPTY)

    // ── sample() ──────────────────────────────────────────────────────────────

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

    @PublishedApi
    internal fun <T> sampleInternal(
        type: KType,
        overrides: Array<out FixtureOverride>,
        randomSource: RandomSource,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return resolve<T>(type, ActiveOverrides.from(overrides)).sample(randomSource).value
    }

    // ── arb() ─────────────────────────────────────────────────────────────────

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

    @PublishedApi
    internal fun <T> arbInternal(
        type: KType,
        overrides: Array<out FixtureOverride>,
    ): Arb<T> = resolve(type, ActiveOverrides.from(overrides))

    // ── Resolver ──────────────────────────────────────────────────────────────

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
        return when (classifier) {
            List::class, Collection::class, Iterable::class -> {
                Arb.list(resolve<Any?>(type.typeArg(0), active), collectionConfig.listSize) as Arb<T>
            }
            Set::class -> {
                Arb.set(resolve<Any?>(type.typeArg(0), active), collectionConfig.setSize) as Arb<T>
            }
            Map::class -> {
                Arb.map(
                    resolve<Any?>(type.typeArg(0), active),
                    resolve<Any?>(type.typeArg(1), active),
                    minSize = collectionConfig.mapSize.first,
                    maxSize = collectionConfig.mapSize.last,
                ) as Arb<T>
            }
            else -> null
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

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

    /** Returns the set of all explicitly registered types. */
    fun registeredTypes(): Set<KType> = factories.keys
}

// ── Builder DSL ───────────────────────────────────────────────────────────────

class FixtureRegistryBuilder @PublishedApi internal constructor() {

    @PublishedApi
    internal val factories = mutableMapOf<KType, (FixtureRegistry, ActiveOverrides) -> Arb<*>>()

    var collectionConfig: CollectionConfig = CollectionConfig()

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
     * Registers a factory that receives the active registry — for composite types
     * that depend on other fixtures.
     *
     * **Important:** call `reg.get<T>()` **before** the `arbitrary {}` block so Kotest
     * can see all Arbs upfront and coordinate edge cases and shrinking.
     *
     * ```kotlin
     * register<Project> { reg ->
     *     val nameArb  = reg.get<ProjectName>()
     *     val ownerArb = reg.get<User>()
     *     arbitrary {
     *         Project(name = nameArb.bind(), owner = ownerArb.bind())
     *     }
     * }
     * ```
     *
     * **Note:** named overrides (`override(Project::name, ...)`) do not apply to types
     * registered this way — use [registerOf] where possible.
     */
    inline fun <reified T> register(crossinline factory: (FixtureRegistry) -> Arb<T>) {
        factories[typeOf<T>()] = { registry, _ -> factory(registry) }
    }

    /**
     * Registers a generator from a constructor reference — all parameters are
     * auto-wired from the registry. Equivalent to Koin's `singleOf(::Class)`.
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
            // Arbs are built OUTSIDE arbitrary{} so Kotest can see them upfront —
            // edge cases and shrinking are coordinated across the whole object.
            val arbs: List<Arb<*>> = constructor.parameters.map { param ->
                val namedKey = NamedOverrideKey(ownerType, param.name!!)
                when {
                    // 1. Named override for this specific field
                    active.byName.containsKey(namedKey) -> active.byName[namedKey]!!
                    // 2. Type-based override for this parameter type
                    active.byType.containsKey(param.type) -> active.byType[param.type]!!
                    // 3. Normal resolution — registry + auto-derivation
                    else -> reg.resolve<Any?>(param.type, active)
                }
            }
            arbitrary {
                @Suppress("UNCHECKED_CAST")
                val values = arbs.map { (it as Arb<Any?>).bind() }.toTypedArray()
                constructor.call(*values)
            }
        }
    }

    @PublishedApi
    internal fun build(): FixtureRegistry = FixtureRegistry(factories.toMap(), collectionConfig)
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
    FixtureRegistryBuilder().apply(block).build()
package co.kofixture.core

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import kotlin.concurrent.Volatile
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Property delegate that creates a sample of type [T] when first accessed.
 *
 * It uses the [FixtureRegistry] for the current [KofixtureTest] spec, applying
 * optional [overrides].
 *
 * ```kotlin
 * class MyTest : StringSpec(), KofixtureTest {
 *     val project by sample<Project>(Project::name override "Custom")
 *
 *     init {
 *         "it works" {
 *             project.name shouldBe "Custom"
 *         }
 *     }
 * }
 * ```
 */
inline fun <reified T> KofixtureTest.sample(vararg overrides: FixtureOverride): ReadOnlyProperty<Any?, T> =
    SampleDelegate(typeOf<T>(), overrides, this)

/**
 * Property delegate that creates a sample of type [T] when first accessed.
 *
 * It uses the [FixtureRegistry] for the current [KofixtureTest] spec, applying
 * overrides from the given [block].
 *
 * ```kotlin
 * class MyTest : StringSpec(), KofixtureTest {
 *     val project by sample<Project> {
 *         override(Project::name, "Custom")
 *     }
 * }
 * ```
 */
inline fun <reified T> KofixtureTest.sample(noinline block: SampleScope.() -> Unit): ReadOnlyProperty<Any?, T> =
    SampleBlockDelegate(typeOf<T>(), block, this)

/**
 * Property delegate that provides an [Arb] of type [T].
 *
 * It uses the [FixtureRegistry] for the current [KofixtureTest] spec, applying
 * optional [overrides].
 */
inline fun <reified T> KofixtureTest.arb(vararg overrides: FixtureOverride): ReadOnlyProperty<Any?, Arb<T>> =
    ArbDelegate(typeOf<T>(), overrides, this)

/**
 * Property delegate that provides an [Arb] of type [T].
 *
 * It uses the [FixtureRegistry] for the current [KofixtureTest] spec, applying
 * overrides from the given [block].
 */
inline fun <reified T> KofixtureTest.arb(noinline block: SampleScope.() -> Unit): ReadOnlyProperty<Any?, Arb<T>> =
    ArbBlockDelegate(typeOf<T>(), block, this)

private sealed interface Cache<out T> {
    data object Empty : Cache<Nothing>

    @JvmInline
    value class Filled<T>(val value: T) : Cache<T>
}

/**
 * Caching delegate for generating a sample value of type [T] with [overrides].
 */
class SampleDelegate<T> @PublishedApi internal constructor(
    private val type: KType,
    private val overrides: Array<out FixtureOverride>,
    private val spec: KofixtureTest,
) : ReadOnlyProperty<Any?, T> {
    @Volatile private var cached: Cache<T> = Cache.Empty

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (val c = cached) {
        is Cache.Filled -> c.value
        is Cache.Empty -> KofixtureContext.registryFor(spec)
            .sampleInternal<T>(type, overrides, RandomSource.default())
            .also { cached = Cache.Filled(it) }
    }
}

/**
 * Caching delegate for generating a sample value of type [T] with a configuration [block].
 */
class SampleBlockDelegate<T> @PublishedApi internal constructor(
    private val type: KType,
    private val block: SampleScope.() -> Unit,
    private val spec: KofixtureTest,
) : ReadOnlyProperty<Any?, T> {
    @Volatile private var cached: Cache<T> = Cache.Empty

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (val c = cached) {
        is Cache.Filled -> c.value
        is Cache.Empty -> KofixtureContext.registryFor(spec)
            .sampleFromScope<T>(type, SampleScope().apply(block), RandomSource.default())
            .also { cached = Cache.Filled(it) }
    }
}

/**
 * Delegate that provides an [Arb] for type [T] with [overrides].
 */
class ArbDelegate<T> @PublishedApi internal constructor(
    private val type: KType,
    private val overrides: Array<out FixtureOverride>,
    private val spec: KofixtureTest,
) : ReadOnlyProperty<Any?, Arb<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Arb<T> =
        KofixtureContext.registryFor(spec).arbInternal(type, overrides)
}

/**
 * Delegate that provides an [Arb] for type [T] with a configuration [block].
 */
class ArbBlockDelegate<T> @PublishedApi internal constructor(
    private val type: KType,
    private val block: SampleScope.() -> Unit,
    private val spec: KofixtureTest,
) : ReadOnlyProperty<Any?, Arb<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Arb<T> = KofixtureContext.registryFor(spec)
        .resolve(type, ActiveOverrides.from(SampleScope().apply(block)))
}

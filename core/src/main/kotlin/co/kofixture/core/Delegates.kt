package co.kofixture.core

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import kotlin.concurrent.Volatile
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

inline fun <reified T> KofixtureTest.sample(vararg overrides: FixtureOverride): ReadOnlyProperty<Any?, T> =
    SampleDelegate(typeOf<T>(), overrides, this)

inline fun <reified T> KofixtureTest.sample(noinline block: SampleScope.() -> Unit): ReadOnlyProperty<Any?, T> =
    SampleBlockDelegate(typeOf<T>(), block, this)

inline fun <reified T> KofixtureTest.arb(vararg overrides: FixtureOverride): ReadOnlyProperty<Any?, Arb<T>> =
    ArbDelegate(typeOf<T>(), overrides, this)

inline fun <reified T> KofixtureTest.arb(noinline block: SampleScope.() -> Unit): ReadOnlyProperty<Any?, Arb<T>> =
    ArbBlockDelegate(typeOf<T>(), block, this)

private sealed interface Cache<out T> {
    data object Empty : Cache<Nothing>

    @JvmInline
    value class Filled<T>(val value: T) : Cache<T>
}

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

class ArbDelegate<T> @PublishedApi internal constructor(
    private val type: KType,
    private val overrides: Array<out FixtureOverride>,
    private val spec: KofixtureTest,
) : ReadOnlyProperty<Any?, Arb<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Arb<T> =
        KofixtureContext.registryFor(spec).arbInternal(type, overrides)
}

class ArbBlockDelegate<T> @PublishedApi internal constructor(
    private val type: KType,
    private val block: SampleScope.() -> Unit,
    private val spec: KofixtureTest,
) : ReadOnlyProperty<Any?, Arb<T>> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Arb<T> = KofixtureContext.registryFor(spec)
        .resolve(type, ActiveOverrides.from(SampleScope().apply(block)))
}

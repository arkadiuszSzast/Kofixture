package co.kofixture.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Key for a named override — pairs the owner type with a parameter name.
 * Allows distinguishing `Person::name` from `Person::surname` even though both are `String`.
 */
@PublishedApi
internal data class NamedOverrideKey(val ownerType: KType, val paramName: String)

/**
 * Represents a single override applied during sampling.
 *
 * Two variants — created exclusively via the [override] factory functions:
 * - [TypeBased] — replaces all fields of a given type across the whole object graph
 * - [Named] — replaces one specific field on a specific owner type
 */
sealed interface FixtureOverride {
    /** Replaces all fields whose type matches [type] with values from [arb]. */
    class TypeBased
    @PublishedApi
    internal constructor(
        @PublishedApi internal val type: KType,
        @PublishedApi internal val arb: Arb<*>,
    ) : FixtureOverride

    /** Replaces the field identified by [key] with values from [arb]. */
    class Named
    @PublishedApi
    internal constructor(
        @PublishedApi internal val key: NamedOverrideKey,
        @PublishedApi internal val arb: Arb<*>,
    ) : FixtureOverride
}

/** Overrides all fields of type [T] with the given [Arb]. */
inline fun <reified T> override(arb: Arb<T>): FixtureOverride = FixtureOverride.TypeBased(type = typeOf<T>(), arb = arb)

/** Overrides all fields of type [T] with a constant [value]. */
inline fun <reified T> override(value: T): FixtureOverride =
    FixtureOverride.TypeBased(type = typeOf<T>(), arb = Arb.constant(value))

/** Overrides a specific [property] on [Owner] with the given [Arb]. */
inline fun <reified Owner : Any, reified Prop> override(
    property: KProperty1<Owner, Prop>,
    arb: Arb<Prop>,
): FixtureOverride = FixtureOverride.Named(
    key = NamedOverrideKey(typeOf<Owner>(), property.name),
    arb = arb,
)

/** Overrides a specific [property] on [Owner] with a constant [value]. */
inline fun <reified Owner : Any, reified Prop> override(
    property: KProperty1<Owner, Prop>,
    value: Prop,
): FixtureOverride = FixtureOverride.Named(
    key = NamedOverrideKey(typeOf<Owner>(), property.name),
    arb = Arb.constant(value),
)

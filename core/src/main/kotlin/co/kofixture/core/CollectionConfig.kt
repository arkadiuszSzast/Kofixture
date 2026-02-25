package co.kofixture.core

/** Default size ranges used when auto-deriving collection types. */
data class CollectionConfig(val listSize: IntRange = 0..5, val setSize: IntRange = 0..5, val mapSize: IntRange = 0..5)

/**
 * DSL builder for [CollectionConfig]. Used inside [SampleScope.collections].
 *
 * ```kotlin
 * registry.sample<Project> {
 *     collections {
 *         listSize = 1..3
 *         mapSize  = 0..2
 *     }
 * }
 * ```
 */
class CollectionConfigBuilder {
    var listSize: IntRange = 0..5
    var setSize: IntRange = 0..5
    var mapSize: IntRange = 0..5

    internal fun build() = CollectionConfig(listSize, setSize, mapSize)
}

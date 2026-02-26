package co.kofixture.core

/**
 * Interface that marks a Kotest [Spec] as a Kofixture-enabled test.
 *
 * It allows configuring [fixtureModules] to be used for the current spec.
 * Requires [KofixtureListener] to be registered for proper operation.
 */
interface KofixtureTest {

    /**
     * Optional modules that configure the [FixtureRegistry] for this spec.
     * If empty, [KofixtureContext.defaultModules] are used instead.
     */
    val fixtureModules: List<FixtureModule> get() = emptyList()
}

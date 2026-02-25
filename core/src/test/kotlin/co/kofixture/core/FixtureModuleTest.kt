package co.kofixture.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant

class FixtureModuleTest :
    FreeSpec({

        "FixtureModule" - {
            "can be included in a registry" {
                val module = fixtureModule {
                    register<String>(Arb.constant("from module"))
                }

                val registry = fixtures {
                    includes(module)
                }

                registry.sample<String>() shouldBe "from module"
            }

            "multiple modules can be combined" {
                val stringModule = fixtureModule {
                    register<String>(Arb.constant("combined"))
                }
                val intModule = fixtureModule {
                    register<Int>(Arb.constant(42))
                }

                val registry = fixtures {
                    includes(stringModule, intModule)
                }

                registry.sample<String>() shouldBe "combined"
                registry.sample<Int>() shouldBe 42
            }

            "later modules override earlier ones for the same type" {
                val module1 = fixtureModule {
                    register<String>(Arb.constant("first"))
                }
                val module2 = fixtureModule {
                    register<String>(Arb.constant("second"))
                }

                val registry = fixtures {
                    includes(module1, module2)
                }

                registry.sample<String>() shouldBe "second"
            }

            "direct registrations override modules" {
                val module = fixtureModule {
                    register<String>(Arb.constant("from module"))
                }

                val registry = fixtures {
                    includes(module)
                    register<String>(Arb.constant("direct override"))
                }

                registry.sample<String>() shouldBe "direct override"
            }

            "modules can be nested (a module can include other modules)" {
                val baseModule = fixtureModule {
                    register<Int>(Arb.constant(1))
                }
                val compositeModule = fixtureModule {
                    includes(baseModule)
                    register<String>(Arb.constant("nested"))
                }

                val registry = fixtures {
                    includes(compositeModule)
                }

                registry.sample<Int>() shouldBe 1
                registry.sample<String>() shouldBe "nested"
            }

            "composite module can override included module" {
                val baseModule = fixtureModule {
                    register<String>(Arb.constant("base"))
                }
                val compositeModule = fixtureModule {
                    includes(baseModule)
                    register<String>(Arb.constant("overridden in composite"))
                }

                val registry = fixtures {
                    includes(compositeModule)
                }

                registry.sample<String>() shouldBe "overridden in composite"
            }
        }
    })

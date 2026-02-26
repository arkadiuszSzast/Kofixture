package co.kofixture.core

import co.kofixture.core.utils.Email
import co.kofixture.core.utils.Project
import co.kofixture.core.utils.ProjectName
import co.kofixture.core.utils.Tag
import co.kofixture.core.utils.User
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

val testRegistry =
    fixtures {
        register<String>(Arb.string(3..15))
        register<ProjectName>(Arb.string(3..50).map(::ProjectName))
        register<Email>(Arb.string(4..10).map { Email("$it@example.com") })
        register<Int>(Arb.int(1..100))
        register<Tag>(Arb.string(3..10).map(::Tag))
        register<User> {
            val nameArb = get(User::name)
            val surnameArb = get(User::surname)
            val emailArb = get(User::email)
            arbitrary {
                User(
                    name = nameArb.bind(),
                    surname = surnameArb.bind(),
                    email = emailArb.bind(),
                )
            }
        }
        registerOf(::Project)
    }

fun FixtureRegistry.adminUser(): User = sample<User>(override(User::name, "Admin"))

fun FixtureRegistry.adminUserArb(): Arb<User> = arb<User> {
    override(User::name, "Admin")
}

class FixtureRegistryTest :
    FreeSpec({

        "defaults" - {
            "sample<Project> returns a correctly built object" {
                val project = testRegistry.sample<Project>()

                project should {
                    it.name.value.length shouldBeInRange 3..50
                    it.owner.email.value shouldEndWith "@example.com"
                    it.memberCount shouldBeInRange 1..100
                    it.tags.size shouldBeInRange 0..5
                }
            }
        }

        "type-based override" - {
            "override<T> replaces all fields of that type" {
                // User has two String fields: name and surname — both get the same Arb
                val user = testRegistry.sample<User> {
                    override<String>(Arb.string(1..2))
                }
                user should {
                    it.name.length shouldBeInRange 1..2
                    it.surname.length shouldBeInRange 1..2
                }
            }

            "override propagates deep into the object graph" {
                val project = testRegistry.sample<Project> {
                    override<Email>(Email("fixed@test.io"))
                }
                project.owner.email shouldBe Email("fixed@test.io")
            }
        }

        "named override" - {
            "override(Property, value) replaces only the specified field" {
                val user = testRegistry.sample<User> {
                    override(User::surname, "Doe")
                }

                user.surname shouldBe "Doe"
                user.name.length shouldBeInRange 3..15 // still random
            }

            "override(Property, Arb) replaces only the specified field" {
                val user =
                    testRegistry.sample<User> {
                        override(User::surname, Arb.of("Doe", "Noe"))
                    }

                user.surname.shouldBeIn("Doe", "Noe")
                user.name.length shouldBeInRange 3..15
            }

            "named override distinguishes fields of the same type in the same object" {
                val user = testRegistry.sample<User> {
                    override(User::name, "Jan")
                    override(User::surname, "Kowalski")
                }

                user.name shouldBe "Jan"
                user.surname shouldBe "Kowalski"
            }

            "named override wins over type-based override for that field" {
                val user = testRegistry.sample<User> {
                    override<String>(Arb.of("X"))
                    override(User::surname, "Kowalski")
                }

                user.name shouldBe "X"
                user.surname shouldBe "Kowalski"
            }

            "named override on a nested object replaces the whole value" {
                val fixedOwner = User("Admin", "Root", Email("admin@sys.io"))

                val project = testRegistry.sample<Project> {
                    override(Project::owner, fixedOwner)
                }

                project.owner shouldBe fixedOwner
                project.memberCount shouldBeInRange 1..100
            }
        }

        "combined overrides" - {
            "named + type-based on different fields" {
                val project = testRegistry.sample<Project> {
                    override(Project::memberCount, 42)
                    override<ProjectName>(ProjectName("FixedName"))
                }

                project.memberCount shouldBe 42
                project.name shouldBe ProjectName("FixedName")
                project.owner.email.value shouldEndWith "@example.com"
            }
        }

        "registerOf + auto-derivation" - {
            "List<Tag> is derived automatically without explicit registration" {
                val project = testRegistry.sample<Project>()
                project.tags.size shouldBeInRange 0..5
                project.tags.forEach { it.label.length shouldBeInRange 3..10 }
            }

            "List override via type-based" {
                val fixedTags = listOf(Tag("alpha"), Tag("beta"))
                val project = testRegistry.sample<Project> {
                    override<List<Tag>>(Arb.constant(fixedTags))
                }
                project.tags shouldBe fixedTags
            }

            "List override via named" {
                val fixedTags = listOf(Tag("only"))
                val project = testRegistry.sample<Project> {
                    override(Project::tags, fixedTags)
                }
                project.tags shouldBe fixedTags
            }
        }

        "arb() with named override in checkAll" - {
            checkAll(
                testRegistry.arb<User>(
                    override(User::surname, "Doe"),
                ),
            ) { user ->
                user.surname shouldBe "Doe"
                user.name.length shouldBeInRange 3..15
            }
        }

        "edge cases" - {
            "original registry is not mutated after sampling with overrides" {
                testRegistry.sample<User> { override(User::surname, "X") }
                val user = testRegistry.sample<User>()
                user.surname.length shouldBeInRange 3..15
            }

            "deterministic result with the same seed" {
                val seed = RandomSource.seeded(42L)
                val p1 = testRegistry.sample<Project>(seed)
                val p2 = testRegistry.sample<Project>(RandomSource.seeded(42L))
                p1 shouldBe p2
            }

            "generic types are distinguished in registry" {
                val r = fixtures {
                    register<String>(Arb.string(1..3))
                    register<Int>(Arb.int(100..999))
                    register<List<String>>(Arb.constant(listOf("a", "b")))
                    register<List<Int>>(Arb.constant(listOf(111, 222)))
                }

                val strings = r.sample<List<String>>()
                val ints = r.sample<List<Int>>()

                strings shouldHaveSize 2
                strings.forEach { it.length shouldBeInRange 1..3 }
                ints shouldHaveSize 2
                ints.forEach { it shouldBeInRange 100..999 }
            }
        }

        "extension functions as fixture builders" - {
            "sample<User> admin" {
                val admin = testRegistry.adminUser()
                admin.name shouldBe "Admin"
            }

            "arb<User> admin" {
                val admin = testRegistry.adminUserArb().next()
                admin.name shouldBe "Admin"
            }
        }
    })

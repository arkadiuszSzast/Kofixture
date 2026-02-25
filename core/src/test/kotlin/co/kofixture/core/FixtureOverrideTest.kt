package co.kofixture.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

// ── Domain models ─────────────────────────────────────────────────────────────

data class ProjectName(val value: String)
data class Email(val value: String)
data class User(val name: String, val surname: String, val email: Email)
data class Tag(val label: String)
data class Project(
    val name: ProjectName,
    val owner: User,
    val memberCount: Int,
    val tags: List<Tag>,
)

// ── Shared registry ───────────────────────────────────────────────────────────

val testRegistry = fixtures {
    register<String>(Arb.string(3..15))
    register<ProjectName>(Arb.string(3..50).map(::ProjectName))
    register<Email>(Arb.string(4..10).map { Email("$it@example.com") })
    register<Int>(Arb.int(1..100))
    register<Tag>(Arb.string(3..10).map(::Tag))

    // registerOf auto-wires constructor params from the registry.
    // User(name: String, surname: String, email: Email) — all three types registered above.
    // Project(name: ProjectName, owner: User, memberCount: Int, tags: List<Tag>)
    //   — List<Tag> is auto-derived from Arb<Tag>, no explicit registration needed.
    registerOf(::User)
    registerOf(::Project)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class FixtureRegistryTest : FreeSpec({

    // ── 1. Defaults ───────────────────────────────────────────────────────────

    "defaults" - {
        "sample<Project> returns a correctly built object" {
            val project = testRegistry.sample<Project>()

            project.name.value.length shouldBeInRange 3..50
            project.owner.email.value shouldEndWith "@example.com"
            project.memberCount shouldBeInRange 1..100
            project.tags.size shouldBeInRange 0..5
        }
    }

    // ── 2. Type-based overrides ───────────────────────────────────────────────

    "type-based override" - {
        "override<T> replaces all fields of that type" {
            // User has two String fields: name and surname — both get the same Arb
            val user = testRegistry.sample<User>(
                override<String>(Arb.string(1..2))
            )

            user.name.length shouldBeInRange 1..2
            user.surname.length shouldBeInRange 1..2
        }

        "override propagates deep into the object graph" {
            val project = testRegistry.sample<Project>(
                override<Email>(Email("fixed@test.io"))
            )
            project.owner.email shouldBe Email("fixed@test.io")
        }
    }

    // ── 3. Named overrides ────────────────────────────────────────────────────

    "named override" - {
        "override(Property, value) replaces only the specified field" {
            val user = testRegistry.sample<User>(
                override(User::surname, "Kowalski")
            )

            user.surname shouldBe "Kowalski"
            user.name.length shouldBeInRange 3..15   // still random
        }

        "override(Property, Arb) replaces only the specified field" {
            val user = testRegistry.sample<User>(
                override(User::surname, Arb.of("Nowak", "Wiśniewski"))
            )

            user.surname.shouldBeIn("Nowak", "Wiśniewski")
            user.name.length shouldBeInRange 3..15
        }

        "named override distinguishes fields of the same type in the same object" {
            // Key test: name and surname are both String —
            // named override must target only one of them.
            val user = testRegistry.sample<User>(
                override(User::name, "Jan"),
                override(User::surname, "Kowalski"),
            )

            user.name shouldBe "Jan"
            user.surname shouldBe "Kowalski"
        }

        "named override wins over type-based override for that field" {
            // type-based: all Strings → "X"
            // named: surname → "Kowalski"
            // Expected: name == "X", surname == "Kowalski"
            val user = testRegistry.sample<User>(
                override<String>(Arb.of("X")),
                override(User::surname, "Kowalski"),
            )

            user.name shouldBe "X"            // type-based
            user.surname shouldBe "Kowalski"  // named wins
        }

        "named override on a nested object replaces the whole value" {
            val fixedOwner = User("Admin", "Root", Email("admin@sys.io"))

            val project = testRegistry.sample<Project>(
                override(Project::owner, fixedOwner)
            )

            project.owner shouldBe fixedOwner
            project.memberCount shouldBeInRange 1..100  // still random
        }
    }

    // ── 4. Combined overrides ─────────────────────────────────────────────────

    "combined overrides" - {
        "named + type-based on different fields" {
            val project = testRegistry.sample<Project>(
                override(Project::memberCount, 42),
                override<ProjectName>(ProjectName("FixedName")),
            )

            project.memberCount shouldBe 42
            project.name shouldBe ProjectName("FixedName")
            project.owner.email.value shouldEndWith "@example.com"  // still random
        }
    }

    // ── 5. registerOf + auto-derivation of collections ────────────────────────

    "registerOf + auto-derivation" - {
        "List<Tag> is derived automatically without explicit registration" {
            val project = testRegistry.sample<Project>()
            project.tags.size shouldBeInRange 0..5
            project.tags.forEach { it.label.length shouldBeInRange 3..10 }
        }

        "List override via type-based" {
            val fixedTags = listOf(Tag("alpha"), Tag("beta"))
            val project = testRegistry.sample<Project>(
                override<List<Tag>>(Arb.constant(fixedTags))
            )
            project.tags shouldBe fixedTags
        }

        "List override via named" {
            val fixedTags = listOf(Tag("only"))
            val project = testRegistry.sample<Project>(
                override(Project::tags, fixedTags)
            )
            project.tags shouldBe fixedTags
        }
    }

    // ── 6. arb() with overrides for property-based tests ─────────────────────

    "arb() with named override in checkAll" {
        checkAll(
            testRegistry.arb<User>(
                override(User::surname, "Testowy")
            )
        ) { user ->
            user.surname shouldBe "Testowy"
            user.name.length shouldBeInRange 3..15
        }
    }

    // ── 7. Edge cases ─────────────────────────────────────────────────────────

    "edge cases" - {
        "original registry is not mutated after sampling with overrides" {
            testRegistry.sample<User>(override(User::surname, "X"))
            val user = testRegistry.sample<User>()
            user.surname.length shouldBeInRange 3..15
        }

        "deterministic result with the same seed" {
            val seed = io.kotest.property.RandomSource.seeded(42L)
            val p1 = testRegistry.sample<Project>(seed)
            val p2 = testRegistry.sample<Project>(io.kotest.property.RandomSource.seeded(42L))
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
            val ints    = r.sample<List<Int>>()

            strings shouldHaveSize 2
            strings.forEach { it.length shouldBeInRange 1..3 }
            ints shouldHaveSize 2
            ints.forEach { it shouldBeInRange 100..999 }
        }
    }
})

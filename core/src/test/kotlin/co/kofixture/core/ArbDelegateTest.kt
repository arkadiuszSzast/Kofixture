package co.kofixture.core

import co.kofixture.core.utils.Project
import co.kofixture.core.utils.User
import co.kofixture.core.utils.coreModule
import co.kofixture.core.utils.productModule
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.property.Arb
import io.kotest.property.checkAll

class ArbDelegateTest :
    FreeSpec(),
    KofixtureTest {

    override val fixtureModules = listOf(coreModule, productModule)

    val userArb: Arb<User> by arb()
    val adminArb: Arb<User> by arb { override(User::name, "Admin") }
    val fixedCountArb: Arb<Project> by arb(override(Project::memberCount, 99))

    init {
        extension(KofixtureListener)

        "arb() — works in checkAll" {
            checkAll(userArb) { user ->
                user.email.value shouldEndWith "@example.com"
                user.name.length shouldBeInRange 3..15
            }
        }

        "arb() with block override — works in checkAll" {
            checkAll(adminArb) { user ->
                user.name shouldBe "Admin"
            }
        }

        "arb() with vararg override — works in checkAll" {
            checkAll(fixedCountArb) { project ->
                project.memberCount shouldBe 99
            }
        }

        "arb() is not cached — each access returns a new Arb instance" {
            userArb shouldNotBe userArb
        }

        "arb() local val inside init" {
            val projectArb: Arb<Project> by arb { override(Project::memberCount, 7) }
            checkAll(projectArb) { project ->
                project.memberCount shouldBe 7
            }
        }
    }
}

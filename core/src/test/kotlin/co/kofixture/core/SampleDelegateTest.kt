package co.kofixture.core
import co.kofixture.core.utils.Project
import co.kofixture.core.utils.User
import co.kofixture.core.utils.coreModule
import co.kofixture.core.utils.productModule
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class SampleDelegateTest :
    FreeSpec(),
    KofixtureTest {

    override val fixtureModules = listOf(coreModule, productModule)

    val user: User by sample()
    val anotherUser: User by sample()
    val admin: User by sample { override(User::name, "Admin") }
    val fixedCount: Project by sample(override(Project::memberCount, 42))

    init {
        extension(KofixtureListener)

        "sample() — basic" {
            user.email.value shouldEndWith "@example.com"
            user.name.length shouldBeInRange 3..15
        }

        "sample() — value is cached, same instance on every access" {
            val first = user
            val second = user
            first shouldBe second
        }

        "sample() — each property has its own independent cache" {
            user shouldBe user
            anotherUser shouldBe anotherUser
        }

        "sample() with block override" {
            admin.name shouldBe "Admin"
            admin.email.value shouldEndWith "@example.com"
        }

        "sample() with block override is also cached" {
            admin shouldBe admin
        }

        "sample() with vararg override" {
            fixedCount.memberCount shouldBe 42
        }

        "sample() with collections override — local val inside init" {
            val project: Project by sample {
                collections { listSize = 2..2 }
            }
            project.tags.size shouldBe 2
        }
    }
}

package co.kofixture.core

import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec

object KofixtureListener : BeforeSpecListener, AfterSpecListener {

    override suspend fun beforeSpec(spec: Spec) {
        if (spec is KofixtureTest) KofixtureContext.buildFor(spec)
    }

    override suspend fun afterSpec(spec: Spec) {
        if (spec is KofixtureTest) KofixtureContext.releaseFor(spec)
    }
}

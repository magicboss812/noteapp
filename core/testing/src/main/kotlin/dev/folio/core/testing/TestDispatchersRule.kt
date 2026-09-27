package dev.folio.core.testing

import dev.folio.core.common.FolioDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Sets Dispatchers.Main to [testDispatcher] for the test and exposes [dispatchers] where every
 * named dispatcher is that same test dispatcher. Use with `runTest(rule.testDispatcher)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatchersRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    val dispatchers: FolioDispatchers =
        FolioDispatchers(
            main = testDispatcher,
            io = testDispatcher,
            render = testDispatcher,
            pdf = testDispatcher,
            text = testDispatcher,
        )

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

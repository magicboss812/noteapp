package dev.folio.core.testing

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test

class TestDispatchersRuleTest {
    @get:Rule
    val rule = TestDispatchersRule()

    @Test
    fun main_duringTest_runsOnTestDispatcherWithVirtualTime() =
        runTest(rule.testDispatcher) {
            var done = false
            launch(Dispatchers.Main) {
                delay(10_000)
                done = true
            }
            testScheduler.advanceUntilIdle()
            assertThat(done).isTrue()
            assertThat(testScheduler.currentTime).isEqualTo(10_000L)
        }

    @Test
    fun namedDispatchers_flowOnIo_emitsInOrder() =
        runTest(rule.testDispatcher) {
            flow {
                emit(1)
                emit(withContext(rule.dispatchers.render) { 2 })
            }.flowOn(rule.dispatchers.io).test {
                assertThat(awaitItem()).isEqualTo(1)
                assertThat(awaitItem()).isEqualTo(2)
                awaitComplete()
            }
        }
}

package dev.folio.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class OutcomeTest {
    @Test
    fun outcomeOf_blockReturns_success() {
        assertThat(outcomeOf("x") { 42 }).isEqualTo(Outcome.Success(42))
    }

    @Test
    fun outcomeOf_blockThrows_failureKeepsCause() {
        val boom = IOException("disk")
        val result = outcomeOf("read file") { throw boom }
        assertThat(result).isEqualTo(Outcome.Failure("read file", boom))
    }

    @Test
    fun outcomeOf_cancellation_isRethrown() {
        assertThrows(CancellationException::class.java) {
            outcomeOf("x") { throw CancellationException("stop") }
        }
    }

    @Test
    fun mapAndFlatMap_onFailure_keepFailure() {
        val failure: Outcome<Int> = Outcome.Failure("nope")
        assertThat(failure.map { it + 1 }).isEqualTo(failure)
        assertThat(failure.flatMap { Outcome.Success(it + 1) }).isEqualTo(failure)
        assertThat(failure.getOrNull()).isNull()
    }

    @Test
    fun mapAndFlatMap_onSuccess_transform() {
        val ok: Outcome<Int> = Outcome.Success(1)
        assertThat(ok.map { it + 1 }.getOrNull()).isEqualTo(2)
        assertThat(ok.flatMap { Outcome.Failure("later") }).isEqualTo(Outcome.Failure("later"))
    }
}

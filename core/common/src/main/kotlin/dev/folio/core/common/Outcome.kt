package dev.folio.core.common

import kotlin.coroutines.cancellation.CancellationException

/** Result of an operation that can fail in an expected way (IO, parsing, permissions). */
sealed interface Outcome<out T> {
    /** The operation produced [value]. */
    data class Success<out T>(
        val value: T,
    ) : Outcome<T>

    /** The operation failed; [message] is for logs, [cause] keeps the original exception if any. */
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : Outcome<Nothing>
}

/** Value on success, null on failure. */
fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

/** Transforms the success value, keeps failures. */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Success -> Outcome.Success(transform(value))
        is Outcome.Failure -> this
    }

/** Chains another fallible step. */
inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> =
    when (this) {
        is Outcome.Success -> transform(value)
        is Outcome.Failure -> this
    }

/** Runs [block] and turns exceptions into [Outcome.Failure]. Cancellation is rethrown, never swallowed. */
@Suppress("TooGenericExceptionCaught") // Boundary helper: every non-cancellation exception becomes a Failure.
inline fun <T> outcomeOf(
    failureMessage: String,
    block: () -> T,
): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(failureMessage, e)
    }

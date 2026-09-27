package dev.folio.core.common

/** Log severity, ordered from most to least verbose. */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** Destination for [FolioLog]; :app installs an android.util.Log sink. */
fun interface LogSink {
    /** Writes one log line. */
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    )
}

/** Logging facade (Kotlin rules). Not for hot paths. Debug/verbose calls are stripped by R8 in release. */
object FolioLog {
    @Volatile
    private var sink: LogSink = LogSink { _, _, _, _ -> }

    /** Replaces the sink. Called once from Application.onCreate (and by tests). */
    fun install(newSink: LogSink) {
        sink = newSink
    }

    /** Verbose log. */
    fun v(
        tag: String,
        message: String,
    ) = sink.log(LogLevel.VERBOSE, tag, message, null)

    /** Debug log. */
    fun d(
        tag: String,
        message: String,
    ) = sink.log(LogLevel.DEBUG, tag, message, null)

    /** Info log. */
    fun i(
        tag: String,
        message: String,
    ) = sink.log(LogLevel.INFO, tag, message, null)

    /** Warning log. */
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) = sink.log(LogLevel.WARN, tag, message, throwable)

    /** Error log. */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) = sink.log(LogLevel.ERROR, tag, message, throwable)
}

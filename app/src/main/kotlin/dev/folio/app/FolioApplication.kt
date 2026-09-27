package dev.folio.app

import android.app.Application
import android.os.Trace
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.folio.core.common.FolioLog
import dev.folio.core.common.LogLevel
import dev.folio.core.common.LogSink
import dev.folio.core.common.PerfMonitor
import dev.folio.core.common.TraceSink

@HiltAndroidApp
class FolioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 12-performance.md: nothing else runs here besides Hilt and PerfMonitor.
        FolioLog.install(AndroidLogSink)
        PerfMonitor.traceSink = PlatformTraceSink
        PerfMonitor.enabled = BuildConfig.DEBUG
    }
}

private object AndroidLogSink : LogSink {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val priority =
            when (level) {
                LogLevel.VERBOSE -> Log.VERBOSE
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
        val text = if (throwable == null) message else message + "\n" + Log.getStackTraceString(throwable)
        Log.println(priority, tag, text)
    }
}

private object PlatformTraceSink : TraceSink {
    override fun begin(section: String) = Trace.beginSection(section)

    override fun end() = Trace.endSection()
}

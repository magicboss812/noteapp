package dev.folio.app

import android.app.Application
import android.os.Trace
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.folio.core.common.FolioLog
import dev.folio.core.common.LogLevel
import dev.folio.core.common.LogSink
import dev.folio.core.common.PerfMonitor
import dev.folio.core.common.TraceSink
import dev.folio.core.storage.session.DocumentSessions
import javax.inject.Inject

@HiltAndroidApp
class FolioApplication : Application() {
    @Inject
    lateinit var sessions: Lazy<DocumentSessions>

    override fun onCreate() {
        super.onCreate()
        // 12-performance.md: nothing else runs here besides Hilt, PerfMonitor and one lifecycle observer.
        FolioLog.install(AndroidLogSink)
        PerfMonitor.traceSink = PlatformTraceSink
        PerfMonitor.enabled = BuildConfig.DEBUG
        // App to background: pack every open document (04-file-format.md#write-protocol). Lazy: no storage work at start.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) = sessions.get().onAppStop()
            },
        )
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

package dev.folio.app.debug

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.folio.app.DebugHooks
import dev.folio.app.NoOpDebugHooks

@Module
@InstallIn(SingletonComponent::class)
internal object DebugHooksModule {
    @Provides
    fun debugHooks(): DebugHooks = NoOpDebugHooks
}

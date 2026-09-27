package dev.folio.app.debug

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.folio.app.DebugHooks

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DebugHooksModule {
    @Binds
    abstract fun debugHooks(impl: AppDebugHooks): DebugHooks
}

package com.openmate.core.data

import com.openmate.core.data.repository.*
import com.openmate.core.data.sse.*
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.data.sync.SyncSseLoggerImpl
import com.openmate.core.data.sync.SyncSseStarter
import com.openmate.core.domain.repository.*
import com.openmate.core.network.SyncSseLogger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindServerProfileRepository(impl: ServerProfileRepositoryImpl): ServerProfileRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindPermissionRepository(impl: PermissionRepositoryImpl): PermissionRepository

    @Binds
    @Singleton
    abstract fun bindQuestionRepository(impl: QuestionRepositoryImpl): QuestionRepository

    @Binds
    @Singleton
    abstract fun bindSseEventRepository(impl: SseEventRepositoryImpl): SseEventRepository

    @Binds
    @Singleton
    abstract fun bindTodoRepository(impl: TodoRepositoryImpl): TodoRepository

    @Binds
    @Singleton
    abstract fun bindSessionMessageRepository(impl: SessionMessageRepositoryImpl): SessionMessageRepository

    @Binds
    @Singleton
    abstract fun bindSyncSseLogger(impl: SyncSseLoggerImpl): SyncSseLogger

    @Binds
    @Singleton
    abstract fun bindSyncSseStarter(impl: SyncSseHandler): SyncSseStarter

}

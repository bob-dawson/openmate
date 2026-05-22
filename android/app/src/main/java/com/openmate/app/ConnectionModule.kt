package com.openmate.app

import com.openmate.app.connection.AppForegroundMonitor
import com.openmate.app.connection.DefaultAppForegroundMonitor
import com.openmate.app.connection.DefaultNetworkChangeMonitor
import com.openmate.app.connection.NetworkChangeMonitor
import com.openmate.app.connection.RouteEvidenceAggregator
import com.openmate.core.domain.repository.ConnectionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds
    @Singleton
    abstract fun bindConnectionRepository(impl: ConnectionManager): ConnectionRepository

    companion object {
        @Provides
        @Singleton
        fun provideRouteEvidenceAggregator(): RouteEvidenceAggregator {
            return RouteEvidenceAggregator(clock = { System.currentTimeMillis() })
        }

        @Provides
        @Singleton
        fun provideAppForegroundMonitor(impl: DefaultAppForegroundMonitor): AppForegroundMonitor {
            return impl
        }

        @Provides
        @Singleton
        fun provideNetworkChangeMonitor(impl: DefaultNetworkChangeMonitor): NetworkChangeMonitor {
            return impl
        }
    }
}

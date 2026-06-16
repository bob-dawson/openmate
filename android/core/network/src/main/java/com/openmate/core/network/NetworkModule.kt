package com.openmate.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGatewayInterceptor(
        activeProfileProvider: ActiveProfileProvider,
    ): GatewayInterceptor {
        return GatewayInterceptor(activeProfileProvider)
    }

    @Provides
    @Singleton
    fun provideActiveProfileProvider(): ActiveProfileProvider {
        return ActiveProfileProviderHolder
    }

    @Provides
    @Singleton
    @Named("sse")
    fun provideSseOkHttpClient(
        tokenStore: TokenStore,
        gatewayInterceptor: GatewayInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(BearerTokenInterceptor(tokenStore))
            .addInterceptor(gatewayInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("api")
    fun provideApiOkHttpClient(
        tokenStore: TokenStore,
        gatewayInterceptor: GatewayInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(BearerTokenInterceptor(tokenStore))
            .addInterceptor(gatewayInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadOkHttpClient(
        tokenStore: TokenStore,
        gatewayInterceptor: GatewayInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(BearerTokenInterceptor(tokenStore))
            .addInterceptor(gatewayInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideOpencodeApiClient(
        @Named("api") client: OkHttpClient,
        @Named("download") downloadClient: OkHttpClient,
        activeProfileProvider: ActiveProfileProvider,
        gatewayInterceptor: GatewayInterceptor,
        routeEvidenceReporter: RouteEvidenceReporter,
    ): OpencodeApiClient {
        return OpencodeApiClient(client, downloadClient, activeProfileProvider = activeProfileProvider, gatewayInterceptor = gatewayInterceptor, routeEvidenceReporter = routeEvidenceReporter)
    }

    @Provides
    @Singleton
    @Named("version")
    fun provideVersionOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("release")
    fun provideReleaseOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideVersionClient(
        @Named("version") versionClient: OkHttpClient,
        @Named("release") releaseClient: OkHttpClient,
    ): VersionClient {
        return VersionClient(versionClient, releaseClient)
    }

    @Provides
    @Singleton
    fun provideSyncApiClient(@Named("api") client: OkHttpClient, opencodeApiClient: OpencodeApiClient): SyncApiClient {
        return SyncApiClient(client, opencodeApiClient)
    }

    @Provides
    @Singleton
    fun provideSyncSseClient(
        @Named("sse") client: OkHttpClient,
        tokenStore: TokenStore,
        logger: SyncSseLogger,
    ): SyncSseClient {
        return SyncSseClient(client, tokenStore, logger)
    }

    @Provides
    @Singleton
    fun provideSyncSseConnection(syncSseClient: SyncSseClient): SyncSseConnection {
        return syncSseClient
    }
}

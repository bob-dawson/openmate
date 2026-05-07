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
    @Named("sse")
    fun provideSseOkHttpClient(
        tokenStore: TokenStore,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(BearerTokenInterceptor(tokenStore))
            .build()
    }

    @Provides
    @Singleton
    @Named("api")
    fun provideApiOkHttpClient(
        tokenStore: TokenStore,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(BearerTokenInterceptor(tokenStore))
            .build()
    }

    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadOkHttpClient(
        tokenStore: TokenStore,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(BearerTokenInterceptor(tokenStore))
            .build()
    }

    @Provides
    @Singleton
    fun provideSseClient(@Named("sse") client: OkHttpClient): SseClient {
        return SseClient(client)
    }

    @Provides
    @Singleton
    fun provideOpencodeApiClient(
        @Named("api") client: OkHttpClient,
        @Named("download") downloadClient: OkHttpClient,
    ): OpencodeApiClient {
        return OpencodeApiClient(client, downloadClient)
    }

    @Provides
    @Singleton
    fun provideSyncApiClient(@Named("api") client: OkHttpClient): SyncApiClient {
        return SyncApiClient(client)
    }
}

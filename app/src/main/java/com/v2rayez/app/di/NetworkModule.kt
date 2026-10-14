package com.v2rayez.app.di

import com.v2rayez.app.BuildConfig
import com.v2rayez.app.data.api.ApiService
import com.v2rayez.app.data.api.AuthInterceptor
import com.v2rayez.app.data.api.DeviceIdProvider
import com.v2rayez.app.data.api.SessionManager
import com.v2rayez.app.data.api.HmacInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideApiService(deviceId: DeviceIdProvider, sessions: SessionManager): ApiService {
        val baseUrl = BuildConfig.API_BASE_URL.trim().let { if (it.endsWith("/")) it else "$it/" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "API_BASE_URL не задан или не https (см. local.properties): '$baseUrl'"
        }

        // Дефолтный TLS и системные CA. Логирование тел запросов намеренно НЕ подключено.
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HmacInterceptor(deviceId = deviceId::get, secret = BuildConfig.APP_HMAC_SECRET))
            .addInterceptor(AuthInterceptor(sessions::token))
            .build()

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}

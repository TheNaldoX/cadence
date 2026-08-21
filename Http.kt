package fr.velo.cadence.net

import fr.velo.cadence.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client HTTP partage. L'en-tete User-Agent est obligatoire : la politique
 * d'usage des services OpenStreetMap refuse le trafic anonyme, et BRouter
 * comme Nominatim s'appuient dessus pour identifier les clients abusifs.
 */
object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(userAgentInterceptor)
            .build()
    }

    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", BuildConfig.HTTP_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        chain.proceed(request)
    }
}

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

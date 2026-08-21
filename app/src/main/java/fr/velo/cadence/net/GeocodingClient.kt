package fr.velo.cadence.net

import fr.velo.cadence.BuildConfig
import fr.velo.cadence.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

data class Place(
    val label: String,
    val point: GeoPoint,
)

/**
 * Recherche d'un lieu de depart via Nominatim.
 *
 * La politique d'usage du service public impose un User-Agent identifiant et
 * au plus une requete par seconde : la recherche est donc declenchee par
 * l'utilisateur, jamais en continu pendant la frappe.
 */
class GeocodingClient(
    private val baseUrl: String = BuildConfig.NOMINATIM_BASE_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(query: String, near: GeoPoint? = null, limit: Int = 6): List<Place> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val builder = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("accept-language", "fr")
            near?.let {
                // Une boite de 1 degre autour de la position privilegie les
                // resultats proches sans les imposer.
                builder.addQueryParameter(
                    "viewbox",
                    "%.4f,%.4f,%.4f,%.4f".format(
                        java.util.Locale.US,
                        it.lon - 1.0, it.lat + 1.0, it.lon + 1.0, it.lat - 1.0,
                    ),
                )
            }
            val request = Request.Builder().url(builder.build()).get().build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) return@withContext emptyList()
                runCatching {
                    json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val lat = obj["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        val lon = obj["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        val name = obj["display_name"]?.jsonPrimitive?.contentOrNull
                        if (lat == null || lon == null || name == null) null
                        else Place(name, GeoPoint(lat, lon))
                    }
                }.getOrDefault(emptyList())
            }
        }

    /** Nom lisible d'un point, utilise pour intituler un parcours. */
    suspend fun reverse(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", "%.6f".format(java.util.Locale.US, point.lat))
            .addQueryParameter("lon", "%.6f".format(java.util.Locale.US, point.lon))
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "14")
            .addQueryParameter("accept-language", "fr")
            .build()
        val request = Request.Builder().url(url).get().build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) return@use null
                val obj = json.parseToJsonElement(body).jsonObject
                val address = obj["address"]?.jsonObject
                address?.get("village")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("town")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("city")?.jsonPrimitive?.contentOrNull
                    ?: address?.get("municipality")?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }
}

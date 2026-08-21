package fr.velo.cadence.model

import kotlin.math.roundToInt

/**
 * Encodage "Encoded Polyline Algorithm" de Google, etendu a trois dimensions
 * comme le fait OpenRouteService : latitude et longitude au 1e-5 degre,
 * altitude au centimetre.
 *
 * Une trace de 5 000 points occupe environ 60 ko en JSON brut et 25 ko ici,
 * ce qui evite de gonfler la base pour rien.
 */
object PolylineCodec {

    fun encode(points: List<GeoPoint>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLon = 0
        var prevEle = 0
        for (p in points) {
            val lat = (p.lat * 1e5).roundToInt()
            val lon = (p.lon * 1e5).roundToInt()
            val ele = ((p.ele ?: 0.0) * 1e2).roundToInt()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            encodeValue(ele - prevEle, sb)
            prevLat = lat
            prevLon = lon
            prevEle = ele
        }
        return sb.toString()
    }

    fun decode(encoded: String): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        val result = ArrayList<GeoPoint>(encoded.length / 8)
        var index = 0
        var lat = 0
        var lon = 0
        var ele = 0
        while (index < encoded.length) {
            val dLat = decodeValue(encoded, index) ?: break
            index = dLat.second
            lat += dLat.first
            val dLon = decodeValue(encoded, index) ?: break
            index = dLon.second
            lon += dLon.first
            val dEle = decodeValue(encoded, index) ?: break
            index = dEle.second
            ele += dEle.first
            result.add(GeoPoint(lat / 1e5, lon / 1e5, ele / 1e2))
        }
        return result
    }

    private fun encodeValue(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }

    private fun decodeValue(encoded: String, start: Int): Pair<Int, Int>? {
        var index = start
        var shift = 0
        var result = 0
        var b: Int
        do {
            if (index >= encoded.length) return null
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val value = if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
        return value to index
    }
}

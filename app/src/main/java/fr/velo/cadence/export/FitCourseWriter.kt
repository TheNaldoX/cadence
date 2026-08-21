package fr.velo.cadence.export

import fr.velo.cadence.model.Geo
import fr.velo.cadence.model.PlannedRoute
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Encodeur de fichiers FIT au profil "Course".
 *
 * Le GPX ne transporte pas de consignes de virage exploitables par un
 * compteur ; le FIT, si, grace aux messages `course_point`. C'est le format a
 * privilegier quand on veut que le compteur annonce les changements de
 * direction plutot que de se contenter d'afficher une trace.
 *
 * Le format est binaire et non documente cote iGPSPORT, mais il suit la
 * specification Garmin que les compteurs iGPSPORT acceptent (les modeles
 * iGS60/iGS618/iGS620 le documentent explicitement).
 */
object FitCourseWriter {

    /** Decalage entre l'epoque FIT (31/12/1989) et l'epoque Unix, en secondes. */
    private const val FIT_EPOCH_OFFSET = 631_065_600L

    private const val SEMICIRCLES_PER_DEGREE = 11_930_464.7111111 // 2^31 / 180

    // Types de base FIT
    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val SINT32 = 0x85
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C
    private const val STRING = 0x07

    /** Nombre maximal de consignes : au-dela, les compteurs en ignorent. */
    private const val MAX_COURSE_POINTS = 190

    fun write(route: PlannedRoute, maxTrackPoints: Int = 4000): ByteArray {
        val points = Geo.capPoints(route.points, maxTrackPoints)
        require(points.size >= 2) { "Parcours trop court pour être exporté" }

        val cumulative = Geo.cumulativeDistances(points)
        val startTime = (System.currentTimeMillis() / 1000L) - FIT_EPOCH_OFFSET
        // Les compteurs attendent des horodatages croissants ; on repartit une
        // duree plausible sur le parcours plutot que d'ecrire la meme seconde
        // partout, sinon le decompte "prochain virage dans X" se derregle.
        val totalSeconds = (route.estimatedDurationMs / 1000L).coerceAtLeast(points.size.toLong())
        val totalDistance = cumulative.last()

        fun timeAt(index: Int): Long {
            val ratio = if (totalDistance > 0) cumulative[index] / totalDistance else 0.0
            return startTime + (ratio * totalSeconds).roundToLong()
        }

        val data = ByteArrayOutputStream(points.size * 24 + 2048)

        // --- file_id -------------------------------------------------------
        data.write(
            definition(
                localType = 0,
                globalMessage = 0,
                fields = listOf(
                    Triple(0, 1, ENUM),      // type = course
                    Triple(1, 2, UINT16),    // manufacturer
                    Triple(2, 2, UINT16),    // product
                    Triple(3, 4, UINT32Z),   // serial number
                    Triple(4, 4, UINT32),    // time created
                ),
            ),
        )
        data.write(byteArrayOf(0x00))
        data.writeU8(6)                      // file type 6 = course
        data.writeU16(255)                   // manufacturer "development"
        data.writeU16(0)
        data.writeU32(0)
        data.writeU32(startTime)

        // --- course --------------------------------------------------------
        val nameBytes = fitString(route.name, 32)
        data.write(
            definition(
                localType = 1,
                globalMessage = 31,
                fields = listOf(
                    Triple(4, 1, ENUM),                 // sport
                    Triple(5, nameBytes.size, STRING),  // name
                ),
            ),
        )
        data.write(byteArrayOf(0x01))
        data.writeU8(2)                      // sport 2 = cycling
        data.write(nameBytes)

        // --- lap -----------------------------------------------------------
        data.write(
            definition(
                localType = 2,
                globalMessage = 19,
                fields = listOf(
                    Triple(253, 4, UINT32),  // timestamp
                    Triple(2, 4, UINT32),    // start_time
                    Triple(3, 4, SINT32),    // start_position_lat
                    Triple(4, 4, SINT32),    // start_position_long
                    Triple(5, 4, SINT32),    // end_position_lat
                    Triple(6, 4, SINT32),    // end_position_long
                    Triple(7, 4, UINT32),    // total_elapsed_time
                    Triple(8, 4, UINT32),    // total_timer_time
                    Triple(9, 4, UINT32),    // total_distance
                ),
            ),
        )
        data.write(byteArrayOf(0x02))
        data.writeU32(startTime + totalSeconds)
        data.writeU32(startTime)
        data.writeS32(semicircles(points.first().lat))
        data.writeS32(semicircles(points.first().lon))
        data.writeS32(semicircles(points.last().lat))
        data.writeS32(semicircles(points.last().lon))
        data.writeU32(totalSeconds * 1000)
        data.writeU32(totalSeconds * 1000)
        data.writeU32((totalDistance * 100).roundToLong())

        // --- event: timer start --------------------------------------------
        data.write(
            definition(
                localType = 3,
                globalMessage = 21,
                fields = listOf(
                    Triple(253, 4, UINT32),
                    Triple(0, 1, ENUM),
                    Triple(1, 1, ENUM),
                ),
            ),
        )
        data.write(byteArrayOf(0x03))
        data.writeU32(startTime)
        data.writeU8(0)  // event = timer
        data.writeU8(0)  // event_type = start

        // --- records -------------------------------------------------------
        data.write(
            definition(
                localType = 4,
                globalMessage = 20,
                fields = listOf(
                    Triple(253, 4, UINT32),  // timestamp
                    Triple(0, 4, SINT32),    // position_lat
                    Triple(1, 4, SINT32),    // position_long
                    Triple(2, 2, UINT16),    // altitude
                    Triple(5, 4, UINT32),    // distance
                ),
            ),
        )
        for (i in points.indices) {
            val p = points[i]
            data.write(byteArrayOf(0x04))
            data.writeU32(timeAt(i))
            data.writeS32(semicircles(p.lat))
            data.writeS32(semicircles(p.lon))
            data.writeU16(altitudeRaw(p.ele))
            data.writeU32((cumulative[i] * 100).roundToLong())
        }

        // --- course points --------------------------------------------------
        val instructions = route.instructions
            .filter { it.pointIndex in points.indices || route.points.size != points.size }
            .let { list ->
                // Les indices se referent a la trace d'origine : on les
                // reprojette sur la trace simplifiee par distance parcourue.
                list.map { instruction ->
                    val targetDistance = instruction.distanceFromStartM
                    var index = cumulative.indexOfFirst { it >= targetDistance }
                    if (index < 0) index = points.lastIndex
                    instruction to index
                }
            }
            .distinctBy { it.second }
            .take(MAX_COURSE_POINTS)

        if (instructions.isNotEmpty()) {
            data.write(
                definition(
                    localType = 5,
                    globalMessage = 32,
                    fields = listOf(
                        Triple(1, 4, UINT32),    // timestamp
                        Triple(2, 4, SINT32),    // position_lat
                        Triple(3, 4, SINT32),    // position_long
                        Triple(4, 4, UINT32),    // distance
                        Triple(5, 1, ENUM),      // type
                        Triple(6, 16, STRING),   // name
                    ),
                ),
            )
            for ((instruction, index) in instructions) {
                val p = points[index]
                data.write(byteArrayOf(0x05))
                data.writeU32(timeAt(index))
                data.writeS32(semicircles(p.lat))
                data.writeS32(semicircles(p.lon))
                data.writeU32((cumulative[index] * 100).roundToLong())
                data.writeU8(instruction.type.fitCoursePointType)
                data.write(fitString(instruction.type.shortLabel, 16))
            }
        }

        // --- event: timer stop ---------------------------------------------
        data.write(byteArrayOf(0x03))
        data.writeU32(startTime + totalSeconds)
        data.writeU8(0)  // event = timer
        data.writeU8(9)  // event_type = stop_all

        val payload = data.toByteArray()
        return assemble(payload)
    }

    // ------------------------------------------------------------- primitives

    private fun definition(
        localType: Int,
        globalMessage: Int,
        fields: List<Triple<Int, Int, Int>>,
    ): ByteArray {
        val out = ByteArrayOutputStream(6 + fields.size * 3)
        out.writeU8(0x40 or (localType and 0x0F)) // en-tete de definition
        out.writeU8(0)                            // reserve
        out.writeU8(0)                            // architecture : petit-boutiste
        out.writeU16(globalMessage)
        out.writeU8(fields.size)
        for ((number, size, baseType) in fields) {
            out.writeU8(number)
            out.writeU8(size)
            out.writeU8(baseType)
        }
        return out.toByteArray()
    }

    private fun assemble(payload: ByteArray): ByteArray {
        val header = ByteArrayOutputStream(14)
        header.writeU8(14)          // taille d'en-tete
        header.writeU8(0x20)        // version de protocole 2.0
        header.writeU16(2140)       // version de profil 21.40
        header.writeU32(payload.size.toLong())
        header.write(".FIT".toByteArray(Charsets.US_ASCII))
        val headerBytes = header.toByteArray()
        val headerCrc = crc16(headerBytes, headerBytes.size)

        val out = ByteArrayOutputStream(headerBytes.size + 2 + payload.size + 2)
        out.write(headerBytes)
        out.writeU16(headerCrc)
        out.write(payload)

        val full = out.toByteArray()
        val fileCrc = crc16(full, full.size)
        out.writeU16(fileCrc)
        return out.toByteArray()
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** CRC-16 defini par la specification FIT. */
    fun crc16(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            val byte = data[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc and 0xFFFF
    }

    private fun semicircles(degrees: Double): Long =
        (degrees * SEMICIRCLES_PER_DEGREE).roundToLong()

    /** Altitude FIT : (metres + 500) * 5, sur 16 bits. 0xFFFF signifie inconnu. */
    private fun altitudeRaw(ele: Double?): Int {
        if (ele == null) return 0xFFFF
        val raw = ((ele + 500.0) * 5.0).roundToInt()
        return raw.coerceIn(0, 0xFFFE)
    }

    /** Chaine FIT : ASCII, terminee par un octet nul, de taille fixe. */
    private fun fitString(value: String, size: Int): ByteArray {
        val ascii = value
            .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('à', 'a')
            .replace('ù', 'u').replace('ç', 'c').replace('ô', 'o').replace('î', 'i')
            .filter { it.code in 32..126 }
            .toByteArray(Charsets.US_ASCII)
        val out = ByteArray(size)
        val copyLength = minOf(ascii.size, size - 1)
        System.arraycopy(ascii, 0, out, 0, copyLength)
        return out
    }

    private fun ByteArrayOutputStream.writeU8(value: Int) {
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt())
        write(((value shr 24) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeS32(value: Long) = writeU32(value)
}

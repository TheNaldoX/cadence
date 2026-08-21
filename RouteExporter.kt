package fr.velo.cadence.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import fr.velo.cadence.model.PlannedRoute
import fr.velo.cadence.model.Ride
import fr.velo.cadence.model.RidePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    GPX("GPX", "gpx", "application/gpx+xml"),
    TCX("TCX", "tcx", "application/vnd.garmin.tcx+xml"),
    FIT("FIT", "fit", "application/vnd.ant.fit"),
}

data class ExportResult(
    val displayName: String,
    val downloadUri: Uri?,
    val shareUri: Uri,
    val format: ExportFormat,
)

/**
 * Production et diffusion des fichiers de parcours.
 *
 * Deux chemins sont proposes, dans cet ordre de fiabilite :
 *
 * 1. Le fichier est ecrit dans le dossier Telechargements du telephone. C'est
 *    la seule voie qui fonctionne a coup sur avec l'application iGPSPORT
 *    Ride, dont la fonction d'import va chercher le fichier elle-meme.
 * 2. Un partage direct vers l'application iGPSPORT est propose en raccourci.
 *    Il echoue sur certaines versions ("Non-GPX file is not supported"), d'ou
 *    le repli systematique sur le chemin precedent.
 *
 * Le nom de fichier est volontairement austere : les compteurs iGPSPORT
 * documentent une limite de 28 caracteres, lettres, chiffres et tirets
 * uniquement.
 */
object RouteExporter {

    /** Nom de paquet de l'application iGPSPORT Ride actuelle. */
    const val IGPSPORT_PACKAGE = "com.qiwu.worldwide.ride"

    /** Ancienne application iGPSPORT, encore installee chez certains. */
    const val IGPSPORT_LEGACY_PACKAGE = "com.igpsport.globalapp"

    private const val MAX_FILENAME_LENGTH = 28

    suspend fun exportRoute(
        context: Context,
        route: PlannedRoute,
        format: ExportFormat,
    ): ExportResult = withContext(Dispatchers.IO) {
        // La limite de 28 caracteres annoncee par iGPSPORT porte sur le nom
        // complet : on reserve donc la place de l'extension.
        val baseName = sanitize(route.name, MAX_FILENAME_LENGTH - (format.extension.length + 1))
        val fileName = "$baseName.${format.extension}"

        val bytes = when (format) {
            ExportFormat.GPX -> GpxWriter.writeRoute(route).toByteArray(Charsets.UTF_8)
            ExportFormat.TCX -> TcxWriter.writeCourse(route).toByteArray(Charsets.UTF_8)
            ExportFormat.FIT -> FitCourseWriter.write(route)
        }

        val shareFile = writeToCache(context, fileName, bytes)
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareFile,
        )
        ExportResult(
            displayName = fileName,
            downloadUri = writeToDownloads(context, fileName, format.mimeType, bytes),
            shareUri = shareUri,
            format = format,
        )
    }

    /** Export d'une sortie enregistree, pour l'envoyer vers Strava ou l'archiver. */
    suspend fun exportRide(
        context: Context,
        ride: Ride,
        points: List<RidePoint>,
    ): ExportResult = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(ride.startedAt))
        val fileName = sanitize("Cadence-$date", MAX_FILENAME_LENGTH - 4) + ".gpx"
        val bytes = GpxWriter.writeRide(ride.title, points).toByteArray(Charsets.UTF_8)
        val shareFile = writeToCache(context, fileName, bytes)
        ExportResult(
            displayName = fileName,
            downloadUri = writeToDownloads(context, fileName, ExportFormat.GPX.mimeType, bytes),
            shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile,
            ),
            format = ExportFormat.GPX,
        )
    }

    // ------------------------------------------------------------- diffusion

    fun isIgpsportInstalled(context: Context): Boolean =
        packageOf(context) != null

    private fun packageOf(context: Context): String? {
        val manager = context.packageManager
        for (candidate in listOf(IGPSPORT_PACKAGE, IGPSPORT_LEGACY_PACKAGE)) {
            val installed = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.getPackageInfo(candidate, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    manager.getPackageInfo(candidate, 0)
                }
            }.isSuccess
            if (installed) return candidate
        }
        return null
    }

    /**
     * Intention de partage ciblee sur l'application iGPSPORT si elle est
     * installee, sinon selecteur generique.
     */
    fun shareIntent(context: Context, result: ExportResult, forceChooser: Boolean = false): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = result.format.mimeType
            putExtra(Intent.EXTRA_STREAM, result.shareUri)
            putExtra(Intent.EXTRA_SUBJECT, result.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(result.displayName, result.shareUri)
        }
        val target = if (forceChooser) null else packageOf(context)
        return if (target != null) {
            send.setPackage(target)
            send
        } else {
            Intent.createChooser(send, "Envoyer le parcours")
        }
    }

    /** Ouvre l'application iGPSPORT sur son ecran d'accueil. */
    fun openIgpsport(context: Context): Intent? {
        val target = packageOf(context) ?: return null
        return context.packageManager.getLaunchIntentForPackage(target)
    }

    // ------------------------------------------------------------- ecriture

    private fun writeToCache(context: Context, fileName: String, bytes: ByteArray): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeBytes(bytes)
        return file
    }

    /**
     * Copie dans le dossier Telechargements, visible depuis n'importe quel
     * gestionnaire de fichiers. C'est de la que l'utilisateur ira chercher le
     * parcours dans l'application iGPSPORT.
     */
    private fun writeToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS,
            )
            directory.mkdirs()
            val file = File(directory, fileName)
            file.writeBytes(bytes)
            Uri.fromFile(file)
        }
    }.getOrNull()

    /**
     * Nom de fichier compatible avec les compteurs : ASCII, sans espace,
     * 28 caracteres au plus.
     */
    fun sanitize(name: String, maxLength: Int = MAX_FILENAME_LENGTH): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val cleaned = ascii
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        val result = cleaned.take(maxLength.coerceAtLeast(4)).trim('-')
        return result.ifBlank { "Parcours" }
    }
}

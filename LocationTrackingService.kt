package fr.velo.cadence.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import fr.velo.cadence.CadenceApp
import fr.velo.cadence.MainActivity
import fr.velo.cadence.R
import fr.velo.cadence.model.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Service de premier plan qui alimente l'enregistrement en positions.
 *
 * Android tue toute application en arriere-plan qui demande des positions en
 * continu : un service de premier plan de type `location`, avec sa
 * notification visible, est la seule facon fiable d'enregistrer une sortie de
 * plusieurs heures avec l'ecran eteint.
 */
class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START = "fr.velo.cadence.action.START"
        const val ACTION_PAUSE = "fr.velo.cadence.action.PAUSE"
        const val ACTION_RESUME = "fr.velo.cadence.action.RESUME"
        const val ACTION_STOP = "fr.velo.cadence.action.STOP"
        const val EXTRA_ROUTE_ID = "route_id"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context, routeId: Long? = null) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                routeId?.let { putExtra(EXTRA_ROUTE_ID, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val container get() = (application as CadenceApp).container
    private val recorder get() = container.rideRecorder

    private lateinit var locationClient: FusedLocationProviderClient
    private var altimeter: BarometricAltimeter? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var sensorJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { recorder.onLocation(it) }
            updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getLongExtra(EXTRA_ROUTE_ID, -1L).takeIf { it >= 0 })
            ACTION_PAUSE -> {
                recorder.pause()
                updateNotification()
            }
            ACTION_RESUME -> {
                recorder.resume()
                updateNotification()
            }
            ACTION_STOP -> handleStop()
            else -> if (!recorder.isActive) stopSelf()
        }
        return START_STICKY
    }

    private fun handleStart(routeId: Long?) {
        startForegroundCompat()
        if (recorder.isActive) return

        serviceScope.launch {
            val profile = container.userPreferences.profile.first()
            val settings = container.userPreferences.settings.first()

            altimeter = BarometricAltimeter(this@LocationTrackingService).also {
                if (settings.useBarometer && it.isAvailable) it.start()
                recorder.attachAltimeter(it)
            }
            recorder.start(
                profile = profile,
                autoPauseEnabled = settings.autoPause,
                useBarometer = settings.useBarometer && altimeter?.isAvailable == true,
                routeId = routeId,
            )
            connectSensors(settings.heartRateSensor, settings.powerSensor, settings.cscSensor)
            requestLocationUpdates()
            startTicker()
        }
    }

    private fun handleStop() {
        stopLocationUpdates()
        val recorded = recorder.stop()
        serviceScope.launch {
            if (recorded != null && recorded.ride.distanceM >= 200 && recorded.points.size >= 5) {
                val id = container.rideRepository.saveRide(recorded.ride, recorded.points)
                container.lastSavedRideId.value = id
            } else {
                container.lastSavedRideId.value = null
            }
            stopSelfSafely()
        }
    }

    private fun connectSensors(hr: String?, power: String?, csc: String?) {
        val manager = container.bleSensorManager
        listOfNotNull(hr, power, csc).forEach { manager.connect(it) }
        sensorJob?.cancel()
        sensorJob = serviceScope.launch {
            manager.readings.collect { recorder.updateSensors(it) }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (recorder.isActive) {
                recorder.tick()
                updateNotification()
                delay(1_000)
            }
        }
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()
        runCatching {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    private fun stopLocationUpdates() {
        runCatching { locationClient.removeLocationUpdates(locationCallback) }
        altimeter?.stop()
        tickerJob?.cancel()
        sensorJob?.cancel()
        container.bleSensorManager.disconnectAll()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_tracking),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_tracking_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val metrics = recorder.metrics.value
        val km = metrics.distanceM / 1000.0
        val minutes = metrics.movingTimeMs / 60_000
        val summary = "%.1f km  ·  %02d:%02d  ·  %.1f km/h".format(
            java.util.Locale.FRANCE,
            km,
            minutes / 60,
            minutes % 60,
            metrics.avgSpeedKmh,
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(summary)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (metrics.state) {
            RecordingState.RECORDING -> builder.addAction(
                0,
                getString(R.string.notif_tracking_action_pause),
                servicePendingIntent(ACTION_PAUSE, 1),
            )
            RecordingState.PAUSED, RecordingState.AUTO_PAUSED -> builder.addAction(
                0,
                getString(R.string.notif_tracking_action_resume),
                servicePendingIntent(ACTION_RESUME, 2),
            )
            RecordingState.IDLE -> Unit
        }
        builder.addAction(
            0,
            getString(R.string.notif_tracking_action_stop),
            servicePendingIntent(ACTION_STOP, 3),
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

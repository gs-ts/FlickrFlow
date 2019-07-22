package com.gts.flickrflow.presentation

import android.os.Build
import android.os.Binder
import android.os.Looper
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.app.ActivityManager
import android.app.Service
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.location.Location
import android.preference.PreferenceManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.FusedLocationProviderClient

import kotlinx.coroutines.*

import org.koin.android.ext.android.inject

import com.gts.flickrflow.R
import com.gts.flickrflow.core.Result
import com.gts.flickrflow.domain.EmptyPhotosDbUseCase
import com.gts.flickrflow.domain.SearchByLocationUseCase

import timber.log.Timber

class LocationService : Service() {

    private val TAG = LocationService::class.java.simpleName
    // The name of the channel for notifications.
    private val NOTIFICATION_CHANNEL_ID = "channel_01"
    // The identifier for the notification displayed for the foreground service.
    private val NOTIFICATION_ID = 1101
    private val EXTRA_STARTED_FROM_NOTIFICATION = "started_from_notification"

    private val binder = LocalBinder()
    // Contains parameters used by [com.google.android.gms.location.FusedLocationProviderApi].
    private lateinit var locationRequest: LocationRequest
    // Provides access to the Fused Location Provider API.
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    // Callback for changes in location.
    private lateinit var locationCallback: LocationCallback
    private lateinit var serviceHandler: Handler
    // The current location.
    private lateinit var location: Location
    private lateinit var notificationManager: NotificationManager

    private val searchByLocationUseCase: SearchByLocationUseCase by inject()
    private val emptyPhotosDbUseCase: EmptyPhotosDbUseCase by inject()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onCreate() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                super.onLocationResult(locationResult)
                onNewLocation(locationResult!!.lastLocation)
            }
        }

        createLocationRequest()
        getLastLocation()

        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        serviceHandler = Handler(handlerThread.looper)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            // Create the channel for the notification
            val mChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)

            // Set the Notification Channel for the Notification Manager.
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Timber.tag(TAG).i("LocationService started")
        val startedFromNotification = intent.getBooleanExtra(
            EXTRA_STARTED_FROM_NOTIFICATION,
            false
        )

        // We got here because the user decided to remove location updates from the notification.
        if (startedFromNotification) {
            removeLocationUpdates()
            stopSelf()
        }
        // Tells the system to not try to recreate the service after it has been killed.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        // Called when a client (MainActivity in case of this sample) comes to the foreground and binds with this service.
        // The service should cease to be a foreground service when that happens.
        Timber.tag(TAG).i("in onBind()")
        stopForeground(true)
        return binder
    }

    override fun onRebind(intent: Intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground and
        // binds once again with this service. The service should cease to be a foreground service when that happens.
        Timber.tag(TAG).i("in onRebind()")
        stopForeground(true)
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.tag(TAG).i("Last client unbound from service")
        Timber.tag(TAG).i("Starting foreground service")
        startForeground(NOTIFICATION_ID, getNotification())
        return true // Ensures onRebind() is called when a client re-binds.
    }

    override fun onDestroy() {
        serviceHandler.removeCallbacksAndMessages(null)
        job.cancel()
    }

    /**
     * Makes a request for location updates. Note that in this sample we merely log the [SecurityException].
     */
    fun requestLocationUpdates() {
        Timber.tag(TAG).i("Requesting location updates")
        startService(Intent(applicationContext, LocationService::class.java))
        try {
            saveServiceState(getString(R.string.service_state_started))
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.myLooper()
            )
            scope.launch {
                emptyPhotosDbUseCase.invoke()
            }
        } catch (unlikely: SecurityException) {
            saveServiceState(getString(R.string.service_state_stopped))
            Timber.tag(TAG).e("Lost location permission. Could not request updates. $unlikely")
        }
    }

    /**
     * Removes location updates. Note that in this sample we merely log the [SecurityException].
     */
    fun removeLocationUpdates() {
        Timber.tag(TAG).i("Removing location updates")
        try {
            saveServiceState(getString(R.string.service_state_stopped))
            fusedLocationClient.removeLocationUpdates(locationCallback)
            stopSelf()
        } catch (unlikely: SecurityException) {
            saveServiceState(getString(R.string.service_state_started))
            Timber.tag(TAG).e("Lost location permission. Could not remove updates. $unlikely")
        }
    }

    private fun getLastLocation() {
        try {
            fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    location = task.result!!
                } else {
                    Timber.tag(TAG).w("Failed to get location.")
                }
            }
        } catch (unlikely: SecurityException) {
            Timber.tag(TAG).e("Lost location permission.$unlikely")
        }
    }

    private fun onNewLocation(location: Location) {
        Timber.tag(TAG).i("New location: $location")
        this.location = location

        scope.launch {
            val result = searchByLocationUseCase.invoke(location.latitude.toString(), location.longitude.toString())
            when (result) {
                is Result.Success -> {
                    Timber.i(" LocationService success!")
                    // Notify anyone listening for broadcasts about the new photo.
                    val intent = Intent(ACTION_BROADCAST)
                    intent.putExtra(EXTRA_PHOTO, result.data)
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
                    Timber.i(" Photo broadcast sent!")
                }
                is Result.Error -> Timber.i(" LocationService error!")
            }
        }

        // Update notification content if running as a foreground service.
        if (serviceIsRunningInForeground(this)) {
            notificationManager.notify(NOTIFICATION_ID, getNotification())
        }
    }

    /**
     * Sets the location request parameters.
     */
    private fun createLocationRequest() {
        locationRequest = LocationRequest()
//        mLocationRequest.smallestDisplacement = 100F
        locationRequest.interval = 10000
        locationRequest.fastestInterval = 10000 / 2
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
    }

    /**
     * Class used for the client Binder.
     * Since this service runs in the same process as its clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        internal val service: LocationService
            get() = this@LocationService
    }

    /**
     * Returns true if this is a foreground service.
     *
     * @param context The [Context].
     */
    private fun serviceIsRunningInForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (javaClass.name == service.service.className) {
                if (service.foreground) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Returns the [NotificationCompat] used as part of the foreground service.
     */
    private fun getNotification(): Notification {
        val intent = Intent(this, LocationService::class.java)
        // Extra to help us figure out if we arrived in onStartCommand via the notification or not.
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true)
        // The PendingIntent that leads to a call to onStartCommand() in this service.
        val servicePendingIntent = PendingIntent.getService(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT
        )
        // The PendingIntent to launch activity.
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), 0
        )

        val builder = NotificationCompat.Builder(this)
            .addAction(0, getString(R.string.notification_action_launch), activityPendingIntent)
            .addAction(0, getString(R.string.notification_action_stop), servicePendingIntent)
            .setContentTitle(getString(R.string.notification_content_title))
            .setContentText(getString(R.string.notification_content_text))
            .setOngoing(true)
            .setPriority(1) // Notification.PRIORITY_HIGH
            .setSmallIcon(R.mipmap.ic_launcher)
            .setWhen(System.currentTimeMillis())

        // Set the Channel ID for Android O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID) // Channel ID
        }

        return builder.build()
    }

    /**
     * Save the state of the Service.
     * The UI retrieves the state to set the Button text.
     */
    private fun saveServiceState(state: String) {
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .edit()
            .putString(getString(R.string.service_state), state)
            .apply()
    }

    companion object {
        const val EXTRA_PHOTO = "location"
        const val ACTION_BROADCAST = "broadcast"
    }
}

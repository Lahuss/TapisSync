package fr.tapissync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import fr.tapissync.ble.FootpodBroadcaster
import fr.tapissync.ble.TreadmillManager
import fr.tapissync.garmin.GarminClient
import fr.tapissync.garmin.WorkoutSummary
import fr.tapissync.run.RunSession
import fr.tapissync.workout.Workout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var garmin: GarminClient
        private set
    lateinit var treadmill: TreadmillManager
        private set
    lateinit var notificationManager: NotificationManager
        private set

    /** Capteur de foulée simulé, qui renvoie la vitesse du tapis à la montre. */
    lateinit var footpod: FootpodBroadcaster
        private set

    /** Fils pour le réseau (jamais sur le fil principal). */
    val io: ExecutorService = Executors.newFixedThreadPool(2)

    /** Séance en cours, s'il y en a une. */
    var session: RunSession? = null

    /** Séance choisie, transmise à l'écran d'aperçu. */
    var selectedWorkout: Workout? = null

    /** Dernières listes chargées, pour ne pas tout recharger à chaque retour à l'accueil. */
    var cachedScheduled: List<WorkoutSummary>? = null
    var cachedLibrary: List<WorkoutSummary>? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        garmin = GarminClient(prefs)
        treadmill = TreadmillManager(this)
        footpod = FootpodBroadcaster(this).apply { cadence = prefs.footpodCadence }
        treadmill.listeners.add(footpod)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Séance en cours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Étape et vitesse pendant la séance"
                setShowBadge(false)
            },
        )
        if (prefs.footpodEnabled && footpod.hasPermissions()) footpod.start()
    }

    companion object {
        const val CHANNEL_RUN = "run"
        lateinit var instance: App
            private set
    }
}

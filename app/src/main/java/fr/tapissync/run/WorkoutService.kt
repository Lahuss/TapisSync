package fr.tapissync.run

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import fr.tapissync.App
import fr.tapissync.R
import fr.tapissync.ui.RunActivity
import fr.tapissync.workout.Format

/**
 * Service au premier plan pendant la séance : garde la connexion au tapis et le minuteur
 * actifs même si tu regardes une vidéo dans une autre appli.
 */
class WorkoutService : Service(), RunSession.Listener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var session: RunSession? = null
    private var lastNotifMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notif = build("Séance en préparation", "")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Android 14+ refuse ce type de service sans l'autorisation Bluetooth :
            // la séance continue, mais seulement tant que l'écran de séance est ouvert.
            fr.tapissync.AppLog.add("Service", "Premier plan refusé : ${e.message}")
            stopSelf()
            return
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapisSync:seance")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = App.instance.session
        if (s == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (s !== session) {
            session?.listeners?.remove(this)
            session = s
            s.listeners.add(this)
        }
        onUpdate()
        return START_NOT_STICKY
    }

    override fun onUpdate() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifMs < 1000) return
        lastNotifMs = now
        val s = session ?: return
        val e = s.engine
        val step = e.current ?: return
        val title = when (e.phase) {
            WorkoutEngine.Phase.READY -> "Prêt : ${s.workout.name}"
            WorkoutEngine.Phase.PAUSED -> "En pause · ${step.title}"
            WorkoutEngine.Phase.FINISHED -> "Séance terminée"
            WorkoutEngine.Phase.RUNNING -> "${step.title} · ${Format.kmh(e.targetKmh())} km/h"
        }
        val remaining = e.stepRemainingS()?.let { "${Format.duration(it)} restantes" }
            ?: e.stepRemainingM()?.let { "${Format.distance(it)} restants" }
            ?: "Appuie sur Suivant pour continuer"
        val text = "Étape ${e.index + 1}/${e.steps.size} · $remaining"
        App.instance.notificationManager.notify(NOTIF_ID, build(title, text))
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, App.CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .build()
    }

    override fun onDestroy() {
        session?.listeners?.remove(this)
        session = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42

        fun start(ctx: Context) {
            // Sans l'autorisation Bluetooth, Android 14+ refuse ce service : on s'en passe.
            if (!App.instance.treadmill.hasPermissions()) {
                fr.tapissync.AppLog.add("Service", "Autorisation Bluetooth absente : pas de service au premier plan")
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, WorkoutService::class.java))
            } catch (e: Exception) {
                fr.tapissync.AppLog.add("Service", "Démarrage impossible : ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WorkoutService::class.java))
        }
    }
}

package fr.tapissync

import android.content.Context
import android.content.SharedPreferences
import fr.tapissync.workout.SpeedSettings

/** Réglages et jetons, stockés dans l'espace privé de l'appli. */
class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("tapissync", Context.MODE_PRIVATE)

    // ---------- Réglages de vitesse ----------

    var speedSettings: SpeedSettings
        get() {
            val d = SpeedSettings()
            return SpeedSettings(
                rangePosition = getD("range_pos", d.rangePosition),
                correctionPct = getD("correction_pct", d.correctionPct),
                warmupKmh = getD("warmup_kmh", d.warmupKmh),
                activeKmh = getD("active_kmh", d.activeKmh),
                recoveryKmh = getD("recovery_kmh", d.recoveryKmh),
                restKmh = getD("rest_kmh", d.restKmh),
                cooldownKmh = getD("cooldown_kmh", d.cooldownKmh),
                minKmh = getD("min_kmh", d.minKmh),
                maxKmh = getD("max_kmh", d.maxKmh),
            )
        }
        set(s) {
            p.edit()
                .putD("range_pos", s.rangePosition)
                .putD("correction_pct", s.correctionPct)
                .putD("warmup_kmh", s.warmupKmh)
                .putD("active_kmh", s.activeKmh)
                .putD("recovery_kmh", s.recoveryKmh)
                .putD("rest_kmh", s.restKmh)
                .putD("cooldown_kmh", s.cooldownKmh)
                .putD("min_kmh", s.minKmh)
                .putD("max_kmh", s.maxKmh)
                .apply()
        }

    /** Vitesse de marche à la fin de la séance (0 = arrêter la bande). */
    var endSpeedKmh: Double
        get() = getD("end_speed_kmh", 4.0)
        set(v) = p.edit().putD("end_speed_kmh", v).apply()

    /** Inclinaison appliquée au départ (NaN = ne pas toucher). */
    var startInclinePct: Double
        get() = getD("start_incline", Double.NaN)
        set(v) = p.edit().putD("start_incline", v).apply()

    /** Bandeau en bas de l'écran pendant la séance, par-dessus les autres applis. */
    var barEnabled: Boolean
        get() = p.getBoolean("bar", true)
        set(v) = p.edit().putBoolean("bar", v).apply()

    /** Distance de l'onglet du bandeau rangé au bord droit (px), -1 = position par défaut. */
    var tabOffset: Int
        get() = p.getInt("tab_offset", -1)
        set(v) = p.edit().putInt("tab_offset", v).apply()

    /** Demande d'autorisation du bandeau déjà proposée une fois. */
    var overlayPrompted: Boolean
        get() = p.getBoolean("overlay_prompted", false)
        set(v) = p.edit().putBoolean("overlay_prompted", v).apply()

    var voiceEnabled: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()

    var beepEnabled: Boolean
        get() = p.getBoolean("beep", true)
        set(v) = p.edit().putBoolean("beep", v).apply()

    // ---------- Tapis ----------

    var treadmillAddress: String?
        get() = p.getString("tm_addr", null)
        set(v) = p.edit().putString("tm_addr", v).apply()

    var treadmillName: String?
        get() = p.getString("tm_name", null)
        set(v) = p.edit().putString("tm_name", v).apply()

    // ---------- Jetons Garmin ----------

    var diAccessToken: String?
        get() = p.getString("di_access", null)
        set(v) = p.edit().putString("di_access", v).apply()

    var diRefreshToken: String?
        get() = p.getString("di_refresh", null)
        set(v) = p.edit().putString("di_refresh", v).apply()

    var diClientId: String?
        get() = p.getString("di_client", null)
        set(v) = p.edit().putString("di_client", v).apply()

    /** Expiration du jeton d'accès (ms depuis 1970). */
    var diAccessExpiresAt: Long
        get() = p.getLong("di_access_exp", 0L)
        set(v) = p.edit().putLong("di_access_exp", v).apply()

    var diRefreshExpiresAt: Long
        get() = p.getLong("di_refresh_exp", 0L)
        set(v) = p.edit().putLong("di_refresh_exp", v).apply()

    var garminDisplayName: String?
        get() = p.getString("gc_name", null)
        set(v) = p.edit().putString("gc_name", v).apply()

    fun saveTokens(access: String, refresh: String?, clientId: String, expiresInS: Long, refreshExpiresInS: Long?) {
        val now = System.currentTimeMillis()
        val e = p.edit()
            .putString("di_access", access)
            .putString("di_client", clientId)
            .putLong("di_access_exp", now + expiresInS * 1000L)
        if (!refresh.isNullOrBlank()) e.putString("di_refresh", refresh)
        if (refreshExpiresInS != null && refreshExpiresInS > 0) e.putLong("di_refresh_exp", now + refreshExpiresInS * 1000L)
        // commit() : la rotation du jeton de rafraîchissement ne doit pas se perdre.
        e.commit()
    }

    fun clearTokens() {
        p.edit()
            .remove("di_access").remove("di_refresh").remove("di_client")
            .remove("di_access_exp").remove("di_refresh_exp").remove("gc_name")
            .commit()
    }

    val isGarminConnected: Boolean
        get() = !diRefreshToken.isNullOrBlank() || !diAccessToken.isNullOrBlank()

    // ---------- Utilitaires ----------

    private fun getD(key: String, def: Double): Double =
        if (p.contains(key)) java.lang.Double.longBitsToDouble(p.getLong(key, 0L)) else def

    private fun SharedPreferences.Editor.putD(key: String, v: Double): SharedPreferences.Editor =
        putLong(key, java.lang.Double.doubleToRawLongBits(v))
}

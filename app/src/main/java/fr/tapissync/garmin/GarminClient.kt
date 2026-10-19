package fr.tapissync.garmin

import android.util.Base64
import fr.tapissync.AppLog
import fr.tapissync.Prefs
import fr.tapissync.workout.GarminWorkoutParser
import fr.tapissync.workout.Workout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth

class GarminException(message: String, val code: Int = 0, val needLogin: Boolean = false) : Exception(message)

/** Résumé d'une séance (calendrier ou bibliothèque). */
data class WorkoutSummary(
    val workoutId: Long?,
    val scheduleId: Long?,
    val uuid: String?,
    val itemType: String,
    val name: String,
    val date: LocalDate?,
    val sportKey: String,
    val estimatedSeconds: Double?,
)

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

object GarminConfig {
    /** Service pour lequel Garmin émet le ticket ; l'échange doit annoncer le même. */
    const val MOBILE_SERVICE = "https://mobile.integration.garmin.com/gcm/android"

    /** Page de connexion de l'appli Garmin Connect mobile (la seule qui fonctionne). */
    val LOGIN_URL = "https://sso.garmin.com/mobile/sso/en_US/sign-in?clientId=GCM_ANDROID_DARK&service=" +
        enc(MOBILE_SERVICE)
    const val TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
    const val GRANT_SERVICE_TICKET = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
    const val API = "https://connectapi.garmin.com"
    val CLIENT_IDS = listOf(
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI",
    )

    /** En-têtes de l'appli Garmin Connect Android, attendus par diauth et connectapi. */
    val APP_HEADERS = mapOf(
        "User-Agent" to "GCM-Android-5.23",
        "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
        "X-Garmin-Paired-App-Version" to "10861",
        "X-Garmin-Client-Platform" to "Android",
        "X-App-Ver" to "10861",
        "X-Lang" to "en",
        "X-GCExperience" to "GC5",
        "Accept-Language" to "en-US,en;q=0.9",
    )
}

/**
 * Accès non officiel à Garmin Connect, avec les jetons « DI OAuth2 » de l'appli mobile.
 * Ces appels sont bloquants : à lancer hors du fil principal.
 */
class GarminClient(private val prefs: Prefs) {

    // ---------- Jetons ----------

    /** Échange le ticket de connexion (ST-…) contre des jetons. */
    fun exchangeTicket(ticket: String, serviceUrl: String) {
        var lastError = "aucune réponse"
        for (clientId in GarminConfig.CLIENT_IDS) {
            val body = form(
                "client_id" to clientId,
                "service_ticket" to ticket,
                "grant_type" to GarminConfig.GRANT_SERVICE_TICKET,
                "service_url" to serviceUrl,
            )
            val r = http("POST", GarminConfig.TOKEN_URL, tokenHeaders(clientId), body)
            AppLog.add("Garmin", "Échange du ticket ($clientId) → HTTP ${r.code}")
            if (r.code == 200) {
                storeTokenResponse(JSONObject(r.body), clientId)
                return
            }
            lastError = "HTTP ${r.code} ${r.body.take(200)}"
        }
        throw GarminException("Garmin a refusé la connexion ($lastError)")
    }

    private fun refresh(): Boolean {
        val refresh = prefs.diRefreshToken ?: return false
        val clientId = prefs.diClientId ?: GarminConfig.CLIENT_IDS.first()
        val r = http(
            "POST", GarminConfig.TOKEN_URL, tokenHeaders(clientId),
            form("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh),
        )
        AppLog.add("Garmin", "Rafraîchissement du jeton → HTTP ${r.code}")
        return when (r.code) {
            200 -> {
                storeTokenResponse(JSONObject(r.body), clientId)
                true
            }
            400, 401, 403 -> {
                prefs.clearTokens()
                throw GarminException("Session Garmin expirée, reconnecte-toi.", r.code, needLogin = true)
            }
            else -> throw GarminException("Garmin indisponible (HTTP ${r.code})", r.code)
        }
    }

    private fun storeTokenResponse(j: JSONObject, clientId: String) {
        val access = j.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw GarminException("Réponse Garmin sans jeton")
        val refresh = j.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" }
        val expires = j.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val refreshExp = if (j.has("refresh_token_expires_in")) j.optLong("refresh_token_expires_in") else null
        prefs.saveTokens(access, refresh, clientId, expires, refreshExp)
    }

    private fun tokenHeaders(clientId: String): Map<String, String> {
        val basic = Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
        return GarminConfig.APP_HEADERS + mapOf(
            "Authorization" to "Basic $basic",
            "Accept" to "application/json",
            "Cache-Control" to "no-cache",
            "Content-Type" to "application/x-www-form-urlencoded",
        )
    }

    private fun validAccessToken(): String {
        val token = prefs.diAccessToken
        val soon = System.currentTimeMillis() + 5 * 60_000L
        if (token != null && prefs.diAccessExpiresAt > soon) return token
        if (!refresh()) throw GarminException("Pas connecté à Garmin.", needLogin = true)
        return prefs.diAccessToken ?: throw GarminException("Pas connecté à Garmin.", needLogin = true)
    }

    // ---------- API ----------

    private fun apiGet(path: String): String {
        var token = validAccessToken()
        var r = http("GET", GarminConfig.API + path, apiHeaders(token), null)
        if (r.code == 401) {
            AppLog.add("Garmin", "401 sur $path, rafraîchissement")
            if (!refresh()) throw GarminException("Pas connecté à Garmin.", needLogin = true)
            token = prefs.diAccessToken ?: throw GarminException("Pas connecté à Garmin.", needLogin = true)
            r = http("GET", GarminConfig.API + path, apiHeaders(token), null)
        }
        if (r.code !in 200..299) {
            AppLog.add("Garmin", "GET $path → HTTP ${r.code} ${r.body.take(200)}")
            throw GarminException("Erreur Garmin (HTTP ${r.code})", r.code, needLogin = r.code == 401)
        }
        return r.body
    }

    private fun apiHeaders(token: String) = GarminConfig.APP_HEADERS + mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/json",
        "NK" to "NT",
    )

    /** Teste la connexion et récupère le nom affiché. */
    fun profileName(): String? = try {
        val j = JSONObject(apiGet("/userprofile-service/socialProfile"))
        (j.optString("displayName").takeIf { it.isNotBlank() } ?: j.optString("fullName"))
            .takeIf { it.isNotBlank() && it != "null" }
    } catch (e: GarminException) {
        if (e.needLogin) throw e
        null
    }

    /** Séances planifiées dans le calendrier entre deux dates. */
    fun scheduledWorkouts(from: LocalDate, to: LocalDate): List<WorkoutSummary> {
        val months = mutableListOf<YearMonth>()
        var ym = YearMonth.from(from)
        while (!ym.isAfter(YearMonth.from(to))) {
            months += ym
            ym = ym.plusMonths(1)
        }
        // L'API numérote les mois à partir de 0 ; on interroge aussi l'indice suivant
        // par sécurité, puis on filtre sur la date de chaque élément.
        val queries = months.flatMap { listOf(it.year to it.monthValue - 1, it.year to it.monthValue) }
            .distinct().filter { it.second in 0..11 }
        val seen = HashSet<String>()
        val out = mutableListOf<WorkoutSummary>()
        var failures = 0
        for ((year, month) in queries) {
            val body = try {
                apiGet("/calendar-service/year/$year/month/$month")
            } catch (e: GarminException) {
                if (e.needLogin) throw e
                failures++
                continue
            }
            val items = JSONObject(body).optJSONArray("calendarItems") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val type = it.optString("itemType")
                if (!type.contains("workout", ignoreCase = true)) continue
                val date = runCatching { LocalDate.parse(it.optString("date").take(10)) }.getOrNull()
                if (date != null && (date.isBefore(from) || date.isAfter(to))) continue
                val sport = it.optString("sportTypeKey").takeIf { s -> s != "null" } ?: ""
                if (sport.isNotBlank() && !sport.contains("run", ignoreCase = true)) continue
                val key = "${it.opt("id")}-${it.opt("workoutId")}-$date"
                if (!seen.add(key)) continue
                out += WorkoutSummary(
                    workoutId = longOrNull(it, "workoutId"),
                    scheduleId = longOrNull(it, "id"),
                    uuid = it.optString("workoutUuid").takeIf { u -> u.isNotBlank() && u != "null" },
                    itemType = type,
                    name = it.optString("title").takeIf { t -> t.isNotBlank() && t != "null" } ?: "Séance",
                    date = date,
                    sportKey = sport,
                    estimatedSeconds = it.optDouble("duration", Double.NaN).takeIf { d -> !d.isNaN() && d > 0 },
                )
            }
        }
        if (out.isEmpty() && failures == queries.size) throw GarminException("Calendrier Garmin inaccessible")
        return out.sortedWith(compareBy({ it.date }, { it.name }))
    }

    /** Séances enregistrées dans « Mes entraînements ». */
    fun libraryWorkouts(): List<WorkoutSummary> {
        val body = apiGet("/workout-service/workouts?start=0&limit=200&myWorkoutsOnly=true&sharedWorkoutsOnly=false")
        val arr = if (body.trimStart().startsWith("[")) JSONArray(body)
        else JSONObject(body).optJSONArray("workouts") ?: JSONArray()
        val out = mutableListOf<WorkoutSummary>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val sport = it.optJSONObject("sportType")?.optString("sportTypeKey") ?: ""
            if (sport.isNotBlank() && !sport.contains("run", ignoreCase = true)) continue
            out += WorkoutSummary(
                workoutId = longOrNull(it, "workoutId"),
                scheduleId = null,
                uuid = null,
                itemType = "workout",
                name = it.optString("workoutName").takeIf { n -> n.isNotBlank() } ?: "Séance",
                date = null,
                sportKey = sport,
                estimatedSeconds = it.optDouble("estimatedDurationInSecs", Double.NaN).takeIf { d -> !d.isNaN() && d > 0 },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Détail d'une séance, mis à plat. */
    fun workout(s: WorkoutSummary): Workout {
        val attempts = buildList {
            s.workoutId?.let { add("/workout-service/workout/$it") }
            if (s.uuid != null) add("/workout-service/fbt-adaptive/${s.uuid}")
            s.scheduleId?.let { add("/workout-service/schedule/$it") }
        }
        if (attempts.isEmpty()) throw GarminException("Séance sans identifiant")
        var last: Exception? = null
        for (path in attempts) {
            try {
                val j = JSONObject(apiGet(path))
                var w = GarminWorkoutParser.parse(j)
                if (w.steps.isEmpty()) j.optJSONObject("workout")?.let { w = GarminWorkoutParser.parse(it) }
                if (w.steps.isNotEmpty()) {
                    return if (w.name == "Séance") w.copy(name = s.name) else w
                }
                AppLog.add("Garmin", "$path : aucune étape trouvée")
            } catch (e: GarminException) {
                if (e.needLogin) throw e
                last = e
            }
        }
        throw GarminException("Impossible de lire cette séance${last?.message?.let { " ($it)" } ?: ""}")
    }

    // ---------- HTTP ----------

    private data class Resp(val code: Int, val body: String)

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text)
        } catch (e: IOException) {
            throw GarminException("Réseau indisponible (${e.message})")
        } finally {
            c.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun longOrNull(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optLong(key, Long.MIN_VALUE)
        return if (v == Long.MIN_VALUE || v == 0L) null else v
    }
}

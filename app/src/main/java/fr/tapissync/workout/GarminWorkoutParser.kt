package fr.tapissync.workout

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lit le JSON renvoyé par workout-service/workout/{id} de Garmin Connect
 * et le met à plat : les blocs répétés sont déroulés étape par étape.
 */
object GarminWorkoutParser {

    /** Garde-fou contre un JSON aberrant (répétitions imbriquées énormes). */
    private const val MAX_STEPS = 2000

    fun parse(json: JSONObject): Workout {
        val steps = mutableListOf<WorkoutStep>()
        val segments = json.optJSONArray("workoutSegments")
        if (segments != null) {
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONObject(i) ?: continue
                steps += parseSteps(seg.optJSONArray("workoutSteps"), emptyList())
            }
        } else {
            // Certains formats (séances adaptatives) posent les étapes à la racine.
            steps += parseSteps(json.optJSONArray("workoutSteps"), emptyList())
        }
        return Workout(
            id = optLong(json, "workoutId"),
            name = optText(json, "workoutName") ?: optText(json, "title") ?: "Séance",
            sportKey = json.optJSONObject("sportType")?.let { optText(it, "sportTypeKey") } ?: "",
            description = optText(json, "description"),
            steps = steps.take(MAX_STEPS),
        )
    }

    private fun parseSteps(arr: JSONArray?, repeatPath: List<String>): List<WorkoutStep> {
        if (arr == null) return emptyList()
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .sortedBy { it.optInt("stepOrder", 0) }
        val out = mutableListOf<WorkoutStep>()
        for (item in items) {
            if (isRepeat(item)) {
                out += expandRepeat(item, repeatPath)
            } else {
                out += parseExecutable(item, repeatPath)
            }
            if (out.size > MAX_STEPS) break
        }
        return out
    }

    private fun isRepeat(item: JSONObject): Boolean {
        val type = item.optString("type")
        val stepTypeKey = item.optJSONObject("stepType")?.optString("stepTypeKey")
        return type == "RepeatGroupDTO" || stepTypeKey == "repeat" ||
            (item.optJSONArray("workoutSteps")?.length() ?: 0) > 0
    }

    private fun expandRepeat(item: JSONObject, repeatPath: List<String>): List<WorkoutStep> {
        var n = item.optInt("numberOfIterations", 0)
        if (n <= 0) {
            val cond = item.optJSONObject("endCondition")?.optString("conditionTypeKey")
            if (cond == "iterations") n = optNum(item, "endConditionValue")?.toInt() ?: 0
        }
        if (n <= 0) n = 1
        n = n.coerceAtMost(200)
        val skipLastRest = item.optBoolean("skipLastRestStep", false)
        val children = item.optJSONArray("workoutSteps")
        val out = mutableListOf<WorkoutStep>()
        for (i in 1..n) {
            val iteration = parseSteps(children, repeatPath + "$i/$n").toMutableList()
            if (skipLastRest && i == n && iteration.isNotEmpty() &&
                iteration.last().kind in setOf(StepKind.REST, StepKind.RECOVERY)
            ) {
                iteration.removeAt(iteration.lastIndex)
            }
            out += iteration
            if (out.size > MAX_STEPS) break
        }
        return out
    }

    private fun parseExecutable(item: JSONObject, repeatPath: List<String>): WorkoutStep {
        val kind = StepKind.fromGarmin(item.optJSONObject("stepType")?.optString("stepTypeKey"))
        val condKey = item.optJSONObject("endCondition")?.optString("conditionTypeKey")
        val value = optNum(item, "endConditionValue")
        val end = when (condKey) {
            "time", "fixed.rest" ->
                if (value != null && value > 0) EndCondition.Time(value) else EndCondition.Manual("durée absente")
            "distance" ->
                if (value != null && value > 0) EndCondition.Distance(value) else EndCondition.Manual("distance absente")
            "lap.button", null, "" -> EndCondition.Manual("bouton Lap")
            "heart.rate" -> EndCondition.Manual("jusqu'à une fréquence cardiaque")
            "calories" -> EndCondition.Manual("jusqu'à un nombre de calories")
            else -> EndCondition.Manual(condKey)
        }

        var target = parseTarget(
            item.optJSONObject("targetType")?.optString("workoutTargetTypeKey"),
            optNum(item, "targetValueOne"),
            optNum(item, "targetValueTwo"),
            optNum(item, "zoneNumber")?.toInt(),
        )
        if (target !is Target.SpeedRange) {
            // Une allure peut être posée en cible secondaire (ex. cible principale = cadence).
            val secondary = parseTarget(
                item.optJSONObject("secondaryTargetType")?.optString("workoutTargetTypeKey"),
                optNum(item, "secondaryTargetValueOne"),
                optNum(item, "secondaryTargetValueTwo"),
                optNum(item, "secondaryZoneNumber")?.toInt(),
            )
            if (secondary is Target.SpeedRange || (target is Target.None && secondary !is Target.None)) {
                target = secondary
            }
        }

        return WorkoutStep(
            kind = kind,
            end = end,
            target = target,
            description = optText(item, "description"),
            repeatLabel = repeatPath.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        )
    }

    internal fun parseTarget(key: String?, one: Double?, two: Double?, zone: Int?): Target {
        val k = key?.lowercase()
        return when (k) {
            null, "", "no.target" -> Target.None
            "pace.zone", "speed.zone" -> {
                val values = listOfNotNull(one, two).filter { it > 0.05 }
                when {
                    values.isNotEmpty() -> Target.SpeedRange(
                        slowMps = values.min(),
                        fastMps = values.max(),
                        fromPace = k == "pace.zone",
                    )
                    zone != null && zone > 0 -> Target.PaceZone(zone)
                    else -> Target.None
                }
            }
            "heart.rate.zone" -> Target.HeartRate(one, two, zone)
            else -> Target.Other(k)
        }
    }

    private fun optNum(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val d = o.optDouble(key, Double.NaN)
        return if (d.isNaN()) null else d
    }

    private fun optLong(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        val l = o.optLong(key, Long.MIN_VALUE)
        return if (l == Long.MIN_VALUE) null else l
    }

    private fun optText(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }
}

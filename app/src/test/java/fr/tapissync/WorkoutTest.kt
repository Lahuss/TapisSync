package fr.tapissync

import fr.tapissync.workout.EndCondition
import fr.tapissync.workout.Format
import fr.tapissync.workout.GarminWorkoutParser
import fr.tapissync.workout.MachineSpeedRange
import fr.tapissync.workout.SpeedPlanner
import fr.tapissync.workout.SpeedSettings
import fr.tapissync.workout.StepKind
import fr.tapissync.workout.Target
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutTest {

    // Séance type telle que renvoyée par workout-service/workout/{id} :
    // échauffement 10 min sans cible, 5 x (1 km à 4:40–4:50 /km + 2 min récup), retour au calme Lap.
    private val sample = """
    {
      "workoutId": 123456789,
      "workoutName": "Fractionné 5 x 1 km",
      "description": null,
      "sportType": {"sportTypeId": 1, "sportTypeKey": "running"},
      "workoutSegments": [{
        "segmentOrder": 1,
        "sportType": {"sportTypeId": 1, "sportTypeKey": "running"},
        "workoutSteps": [
          {"type": "ExecutableStepDTO", "stepOrder": 1,
           "stepType": {"stepTypeId": 1, "stepTypeKey": "warmup"},
           "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time"},
           "endConditionValue": 600.0,
           "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target"},
           "targetValueOne": null, "targetValueTwo": null, "zoneNumber": null},
          {"type": "RepeatGroupDTO", "stepOrder": 2,
           "stepType": {"stepTypeId": 6, "stepTypeKey": "repeat"},
           "numberOfIterations": 5, "smartRepeat": false, "skipLastRestStep": true,
           "endCondition": {"conditionTypeId": 7, "conditionTypeKey": "iterations"},
           "endConditionValue": 5.0,
           "workoutSteps": [
             {"type": "ExecutableStepDTO", "stepOrder": 3,
              "stepType": {"stepTypeId": 3, "stepTypeKey": "interval"},
              "endCondition": {"conditionTypeId": 3, "conditionTypeKey": "distance"},
              "endConditionValue": 1000.0,
              "targetType": {"workoutTargetTypeId": 6, "workoutTargetTypeKey": "pace.zone"},
              "targetValueOne": 3.4482758620689653, "targetValueTwo": 3.5714285714285716},
             {"type": "ExecutableStepDTO", "stepOrder": 4,
              "stepType": {"stepTypeId": 4, "stepTypeKey": "recovery"},
              "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time"},
              "endConditionValue": 120.0,
              "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target"}}
           ]},
          {"type": "ExecutableStepDTO", "stepOrder": 5,
           "stepType": {"stepTypeId": 2, "stepTypeKey": "cooldown"},
           "endCondition": {"conditionTypeId": 1, "conditionTypeKey": "lap.button"},
           "endConditionValue": null,
           "targetType": {"workoutTargetTypeId": 4, "workoutTargetTypeKey": "heart.rate.zone"},
           "zoneNumber": 2}
        ]
      }]
    }
    """.trimIndent()

    @Test
    fun parsesAndExpandsRepeats() {
        val w = GarminWorkoutParser.parse(JSONObject(sample))
        assertEquals("Fractionné 5 x 1 km", w.name)
        assertTrue(w.isRunning)
        // 1 échauffement + 5 intervalles + 4 récups (dernière sautée) + 1 retour au calme
        assertEquals(11, w.steps.size)
        assertEquals(StepKind.WARMUP, w.steps[0].kind)
        assertEquals(EndCondition.Time(600.0), w.steps[0].end)
        assertEquals(StepKind.ACTIVE, w.steps[1].kind)
        assertEquals(EndCondition.Distance(1000.0), w.steps[1].end)
        assertEquals("1/5", w.steps[1].repeatLabel)
        assertEquals(StepKind.ACTIVE, w.steps[9].kind)
        assertEquals("5/5", w.steps[9].repeatLabel)
        assertEquals(StepKind.COOLDOWN, w.steps[10].kind)
        assertTrue(w.steps[10].end is EndCondition.Manual)
        assertTrue(w.steps[10].target is Target.HeartRate)
    }

    @Test
    fun paceRangeMiddleIsMiddleOfPace() {
        // 4:40–4:50 /km : le milieu est 4:45 /km = 12,63 km/h → 12,6 km/h au dixième.
        val w = GarminWorkoutParser.parse(JSONObject(sample))
        val plan = SpeedPlanner.plan(w, SpeedSettings())
        val interval = plan[1]
        assertTrue(interval.fromTarget)
        assertEquals(12.6, interval.speedKmh, 1e-9)
        assertEquals("4:45", Format.pace(SpeedPlanner.speedInRange(3.4482758620689653, 3.5714285714285716, 0.5)))
        // Les bouts de plage.
        assertEquals("4:50", Format.pace(SpeedPlanner.speedInRange(3.4482758620689653, 3.5714285714285716, 0.0)))
        assertEquals("4:40", Format.pace(SpeedPlanner.speedInRange(3.4482758620689653, 3.5714285714285716, 1.0)))
        // Étapes sans cible : vitesses par défaut.
        assertEquals(9.0, plan[0].speedKmh, 1e-9)
        assertEquals(7.5, plan[2].speedKmh, 1e-9)
        assertEquals(8.0, plan[10].speedKmh, 1e-9)
    }

    @Test
    fun correctionAndMachineLimits() {
        val w = GarminWorkoutParser.parse(JSONObject(sample))
        val plan = SpeedPlanner.plan(w, SpeedSettings(correctionPct = 2.0))
        // 12,63 × 1,02 = 12,88 → 12,9
        assertEquals(12.9, plan[1].speedKmh, 1e-9)
        // La correction ne touche pas les vitesses par défaut.
        assertEquals(9.0, plan[0].speedKmh, 1e-9)

        val machine = MachineSpeedRange(minKmh = 1.0, maxKmh = 12.0, incrementKmh = 0.5)
        val capped = SpeedPlanner.plan(w, SpeedSettings(), machine)
        assertEquals(12.0, capped[1].speedKmh, 1e-9)
        assertEquals(7.5, capped[2].speedKmh, 1e-9)
    }

    @Test
    fun reversedAndSingleValueTargets() {
        val t = GarminWorkoutParser.parseTarget("pace.zone", 3.5714285714285716, 3.4482758620689653, null)
        assertTrue(t is Target.SpeedRange)
        t as Target.SpeedRange
        assertTrue(t.slowMps < t.fastMps)
        val single = GarminWorkoutParser.parseTarget("pace.zone", 3.3333333333, null, null) as Target.SpeedRange
        assertEquals("5:00", Format.pace(SpeedPlanner.speedInRange(single.slowMps, single.fastMps, 0.5)))
        assertTrue(GarminWorkoutParser.parseTarget("pace.zone", null, null, 3) is Target.PaceZone)
        assertTrue(GarminWorkoutParser.parseTarget("no.target", null, null, null) is Target.None)
    }

    @Test
    fun estimates() {
        val w = GarminWorkoutParser.parse(JSONObject(sample))
        val plan = SpeedPlanner.plan(w, SpeedSettings())
        val meters = SpeedPlanner.estimatedMeters(plan)
        // 10 min à 9 km/h (1500 m) + 5 km + 4 × 2 min à 7,5 km/h (1000 m)
        assertEquals(7500.0, meters, 1.0)
    }
}

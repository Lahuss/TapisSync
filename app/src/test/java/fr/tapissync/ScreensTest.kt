package fr.tapissync

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.ContextThemeWrapper
import android.os.Looper
import android.view.View.MeasureSpec
import fr.tapissync.ble.Ftms
import fr.tapissync.garmin.WorkoutSummary
import fr.tapissync.run.RunSession
import fr.tapissync.ui.BarView
import fr.tapissync.ui.MainActivity
import fr.tapissync.ui.PreviewActivity
import fr.tapissync.ui.RunActivity
import fr.tapissync.ui.SettingsActivity
import fr.tapissync.workout.GarminWorkoutParser
import fr.tapissync.workout.SpeedPlanner
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.LocalDate

/**
 * Rendu des principaux écrans en PNG (build/screens), pour vérifier le design
 * sans appareil. Ce n'est pas un test fonctionnel : il ne vérifie rien d'autre que l'affichage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreensTest {

    private val sample = """
    {"workoutId": 1, "workoutName": "Fractionné 5 x 1 km", "sportType": {"sportTypeKey": "running"},
     "workoutSegments": [{"workoutSteps": [
       {"type": "ExecutableStepDTO", "stepOrder": 1, "stepType": {"stepTypeKey": "warmup"},
        "endCondition": {"conditionTypeKey": "time"}, "endConditionValue": 600.0,
        "targetType": {"workoutTargetTypeKey": "no.target"}},
       {"type": "RepeatGroupDTO", "stepOrder": 2, "stepType": {"stepTypeKey": "repeat"},
        "numberOfIterations": 5, "skipLastRestStep": true, "workoutSteps": [
          {"type": "ExecutableStepDTO", "stepOrder": 3, "stepType": {"stepTypeKey": "interval"},
           "endCondition": {"conditionTypeKey": "distance"}, "endConditionValue": 1000.0,
           "targetType": {"workoutTargetTypeKey": "pace.zone"},
           "targetValueOne": 3.4482758620689653, "targetValueTwo": 3.5714285714285716},
          {"type": "ExecutableStepDTO", "stepOrder": 4, "stepType": {"stepTypeKey": "recovery"},
           "endCondition": {"conditionTypeKey": "time"}, "endConditionValue": 120.0,
           "targetType": {"workoutTargetTypeKey": "no.target"}}]},
       {"type": "ExecutableStepDTO", "stepOrder": 5, "stepType": {"stepTypeKey": "cooldown"},
        "endCondition": {"conditionTypeKey": "time"}, "endConditionValue": 600.0,
        "targetType": {"workoutTargetTypeKey": "no.target"}}]}]}
    """.trimIndent()

    private fun workout() = GarminWorkoutParser.parse(JSONObject(sample))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun capture(activity: Activity, name: String) {
        idle()
        val dm = activity.resources.displayMetrics
        val v = activity.window.decorView
        v.measure(
            MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dm.heightPixels, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setupApp() {
        val app = App.instance
        app.prefs.diRefreshToken = "test"
        app.prefs.garminDisplayName = "Thomas"
        val today = LocalDate.now()
        app.cachedScheduled = listOf(
            WorkoutSummary(1, 11, null, "workout", "Fractionné 5 x 1 km", today, "running", 2940.0),
            WorkoutSummary(2, 12, null, "workout", "Footing 45 min", today.plusDays(2), "running", 2700.0),
            WorkoutSummary(3, 13, null, "workout", "Seuil 3 x 10 min", today.plusDays(4), "running", 3300.0),
            WorkoutSummary(4, 14, null, "workout", "Sortie longue tapis", today.plusDays(6), "running", 4500.0),
        )
    }

    private fun runScreen(name: String) {
        val app = App.instance
        val w = workout()
        val plan = SpeedPlanner.plan(w, app.prefs.speedSettings)
        val s = RunSession(app, w, plan)
        app.session = s
        val act = Robolectric.buildActivity(RunActivity::class.java).setup().get()
        s.start()
        // 10 min d'échauffement puis 100 s de fractionné, avec les données du tapis.
        var dist = 0
        for (i in 0 until 700) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val warm = i < 600
            dist += if (warm) 2 else 3
            s.onData(Ftms.TreadmillData(speedKmh = if (warm) 9.0 else 12.6, distanceM = dist))
        }
        capture(act, name)
        s.release()
        app.session = null
    }

    /** Bandeau dessiné en bas d'un écran factice qui figure une vidéo Kinomap ou Netflix. */
    private fun captureBar(name: String) {
        val app = App.instance
        val w = workout()
        val s = RunSession(app, w, SpeedPlanner.plan(w, app.prefs.speedSettings))
        s.start()
        var dist = 0
        for (i in 0 until 700) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val warm = i < 600
            dist += if (warm) 2 else 3
            s.onData(Ftms.TreadmillData(speedKmh = if (warm) 9.0 else 12.6, distanceM = dist))
        }
        val bar = BarView(ContextThemeWrapper(app, R.style.AppTheme)) {}
        bar.bind(s)
        val dm = app.resources.displayMetrics
        bar.measure(
            MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        bar.layout(0, 0, dm.widthPixels, bar.measuredHeight)
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat(),
                0xFF2B4A63.toInt(), 0xFF0E1A24.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), p)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; textSize = dm.density * 28 }
        c.drawText("Vidéo Kinomap ou Netflix", dm.density * 32, dm.density * 56, label)
        c.save()
        c.translate(0f, (dm.heightPixels - bar.measuredHeight).toFloat())
        bar.draw(c)
        c.restore()
        val dir = File("build/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        s.release()
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land-hdpi")
    fun bandeauTablette() {
        captureBar("tablette-bandeau")
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-xxhdpi")
    fun bandeauTelephone() {
        captureBar("telephone-bandeau")
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land-hdpi")
    fun tablette() {
        setupApp()
        capture(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "tablette-accueil")
        App.instance.selectedWorkout = workout()
        capture(Robolectric.buildActivity(PreviewActivity::class.java).setup().get(), "tablette-apercu")
        runScreen("tablette-seance")
        capture(Robolectric.buildActivity(SettingsActivity::class.java).setup().get(), "tablette-reglages")
    }

    @Test
    @Config(qualifiers = "w400dp-h880dp-port-xxhdpi")
    fun telephone() {
        setupApp()
        capture(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "telephone-accueil")
        App.instance.selectedWorkout = workout()
        capture(Robolectric.buildActivity(PreviewActivity::class.java).setup().get(), "telephone-apercu")
        runScreen("telephone-seance")
    }
}

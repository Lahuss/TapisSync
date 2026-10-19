package fr.tapissync.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import fr.tapissync.App
import fr.tapissync.ble.Ftms
import fr.tapissync.ble.TreadmillManager
import fr.tapissync.garmin.GarminException
import fr.tapissync.garmin.LoginActivity
import fr.tapissync.garmin.WorkoutSummary
import fr.tapissync.ui.Ui.addSpaced
import fr.tapissync.workout.Format
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity(), TreadmillManager.Listener {

    private val app get() = App.instance
    private lateinit var root: LinearLayout
    private lateinit var sessionCard: LinearLayout
    private lateinit var sessionText: TextView
    private lateinit var garminStatus: TextView
    private lateinit var garminButton: android.widget.Button
    private lateinit var tmStatus: TextView
    private lateinit var listStatus: TextView
    private lateinit var list: LinearLayout
    private lateinit var tabScheduled: android.widget.Button
    private lateinit var tabLibrary: android.widget.Button
    private var showLibrary = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = Ui.page(this, "TapisSync", "Tes séances Garmin Connect, jouées par le tapis.")

        // Séance en cours
        sessionCard = Ui.card(this).apply {
            background = Ui.rounded(Ui.ACCENT_DARK, 16, this@MainActivity, Ui.ACCENT)
        }
        sessionText = Ui.text(this, "", 16f, bold = true)
        sessionCard.addView(sessionText)
        sessionCard.addSpaced(Ui.button(this, "Revenir à la séance") {
            startActivity(Intent(this, RunActivity::class.java))
        }, this, 10)
        root.addSpaced(sessionCard, this, 16)

        // Garmin
        val g = Ui.card(this)
        g.addView(Ui.text(this, "Garmin Connect", 18f, bold = true))
        garminStatus = Ui.text(this, "", 15f, Ui.MUTED)
        g.addSpaced(garminStatus, this, 4)
        garminButton = Ui.button(this, "Se connecter", primary = false) { onGarminButton() }
        g.addSpaced(garminButton, this, 10)
        root.addSpaced(g, this, 16)

        // Tapis
        val t = Ui.card(this)
        t.addView(Ui.text(this, "Tapis de course", 18f, bold = true))
        tmStatus = Ui.text(this, "", 15f, Ui.MUTED)
        t.addSpaced(tmStatus, this, 4)
        t.addSpaced(Ui.button(this, "Choisir le tapis / tester", primary = false) {
            startActivity(Intent(this, TreadmillActivity::class.java))
        }, this, 10)
        root.addSpaced(t, this, 12)

        // Séances
        root.addSpaced(Ui.text(this, "Séances", 22f, bold = true), this, 24)
        val tabs = Ui.horizontal(this)
        tabScheduled = Ui.button(this, "Planifiées") { showLibrary = false; refreshTabs(); load(false) }
        tabLibrary = Ui.button(this, "Mes entraînements") { showLibrary = true; refreshTabs(); load(false) }
        tabs.addView(tabScheduled, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        tabs.addView(tabLibrary, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        tabs.addView(Ui.button(this, "↻", primary = false) { load(true) },
            Ui.lp(Ui.dp(this, 56), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addSpaced(tabs, this, 10)
        listStatus = Ui.text(this, "", 15f, Ui.MUTED)
        root.addSpaced(listStatus, this, 10)
        list = Ui.vertical(this)
        root.addSpaced(list, this, 4)

        // Pied de page
        val footer = Ui.horizontal(this)
        footer.addView(Ui.button(this, "Réglages", primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        footer.addView(Ui.button(this, "Diagnostic", primary = false) {
            startActivity(Intent(this, TreadmillActivity::class.java).putExtra(TreadmillActivity.EXTRA_DIAG, true))
        }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addSpaced(footer, this, 28)

        refreshTabs()
    }

    override fun onResume() {
        super.onResume()
        app.treadmill.listeners.add(this)
        autoConnectTreadmill()
        refreshStatus()
        val cached = if (showLibrary) app.cachedLibrary else app.cachedScheduled
        if (cached != null) render(cached) else load(false)
    }

    override fun onPause() {
        app.treadmill.listeners.remove(this)
        super.onPause()
    }

    private fun autoConnectTreadmill() {
        val tm = app.treadmill
        val addr = app.prefs.treadmillAddress ?: return
        if (tm.state == TreadmillManager.State.DISCONNECTED && tm.hasPermissions() && tm.isBluetoothOn) {
            tm.connect(addr, app.prefs.treadmillName)
        }
    }

    private fun refreshStatus() {
        val s = app.session
        sessionCard.visibility = if (s != null) View.VISIBLE else View.GONE
        if (s != null) sessionText.text = "Séance en cours : ${s.workout.name}"

        if (app.prefs.isGarminConnected) {
            val name = app.prefs.garminDisplayName
            garminStatus.text = if (name != null) "Connecté ($name)" else "Connecté"
            garminStatus.setTextColor(Ui.OK)
            garminButton.text = "Se déconnecter"
        } else {
            garminStatus.text = "Non connecté. Connecte-toi pour voir tes séances."
            garminStatus.setTextColor(Ui.MUTED)
            garminButton.text = "Se connecter à Garmin"
        }
        updateTreadmillText()
    }

    private fun updateTreadmillText() {
        val tm = app.treadmill
        val name = tm.deviceName ?: app.prefs.treadmillName
        tmStatus.text = when {
            name == null -> "Aucun tapis choisi."
            tm.state == TreadmillManager.State.READY && !tm.hasControlPoint ->
                "$name : connecté, mais pilotage indisponible (${tm.stateMessage ?: "voir Diagnostic"})."
            tm.state == TreadmillManager.State.READY -> {
                val v = tm.lastData?.speedKmh?.let { " · ${Format.kmh(it)} km/h" } ?: ""
                "$name : connecté$v"
            }
            else -> "$name : ${tm.state.label.lowercase()}${tm.stateMessage?.let { " ($it)" } ?: ""}"
        }
        tmStatus.setTextColor(
            when {
                tm.state == TreadmillManager.State.READY && tm.hasControlPoint -> Ui.OK
                tm.state == TreadmillManager.State.READY -> Ui.WARN
                else -> Ui.MUTED
            },
        )
    }

    private fun onGarminButton() {
        if (app.prefs.isGarminConnected) {
            AlertDialog.Builder(this)
                .setTitle("Se déconnecter de Garmin ?")
                .setMessage("Il faudra te reconnecter pour voir tes séances.")
                .setPositiveButton("Se déconnecter") { _, _ ->
                    app.prefs.clearTokens()
                    app.cachedScheduled = null
                    app.cachedLibrary = null
                    list.removeAllViews()
                    refreshStatus()
                    listStatus.text = ""
                }
                .setNegativeButton("Annuler", null)
                .show()
        } else {
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
        }
    }

    @Deprecated("API Activity simple, sans AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            refreshStatus()
            load(true)
        }
    }

    private fun refreshTabs() {
        tabScheduled.background = Ui.rounded(if (!showLibrary) Ui.ACCENT else Ui.SURFACE_2, 12, this, if (!showLibrary) null else Ui.BORDER)
        tabScheduled.setTextColor(if (!showLibrary) 0xFF1A0E07.toInt() else Ui.TEXT)
        tabLibrary.background = Ui.rounded(if (showLibrary) Ui.ACCENT else Ui.SURFACE_2, 12, this, if (showLibrary) null else Ui.BORDER)
        tabLibrary.setTextColor(if (showLibrary) 0xFF1A0E07.toInt() else Ui.TEXT)
    }

    private fun load(force: Boolean) {
        if (!app.prefs.isGarminConnected) {
            list.removeAllViews()
            listStatus.text = "Connecte-toi à Garmin pour afficher tes séances."
            return
        }
        val library = showLibrary
        val cached = if (library) app.cachedLibrary else app.cachedScheduled
        if (!force && cached != null) {
            render(cached)
            return
        }
        if (loading) return
        loading = true
        list.removeAllViews()
        listStatus.text = "Chargement…"
        listStatus.setTextColor(Ui.MUTED)
        app.io.execute {
            try {
                val today = LocalDate.now()
                val items = if (library) app.garmin.libraryWorkouts()
                else app.garmin.scheduledWorkouts(today.minusDays(3), today.plusDays(21))
                runOnUiThread {
                    loading = false
                    if (library) app.cachedLibrary = items else app.cachedScheduled = items
                    if (library == showLibrary) render(items)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    listStatus.text = e.message ?: "Erreur de chargement"
                    listStatus.setTextColor(Ui.ERROR)
                    if (e is GarminException && e.needLogin) refreshStatus()
                }
            }
        }
    }

    private fun render(items: List<WorkoutSummary>) {
        list.removeAllViews()
        listStatus.setTextColor(Ui.MUTED)
        listStatus.text = when {
            items.isEmpty() && !showLibrary -> "Aucune séance de course planifiée entre il y a 3 jours et dans 3 semaines."
            items.isEmpty() -> "Aucune séance de course dans « Mes entraînements »."
            else -> ""
        }
        val today = LocalDate.now()
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE)
        for (w in items) {
            val row = Ui.card(this).apply { isClickable = true }
            val top = Ui.horizontal(this)
            val d = w.date
            val dateLabel = when (d) {
                null -> null
                today -> "Aujourd'hui"
                today.plusDays(1) -> "Demain"
                today.minusDays(1) -> "Hier"
                else -> d.format(fmt)
            }
            if (dateLabel != null) {
                top.addView(Ui.text(this, dateLabel, 13f, if (w.date == today) Ui.ACCENT else Ui.MUTED, bold = true))
            }
            row.addView(top)
            row.addSpaced(Ui.text(this, w.name, 17f, bold = true), this, if (dateLabel != null) 2 else 0)
            w.estimatedSeconds?.let { row.addSpaced(Ui.text(this, "Environ ${Format.duration(it)}", 14f, Ui.MUTED), this, 2) }
            row.setOnClickListener { open(w) }
            list.addSpaced(row, this, 10)
        }
    }

    private fun open(w: WorkoutSummary) {
        val dialog = AlertDialog.Builder(this).setMessage("Chargement de « ${w.name} »…").setCancelable(false).show()
        app.io.execute {
            try {
                val workout = app.garmin.workout(w)
                runOnUiThread {
                    dialog.dismiss()
                    app.selectedWorkout = workout
                    startActivity(Intent(this, PreviewActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Séance illisible")
                        .setMessage(e.message ?: "Erreur inconnue")
                        .setPositiveButton("OK", null)
                        .show()
                    if (e is GarminException && e.needLogin) refreshStatus()
                }
            }
        }
    }

    // Mises à jour du tapis
    override fun onState(state: TreadmillManager.State, message: String?) = updateTreadmillText()
    override fun onData(data: Ftms.TreadmillData) = updateTreadmillText()

    companion object {
        private const val REQ_LOGIN = 1
    }
}

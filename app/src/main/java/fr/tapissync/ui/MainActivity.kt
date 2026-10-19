package fr.tapissync.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

/**
 * Accueil. Sur tablette en paysage : à gauche l'état (Garmin, tapis), à droite les séances.
 * Sur téléphone : tout en une colonne.
 */
class MainActivity : Activity(), TreadmillManager.Listener {

    private val app get() = App.instance
    private lateinit var sessionCard: LinearLayout
    private lateinit var sessionText: TextView
    private lateinit var garminStatus: Ui.StatusLine
    private lateinit var garminButton: Button
    private lateinit var tmStatus: Ui.StatusLine
    private lateinit var tmSpeed: TextView
    private lateinit var listStatus: TextView
    private lateinit var list: LinearLayout
    private lateinit var tabScheduled: Button
    private lateinit var tabLibrary: Button
    private var showLibrary = false
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wide = Ui.isWide(this)
        val pad = Ui.dp(this, if (wide) 32 else 22)

        val left: LinearLayout
        val right: LinearLayout
        if (wide) {
            val split = Ui.horizontal(this).apply {
                setBackgroundColor(Ui.BG)
                gravity = Gravity.TOP
            }
            left = Ui.vertical(this).apply { setPadding(pad, pad, pad / 2, pad) }
            right = Ui.vertical(this).apply { setPadding(pad / 2, pad, pad, pad) }
            split.addView(scroll(left), Ui.lp(Ui.dp(this, 420), ViewGroup.LayoutParams.MATCH_PARENT))
            split.addView(View(this).apply { setBackgroundColor(0xFF1A1A1A.toInt()) }, Ui.lp(Ui.dp(this, 1), ViewGroup.LayoutParams.MATCH_PARENT))
            split.addView(scroll(right), Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(split)
        } else {
            val col = Ui.vertical(this).apply { setPadding(pad, pad, pad, pad * 2) }
            setContentView(scroll(col))
            left = col
            right = col
        }

        // En-tête
        left.addView(Ui.header(this, "TapisSync", 46f))
        left.addSpaced(Ui.text(this, "Tes séances Garmin Connect, jouées par le tapis.", 16f, Ui.MUTED), this, 6)

        // Séance en cours
        sessionCard = Ui.accentCard(this)
        sessionText = Ui.label(this, "", 20f)
        sessionCard.addView(sessionText)
        sessionCard.addSpaced(Ui.button(this, "Revenir à la séance") {
            startActivity(Intent(this, RunActivity::class.java))
        }, this, 12)
        left.addSpaced(sessionCard, this, 24)

        // Garmin
        val g = Ui.card(this)
        g.addView(Ui.label(this, "Garmin Connect", 22f))
        garminStatus = Ui.status(this)
        g.addSpaced(garminStatus, this, 8)
        garminButton = Ui.button(this, "Se connecter", primary = false) { onGarminButton() }
        g.addSpaced(garminButton, this, 14)
        left.addSpaced(g, this, 20)

        // Tapis
        val t = Ui.card(this)
        val tHead = Ui.horizontal(this)
        tHead.addView(Ui.label(this, "Tapis", 22f), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tmSpeed = Ui.display(this, "", 30f, Ui.ACCENT, glow = true)
        tHead.addView(tmSpeed)
        t.addView(tHead)
        tmStatus = Ui.status(this)
        t.addSpaced(tmStatus, this, 8)
        t.addSpaced(Ui.button(this, "Choisir ou tester le tapis", primary = false) {
            startActivity(Intent(this, TreadmillActivity::class.java))
        }, this, 14)
        left.addSpaced(t, this, 12)

        // Séances
        val head = Ui.horizontal(this)
        head.addView(Ui.section(this, "Séances"), Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.button(this, "Actualiser", primary = false) { load(true) },
            Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        right.addSpaced(head, this, if (wide) 0 else 32)

        val tabs = Ui.horizontal(this)
        tabScheduled = Ui.button(this, "Planifiées") { showLibrary = false; refreshTabs(); load(false) }
        tabLibrary = Ui.button(this, "Mes entraînements") { showLibrary = true; refreshTabs(); load(false) }
        tabs.addView(tabScheduled, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(Ui.gap(this))
        tabs.addView(tabLibrary, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        right.addSpaced(tabs, this, 14)
        listStatus = Ui.text(this, "", 16f, Ui.MUTED)
        right.addSpaced(listStatus, this, 12)
        list = Ui.vertical(this)
        right.addSpaced(list, this, 0)

        // Réglages et diagnostic
        val footer = Ui.horizontal(this)
        footer.addView(Ui.button(this, "Réglages", primary = false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(Ui.gap(this))
        footer.addView(Ui.button(this, "Diagnostic", primary = false) {
            startActivity(Intent(this, TreadmillActivity::class.java).putExtra(TreadmillActivity.EXTRA_DIAG, true))
        }, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        left.addSpaced(footer, this, if (wide) 20 else 32)

        refreshTabs()
    }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(content)
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
            garminStatus.set(if (name != null) "Connecté en tant que $name" else "Connecté", Ui.OK)
            garminButton.text = "Se déconnecter"
        } else {
            garminStatus.set("Non connecté. Connecte-toi pour voir tes séances.", Ui.MUTED)
            garminButton.text = "Se connecter à Garmin"
        }
        updateTreadmillText()
    }

    private fun updateTreadmillText() {
        val tm = app.treadmill
        val name = tm.deviceName ?: app.prefs.treadmillName
        val ready = tm.state == TreadmillManager.State.READY
        when {
            name == null -> tmStatus.set("Aucun tapis choisi.", Ui.MUTED)
            ready && !tm.hasControlPoint ->
                tmStatus.set("$name connecté, mais pas pilotable (${tm.stateMessage ?: "voir Diagnostic"}).", Ui.WARN)
            ready -> tmStatus.set("$name connecté", Ui.OK)
            else -> tmStatus.set(
                "$name : ${tm.state.label.lowercase()}${tm.stateMessage?.let { ", $it" } ?: ""}",
                if (tm.state == TreadmillManager.State.DISCONNECTED) Ui.MUTED else Ui.WARN,
            )
        }
        val v = tm.lastData?.speedKmh
        tmSpeed.text = if (ready && v != null && v > 0.05) "${Format.kmh(v)} km/h" else ""
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
        Ui.styleTab(tabScheduled, !showLibrary)
        Ui.styleTab(tabLibrary, showLibrary)
    }

    private fun load(force: Boolean) {
        if (!app.prefs.isGarminConnected) {
            list.removeAllViews()
            listStatus.text = "Connecte-toi à Garmin pour afficher tes séances."
            listStatus.setTextColor(Ui.MUTED)
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
        listStatus.text = "Chargement des séances…"
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
                    listStatus.text = e.message ?: "Chargement impossible. Vérifie ta connexion internet puis touche Actualiser."
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
            items.isEmpty() && !showLibrary ->
                "Aucune séance de course planifiée entre il y a 3 jours et dans 3 semaines. Planifie-en une dans Garmin Connect, puis touche Actualiser."
            items.isEmpty() -> "Aucune séance de course dans « Mes entraînements »."
            else -> ""
        }
        listStatus.visibility = if (listStatus.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val today = LocalDate.now()
        val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.FRANCE)
        val dateFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)
        for (w in items) {
            val d = w.date
            val isToday = d == today
            val row = Ui.horizontal(this).apply {
                background = if (isToday) Ui.accentBackground(this@MainActivity) else Ui.rounded(Ui.SURFACE, 4, this@MainActivity)
                val p = Ui.dp(this@MainActivity, 16)
                setPadding(p + if (isToday) Ui.dp(this@MainActivity, 4) else 0, p, p, p)
                isClickable = true
                isFocusable = true
                setOnClickListener { open(w) }
            }
            if (d != null) {
                val dateBlock = Ui.vertical(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
                dateBlock.addView(Ui.display(this, d.dayOfMonth.toString(), 34f, if (isToday) Ui.ACCENT else Ui.TEXT, glow = isToday))
                dateBlock.addView(Ui.label(this, d.format(dayFmt), 15f, Ui.MUTED))
                row.addView(dateBlock, Ui.lp(Ui.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = Ui.dp(this@MainActivity, 16)
                })
            }
            val info = Ui.vertical(this)
            info.addView(Ui.label(this, w.name, 21f))
            val sub = buildString {
                if (d != null) {
                    append(
                        when (d) {
                            today -> "Aujourd'hui"
                            today.plusDays(1) -> "Demain"
                            today.minusDays(1) -> "Hier"
                            else -> d.format(dateFmt).replaceFirstChar { it.uppercase() }
                        },
                    )
                }
                w.estimatedSeconds?.let {
                    if (isNotEmpty()) append(", ")
                    append("environ ${Format.duration(it)}")
                }
            }
            if (sub.isNotEmpty()) info.addSpaced(Ui.text(this, sub, 15f, Ui.MUTED), this, 2)
            row.addView(info, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

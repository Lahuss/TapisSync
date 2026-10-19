package fr.tapissync.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.tapissync.App
import fr.tapissync.AppLog
import fr.tapissync.ble.Ftms
import fr.tapissync.ble.TreadmillManager
import fr.tapissync.ui.Ui.addSpaced
import fr.tapissync.workout.Format

/** Choix du tapis, test du pilotage et diagnostic à m'envoyer en cas de souci. */
class TreadmillActivity : Activity(), TreadmillManager.Listener {

    private val app get() = App.instance
    private val tm get() = app.treadmill
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var info: TextView
    private lateinit var results: LinearLayout
    private lateinit var scanButton: android.widget.Button
    private lateinit var logView: TextView
    private val found = LinkedHashMap<String, TreadmillManager.ScanItem>()
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.page(this, "Tapis", "Connexion Bluetooth, test du pilotage et diagnostic.")

        val card = Ui.card(this)
        status = Ui.text(this, "", 16f, bold = true)
        card.addView(status)
        live = Ui.text(this, "", 15f, Ui.MUTED)
        card.addSpaced(live, this, 4)
        info = Ui.text(this, "", 13f, Ui.MUTED)
        card.addSpaced(info, this, 6)
        root.addSpaced(card, this, 16)

        scanButton = Ui.button(this, "Rechercher le tapis") { toggleScan() }
        root.addSpaced(scanButton, this, 12)
        root.addSpaced(
            Ui.text(this, "Allume le tapis et quitte l'appli Reebok ou Kinomap : un tapis n'accepte en général qu'une connexion à la fois.", 13f, Ui.MUTED),
            this, 6,
        )
        results = Ui.vertical(this)
        root.addSpaced(results, this, 6)

        // Test du pilotage
        root.addSpaced(Ui.section(this, "Tester le pilotage"), this, 24)
        root.addSpaced(
            Ui.text(this, "Monte sur les rebords du tapis et garde la clé de sécurité attachée avant de lancer un test.", 14f, Ui.WARN),
            this, 4,
        )
        val row1 = Ui.horizontal(this)
        row1.addView(Ui.button(this, "Démarrer à 3 km/h") {
            tm.requestControl(); tm.start(); tm.setSpeed(3.0)
        }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        row1.addView(Ui.button(this, "+1 km/h", primary = false) {
            val v = (tm.lastData?.speedKmh ?: 3.0) + 1.0
            tm.setSpeed(v.coerceAtMost(8.0))
        }, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addSpaced(row1, this, 10)
        val row2 = Ui.horizontal(this)
        row2.addView(Ui.button(this, "Pause", primary = false) { tm.pause() },
            Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        row2.addView(Ui.button(this, "Arrêt", primary = false) { tm.stopBelt() },
            Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addSpaced(row2, this, 8)

        // Diagnostic
        root.addSpaced(Ui.section(this, "Diagnostic"), this, 28)
        root.addSpaced(Ui.text(this, "Si quelque chose ne marche pas, partage ce journal pour que je corrige l'appli.", 14f, Ui.MUTED), this, 4)
        val row3 = Ui.horizontal(this)
        row3.addView(Ui.button(this, "Partager le journal", primary = false) { share() },
            Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(View(this), Ui.lp(Ui.dp(this, 8), 1))
        row3.addView(Ui.button(this, "Copier", primary = false) { copy() },
            Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addSpaced(row3, this, 10)
        root.addSpaced(Ui.button(this, "Oublier ce tapis", primary = false) {
            tm.disconnect()
            app.prefs.treadmillAddress = null
            app.prefs.treadmillName = null
            refresh()
        }, this, 8)
        logView = Ui.text(this, "", 11f, Ui.MUTED, mono = true).apply { setTextIsSelectable(true) }
        root.addSpaced(logView, this, 12)
    }

    override fun onResume() {
        super.onResume()
        tm.listeners.add(this)
        ensurePermissions()
        refresh()
    }

    override fun onPause() {
        tm.listeners.remove(this)
        if (scanning) toggleScan()
        super.onPause()
    }

    private fun ensurePermissions(): Boolean {
        if (tm.hasPermissions()) return true
        val perms = tm.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) perms += android.Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (!tm.hasPermissions()) {
            AlertDialog.Builder(this)
                .setTitle("Autorisation nécessaire")
                .setMessage("Sans l'autorisation « Appareils à proximité » (Bluetooth), l'appli ne peut pas parler au tapis.")
                .setPositiveButton("Ouvrir les réglages") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Plus tard", null)
                .show()
        }
        refresh()
    }

    private fun toggleScan() {
        if (scanning) {
            tm.stopScan()
            scanning = false
            scanButton.text = "Rechercher le tapis"
            return
        }
        if (!ensurePermissions()) return
        if (!tm.isBluetoothOn) {
            Toast.makeText(this, "Active le Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        found.clear()
        results.removeAllViews()
        val err = tm.startScan { item ->
            val prev = found[item.address]
            found[item.address] = item
            if (prev == null || prev.hasFtms != item.hasFtms) renderResults()
        }
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            return
        }
        scanning = true
        scanButton.text = "Arrêter la recherche"
        scanButton.postDelayed({ if (scanning) toggleScan() }, 20000)
    }

    private fun renderResults() {
        results.removeAllViews()
        val items = found.values.sortedWith(compareByDescending<TreadmillManager.ScanItem> { it.hasFtms }.thenByDescending { it.rssi })
        for (item in items) {
            val row = Ui.card(this).apply { isClickable = true }
            row.addView(Ui.text(this, item.name, 16f, bold = true))
            row.addView(
                Ui.text(
                    this,
                    (if (item.hasFtms) "Tapis compatible (FTMS) · " else "") + "${item.address} · signal ${item.rssi} dBm",
                    13f, if (item.hasFtms) Ui.OK else Ui.MUTED,
                ),
            )
            row.setOnClickListener {
                if (scanning) toggleScan()
                app.prefs.treadmillAddress = item.address
                app.prefs.treadmillName = item.name
                tm.connect(item.address, item.name)
                results.removeAllViews()
                refresh()
            }
            results.addSpaced(row, this, 8)
        }
    }

    private fun refresh() {
        val name = tm.deviceName ?: app.prefs.treadmillName ?: "Aucun tapis"
        status.text = "$name · ${tm.state.label}"
        status.setTextColor(
            when {
                tm.state == TreadmillManager.State.READY && tm.hasControlPoint -> Ui.OK
                tm.state == TreadmillManager.State.READY -> Ui.WARN
                else -> Ui.TEXT
            },
        )
        val d = tm.lastData
        live.text = if (d == null) (tm.stateMessage ?: "Pas de données reçues pour l'instant.") else buildString {
            append("Vitesse ${d.speedKmh?.let { Format.kmh(it) } ?: "—"} km/h")
            d.inclinePct?.let { append(" · pente ${Format.num1(it)} %") }
            d.distanceM?.let { append(" · ${Format.distance(it.toDouble())}") }
            d.heartRate?.let { append(" · $it bpm") }
        }
        info.text = buildString {
            if (tm.state == TreadmillManager.State.READY) {
                append(if (tm.hasControlPoint) "Pilotage FTMS disponible" else "Pas de pilotage FTMS")
                tm.features?.let { append(" · vitesse pilotable : ${if (it.speedTarget) "oui" else "non annoncé"}") }
                tm.speedRange?.let { append("\nPlage : ${Format.kmh(it.minKmh)}–${Format.kmh(it.maxKmh)} km/h, pas de ${it.incrementKmh} km/h") }
                tm.inclineRange?.let { append("\nPente : ${it.first}–${it.second} %") }
                tm.lastResponse?.let { append("\nDernière réponse du tapis : $it") }
            }
            if (tm.discoveredServices.isNotEmpty()) {
                append("\n\nServices Bluetooth :\n")
                append(tm.discoveredServices.joinToString("\n"))
            }
        }
        logView.text = AppLog.dump().lines().takeLast(150).joinToString("\n")
    }

    private fun report(): String = buildString {
        append("TapisSync — diagnostic\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Tapis : ${tm.deviceName ?: "—"} · ${tm.state.label}\n\n")
        append(info.text)
        append("\n\nJournal :\n")
        append(AppLog.dump())
    }

    private fun share() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Diagnostic TapisSync")
                    .putExtra(Intent.EXTRA_TEXT, report()),
                "Partager le diagnostic",
            ),
        )
    }

    private fun copy() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Diagnostic TapisSync", report()))
        Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
    }

    override fun onState(state: TreadmillManager.State, message: String?) = refresh()
    override fun onData(data: Ftms.TreadmillData) = refresh()
    override fun onResponse(response: Ftms.ControlResponse) = refresh()
    override fun onStatus(event: Ftms.StatusEvent) = refresh()

    companion object {
        const val EXTRA_DIAG = "diag"
        private const val REQ_PERMS = 7
    }
}

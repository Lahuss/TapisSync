package fr.tapissync.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import fr.tapissync.App
import fr.tapissync.ui.Ui.addSpaced
import fr.tapissync.workout.Format
import fr.tapissync.workout.SpeedPlanner
import fr.tapissync.workout.SpeedSettings

class SettingsActivity : Activity() {

    private val prefs get() = App.instance.prefs
    private lateinit var seek: SeekBar
    private lateinit var seekLabel: TextView
    private lateinit var example: TextView
    private val presetButtons = mutableListOf<Pair<android.widget.Button, Int>>()
    private lateinit var correction: EditText
    private lateinit var warmup: EditText
    private lateinit var active: EditText
    private lateinit var recovery: EditText
    private lateinit var rest: EditText
    private lateinit var cooldown: EditText
    private lateinit var minKmh: EditText
    private lateinit var maxKmh: EditText
    private lateinit var endKmh: EditText
    private lateinit var incline: EditText
    private lateinit var bar: Switch
    private lateinit var overlayStatus: Ui.StatusLine
    private lateinit var overlayButton: android.widget.Button
    private lateinit var voice: Switch
    private lateinit var beep: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = prefs.speedSettings
        val root = Ui.page(this, "Réglages", "Comment les allures Garmin deviennent des vitesses tapis.")

        // Position dans la plage
        val c1 = Ui.card(this)
        c1.addView(Ui.label(this, "Où viser dans la plage d'allure", 21f))
        c1.addSpaced(Ui.text(this, "Garmin donne une plage (ex. 4:40–4:50 /km). Choisis où l'appli se place dedans.", 14f, Ui.MUTED), this, 4)
        seekLabel = Ui.text(this, "", 15f, Ui.ACCENT, bold = true)
        c1.addSpaced(seekLabel, this, 10)
        seek = SeekBar(this).apply {
            max = 100
            progress = Math.round(s.rangePosition * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateExample()
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        c1.addSpaced(seek, this, 6)
        val presets = Ui.horizontal(this)
        listOf("Lent" to 0, "Milieu" to 50, "Rapide" to 100).forEachIndexed { i, (label, v) ->
            if (i > 0) presets.addView(android.view.View(this), Ui.lp(Ui.dp(this, 8), 1))
            val b = Ui.button(this, label, primary = false) { seek.progress = v }
            presetButtons += b to v
            presets.addView(b, Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        c1.addSpaced(presets, this, 8)
        example = Ui.text(this, "", 14f, Ui.MUTED)
        c1.addSpaced(example, this, 10)
        root.addSpaced(c1, this, 16)

        // Correction
        val c2 = Ui.card(this)
        c2.addView(Ui.label(this, "Correction de vitesse (%)", 21f))
        c2.addSpaced(Ui.text(this, "Si ta montre mesure une allure plus lente que celle du tapis, mets par exemple +2. S'applique seulement aux étapes avec une allure Garmin.", 14f, Ui.MUTED), this, 4)
        correction = Ui.numberField(this, s.correctionPct, signed = true)
        c2.addSpaced(correction, this, 8)
        root.addSpaced(c2, this, 12)

        // Vitesses par défaut
        val c3 = Ui.card(this)
        c3.addView(Ui.label(this, "Vitesses sans cible d'allure (km/h)", 21f))
        c3.addSpaced(Ui.text(this, "Utilisées pour les étapes Garmin sans allure (cible cardio, aucune cible…).", 14f, Ui.MUTED), this, 4)
        warmup = field(c3, "Échauffement", s.warmupKmh)
        active = field(c3, "Course", s.activeKmh)
        recovery = field(c3, "Récupération", s.recoveryKmh)
        rest = field(c3, "Repos", s.restKmh)
        cooldown = field(c3, "Retour au calme", s.cooldownKmh)
        root.addSpaced(c3, this, 12)

        // Sécurité
        val c4 = Ui.card(this)
        c4.addView(Ui.label(this, "Sécurité et fin de séance", 21f))
        minKmh = field(c4, "Vitesse minimale (km/h)", s.minKmh)
        maxKmh = field(c4, "Vitesse maximale (km/h)", s.maxKmh)
        endKmh = field(c4, "Marche après la séance (km/h, 0 = arrêt)", prefs.endSpeedKmh)
        incline = field(c4, "Pente au départ (%, vide = ne pas toucher)", prefs.startInclinePct, signed = true)
        root.addSpaced(c4, this, 12)

        // Pendant la séance
        val c7 = Ui.card(this)
        c7.addView(Ui.label(this, "Pendant la séance", 21f))
        bar = Switch(this).apply {
            text = "Bandeau en bas de l'écran quand tu ouvres une autre appli"
            setTextColor(Ui.TEXT)
            textSize = 16f
            isChecked = prefs.barEnabled
        }
        c7.addSpaced(bar, this, 12)
        c7.addSpaced(
            Ui.text(this, "Le bandeau reste par-dessus Kinomap, Netflix ou YouTube et laisse le reste de l'écran utilisable. Sans lui, l'écran de séance passe en petite fenêtre flottante.", 14f, Ui.MUTED),
            this, 4,
        )
        overlayStatus = Ui.status(this)
        c7.addSpaced(overlayStatus, this, 12)
        overlayButton = Ui.button(this, "Autoriser l'affichage par-dessus les autres applis", primary = false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        c7.addSpaced(overlayButton, this, 10)
        c7.addSpaced(
            Ui.text(this, "Si l'option est grisée : Paramètres › Applis › TapisSync › menu ⋮ › Autoriser les paramètres restreints, puis réessaie.", 14f, Ui.MUTED),
            this, 6,
        )
        root.addSpaced(c7, this, 12)

        // Sons
        val c5 = Ui.card(this)
        voice = Switch(this).apply {
            text = "Annonces vocales à chaque étape"
            setTextColor(Ui.TEXT)
            isChecked = prefs.voiceEnabled
        }
        c5.addView(voice)
        beep = Switch(this).apply {
            text = "Bips avant chaque changement"
            setTextColor(Ui.TEXT)
            isChecked = prefs.beepEnabled
        }
        c5.addSpaced(beep, this, 8)
        root.addSpaced(c5, this, 12)

        root.addSpaced(Ui.button(this, "Enregistrer") {
            if (save()) {
                Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, this, 20)
        updateExample()
    }

    override fun onResume() {
        super.onResume()
        val ok = Settings.canDrawOverlays(this)
        overlayStatus.set(
            if (ok) "Affichage par-dessus les autres applis autorisé" else "Affichage par-dessus les autres applis non autorisé",
            if (ok) Ui.OK else Ui.WARN,
        )
        overlayButton.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun field(
        parent: LinearLayout, label: String, value: Double,
        signed: Boolean = false, decimals: Boolean = true,
    ): EditText {
        val row = Ui.horizontal(this)
        row.addView(Ui.text(this, label, 15f), Ui.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val f = Ui.numberField(this, value, decimals = decimals, signed = signed)
        row.addView(f, Ui.lp(Ui.dp(this, 110), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addSpaced(row, this, 10)
        return f
    }

    private fun updateExample() {
        val pos = seek.progress / 100.0
        presetButtons.forEach { (b, v) -> Ui.styleTab(b, seek.progress == v) }
        seekLabel.text = when (seek.progress) {
            0 -> "Bout lent de la plage"
            50 -> "Milieu de la plage"
            100 -> "Bout rapide de la plage"
            else -> "${seek.progress} % vers le rapide"
        }
        // Exemple : plage 4:40–4:50 /km
        val v = SpeedPlanner.speedInRange(1000.0 / 290.0, 1000.0 / 280.0, pos)
        example.text = "Exemple : 4:40–4:50 /km → ${Format.pace(v)} /km, soit ${Format.kmh(Math.round(v * 10) / 10.0)} km/h"
    }

    private fun num(e: EditText, label: String, min: Double, max: Double): Double? {
        val v = Ui.parseNumber(e.text)
        if (v == null || v < min || v > max) {
            e.error = "$label : entre ${Format.num1(min)} et ${Format.num1(max)}"
            e.requestFocus()
            return null
        }
        return v
    }

    private fun save(): Boolean {
        val corr = num(correction, "Correction", -20.0, 20.0) ?: return false
        val w = num(warmup, "Échauffement", 0.5, 25.0) ?: return false
        val a = num(active, "Course", 0.5, 25.0) ?: return false
        val r = num(recovery, "Récupération", 0.5, 25.0) ?: return false
        val re = num(rest, "Repos", 0.5, 25.0) ?: return false
        val cd = num(cooldown, "Retour au calme", 0.5, 25.0) ?: return false
        val mn = num(minKmh, "Minimum", 0.5, 10.0) ?: return false
        val mx = num(maxKmh, "Maximum", 3.0, 25.0) ?: return false
        if (mx <= mn) {
            maxKmh.error = "Le maximum doit dépasser le minimum"
            return false
        }
        val end = num(endKmh, "Marche", 0.0, 8.0) ?: return false
        val inc = if (incline.text.isNullOrBlank()) Double.NaN else (num(incline, "Pente", -5.0, 20.0) ?: return false)
        prefs.speedSettings = SpeedSettings(
            rangePosition = seek.progress / 100.0,
            correctionPct = corr,
            warmupKmh = w, activeKmh = a, recoveryKmh = r, restKmh = re, cooldownKmh = cd,
            minKmh = mn, maxKmh = mx,
        )
        prefs.endSpeedKmh = end
        prefs.startInclinePct = inc
        prefs.voiceEnabled = voice.isChecked
        prefs.beepEnabled = beep.isChecked
        prefs.barEnabled = bar.isChecked
        return true
    }
}

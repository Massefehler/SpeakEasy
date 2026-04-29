package com.speakeasy.intercom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.speakeasy.intercom.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }
    private var service: IntercomService? = null
    private var bound = false
    private var discovery: PeerDiscovery? = null
    private var searchSheet: BottomSheetDialog? = null

    // Sanft abfallende Peak-Hold-Werte für die VU-Balken.
    @Volatile private var smoothSent = 0f
    @Volatile private var smoothRecv = 0f

    private val dimHandler = Handler(Looper.getMainLooper())
    private val dimToLow = Runnable {
        applyBrightness(BRIGHTNESS_DIM)
        dimHandler.postDelayed(lightsOff, autoOffDelayMs())
    }
    private val lightsOff = Runnable {
        applyBrightness(BRIGHTNESS_OFF)
        // KEEP_SCREEN_ON loslassen, sonst hält Android den Bildschirm trotz
        // Helligkeit 0 weiter aktiv und das Panel bleibt sichtbar.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var lastLogoTapMs = 0L
    private var logoTapCount = 0

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as IntercomService.LocalBinder).service
            bound = true
            service?.stateListener = { onStateChanged() }
            service?.levelListener = { sent, recv -> onLevelChanged(sent, recv) }
            service?.rttListener = { ms -> onRttChanged(ms) }
            service?.peerBatteryListener = { pct -> onPeerBatteryChanged(pct) }
            onStateChanged()
            // Letzten bekannten Wert sofort anzeigen, falls schon vorher empfangen.
            service?.peerBatteryPercent?.takeIf { it in 0..100 }?.let { onPeerBatteryChanged(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Status wird beim nächsten Klick erneut geprüft. */ }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // resultCode > 0 = bewilligte Sichtbarkeitsdauer in Sekunden, RESULT_CANCELED = abgelehnt.
        if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(this, R.string.must_allow_discoverable, Toast.LENGTH_LONG).show()
        } else {
            beginPeerSearch()
        }
    }

    @Volatile private var onboardingInProgress = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            handleDeniedPermissions(result.filterValues { !it }.keys)
        }
        if (onboardingInProgress) {
            // Erst-Anleitung als nächster Schritt der Onboarding-Kette.
            onPermissionsResolved()
        }
    }

    /**
     * Wenn der User eine Permission ablehnt, zeigt Android beim 2. Anlauf keinen
     * System-Dialog mehr. Wir reagieren mit einer eigenen Erklärung + Direkt-
     * Sprung in die App-Settings, damit die App nicht stumm-funktionslos bleibt.
     */
    private fun handleDeniedPermissions(denied: Set<String>) {
        if (denied.isEmpty()) return
        // shouldShowRequestPermissionRationale = false ⇔ User hat „Nicht mehr fragen"
        // gewählt ODER es ist die erste Ablehnung auf Android 12+. In beiden Fällen
        // werden weitere Dialoge nicht mehr von alleine erscheinen – also unser
        // eigener Erklär-Dialog mit Settings-Link.
        val needsOurDialog = denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (needsOurDialog) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_msg)
                .setPositiveButton(R.string.permission_rationale_open) { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                    try { startActivity(intent) } catch (_: Throwable) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
                .setNegativeButton(R.string.battery_dialog_later, null)
                .show()
        } else {
            Toast.makeText(this, R.string.error_permissions, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ (API 35/36) zeichnet Apps standardmäßig edge-to-edge.
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        if (adapter == null) {
            binding.statusText.setText(R.string.error_no_bluetooth)
            binding.searchBtn.isEnabled = false
            binding.hostBtn.isEnabled = false
            binding.joinBtn.isEnabled = false
            return
        }

        binding.searchBtn.setOnClickListener { onSearchClicked() }
        binding.hostBtn.setOnClickListener { onHostClicked() }
        binding.joinBtn.setOnClickListener { onJoinClicked() }
        binding.disconnectBtn.setOnClickListener { IntercomService.stopIntent(this) }
        binding.muteSwitch.setOnCheckedChangeListener { _, checked ->
            service?.setMuted(checked)
        }
        binding.appLogo.setOnClickListener { handleLogoTap() }
        binding.moreBtn.setOnClickListener { showOverflowMenu(it) }
        wireMicModeToggle()
        wireSensitivitySlider()
        wirePeerVolumeSlider()
        renderDirectCard()
        wireHeaderAspect()
        applyWallpaper()

        // Bei jeder neuen App-Version werden die Sticky-Flags zurückgesetzt, damit
        // die Onboarding-Schritte einmalig nach dem Update wieder erscheinen.
        resetOnboardingFlagsIfNewVersion()
        runOnboardingChain()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, IntercomService::class.java), serviceConn, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        // Beim Aufwachen Helligkeit zurück, Auto-Dim ggf. neu planen.
        wakeScreen()
        // Falls der User in der WallpaperActivity ein neues Hintergrundbild
        // gewählt hat, hier ohne Neustart sofort übernehmen.
        applyWallpaper()
    }

    override fun onPause() {
        super.onPause()
        // Damit ein wiedererwachendes Panel nicht in 0 % steckenbleibt.
        cancelDimSchedule()
        applyBrightness(BRIGHTNESS_DEFAULT)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.stateListener = null
            service?.levelListener = null
            service?.rttListener = null
            service?.peerBatteryListener = null
            unbindService(serviceConn)
            bound = false
        }
        cancelDimSchedule()
        applyBrightness(BRIGHTNESS_DEFAULT)
        // Wenn das Sheet noch offen ist, beim Verlassen aufräumen.
        discovery?.stop(); discovery = null
        searchSheet?.dismiss(); searchSheet = null
    }

    /**
     * Jede Berührung zählt als Aktivität: Helligkeit zurück auf System-Default,
     * Dim-Plan neu starten, falls eine aktive Verbindung läuft.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            wakeScreen()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun wakeScreen() {
        cancelDimSchedule()
        applyBrightness(BRIGHTNESS_DEFAULT)
        // Während aktiver Verbindung Panel an, sonst dem System überlassen.
        // Auto-Dim kann in den Settings deaktiviert werden – dann bleibt KEEP_SCREEN_ON
        // gesetzt und das Panel wird nicht abgedunkelt.
        if (service?.state == IntercomService.State.CONNECTED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            if (prefs.getBoolean(IntercomService.KEY_AUTO_DIM_ENABLED, true)) {
                dimHandler.postDelayed(dimToLow, autoDimDelayMs())
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun cancelDimSchedule() {
        dimHandler.removeCallbacks(dimToLow)
        dimHandler.removeCallbacks(lightsOff)
    }

    /**
     * Liest die Auto-Dim-/Auto-Off-Zeiten aus dem User-Preset
     * `KEY_AUTO_DIM_SPEED`. Default = „default" (10 s / 30 s) — passt zum
     * Stand vor v1.7-beta17.
     */
    private fun autoDimDelayMs(): Long = when (autoDimSpeed()) {
        "fast"   -> 5_000L
        "medium" -> 20_000L
        "slow"   -> 60_000L
        else     -> 10_000L
    }
    private fun autoOffDelayMs(): Long = when (autoDimSpeed()) {
        "fast"   -> 15_000L
        "medium" -> 60_000L
        "slow"   -> 120_000L
        else     -> 30_000L
    }
    private fun autoDimSpeed(): String =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString(IntercomService.KEY_AUTO_DIM_SPEED, "default") ?: "default"

    private fun applyBrightness(value: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    /**
     * Hält den Header-Container auf 14:10-Aspect-Ratio (entspricht der
     * nativen Komposition aller Bundled-Wallpapers, 1484×1060). Dadurch füllt
     * das Wallpaper den Header rechteckig ohne Beschnitt — das komplette
     * Motiv (inkl. Motorrad rechts vom Schriftzug) bleibt sichtbar.
     */
    private fun wireHeaderAspect() {
        binding.headerFrame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            applyHeaderAspect(v)
        }
        applyHeaderAspect(binding.headerFrame)
    }

    private fun applyHeaderAspect(v: View) {
        val w = v.width
        if (w <= 0) return
        // 14:10 = 1.4:1 → Höhe = Breite * 10/14.
        val targetH = (w * 10 + 7) / 14
        if (v.layoutParams.height != targetH) {
            v.layoutParams = v.layoutParams.apply { height = targetH }
        }
    }

    /**
     * Lädt das vom User gewählte Bundled-Wallpaper. Wird aus [onCreate] und
     * [onResume] aufgerufen, damit eine Auswahl in der WallpaperActivity
     * sofort ohne App-Restart sichtbar wird.
     */
    private fun applyWallpaper() {
        val wp = WallpaperRepository.current(this)
        val drawable = WallpaperRepository.loadDrawable(this, wp) ?: return
        binding.headerBg.setImageDrawable(drawable)
    }

    private fun onHostClicked() {
        if (!ensureReady()) return
        IntercomService.host(this)
    }

    @SuppressLint("MissingPermission")
    private fun onProfileClicked(profile: Profile) {
        if (!ensureReady()) return
        val a = adapter ?: return
        val device = try { a.getRemoteDevice(profile.mac) } catch (_: IllegalArgumentException) { null } ?: return
        IntercomService.directConnect(this, device)
    }

    private fun renderDirectCard() {
        val profiles = IntercomService.loadProfiles(this)
        val state = service?.state ?: IntercomService.State.IDLE
        val visible = profiles.isNotEmpty() &&
            (state == IntercomService.State.IDLE || state == IntercomService.State.ERROR)
        binding.directCard.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val list = binding.profileList
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        profiles.forEach { p ->
            val btn = inflater.inflate(R.layout.item_profile, list, false) as MaterialButton
            btn.text = getString(R.string.btn_direct_connect, p.label)
            btn.setOnClickListener { onProfileClicked(p) }
            btn.setOnLongClickListener { showProfileMenu(p); true }
            list.addView(btn)
        }
    }

    private fun showProfileMenu(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_actions_title, profile.label))
            .setItems(
                arrayOf(
                    getString(R.string.profile_action_connect),
                    getString(R.string.profile_action_rename),
                    getString(R.string.profile_action_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> onProfileClicked(profile)
                    1 -> renameProfile(profile)
                    2 -> { ProfileStore.delete(this, profile.mac); renderDirectCard() }
                }
            }
            .show()
    }

    private fun renameProfile(profile: Profile) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(profile.label)
            setSelection(profile.label.length)
            hint = getString(R.string.profile_rename_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newLabel = input.text.toString().trim().ifEmpty { profile.label }
                ProfileStore.rename(this, profile.mac, newLabel)
                renderDirectCard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun wireMicModeToggle() {
        val current = IntercomService.loadMicMode(this)
        val checkId = when (current) {
            AudioEngine.MicMode.OPEN -> R.id.modeOpen
            AudioEngine.MicMode.WIND_FILTER -> R.id.modeFilter
            AudioEngine.MicMode.VOICE_GATE -> R.id.modeGate
        }
        binding.micModeGroup.check(checkId)
        applyMicModeHint(current)

        binding.micModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.modeOpen -> AudioEngine.MicMode.OPEN
                R.id.modeGate -> AudioEngine.MicMode.VOICE_GATE
                else -> AudioEngine.MicMode.WIND_FILTER
            }
            applyMicModeHint(mode)
            // Persist auch dann, wenn Service nicht gebunden ist (Direct-Connect liest später).
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(IntercomService.KEY_MIC_MODE, mode.name).apply()
            service?.setMicMode(mode)
        }
    }

    private fun applyMicModeHint(mode: AudioEngine.MicMode) {
        binding.micModeHint.setText(
            when (mode) {
                AudioEngine.MicMode.OPEN -> R.string.mic_mode_open_hint
                AudioEngine.MicMode.WIND_FILTER -> R.string.mic_mode_filter_hint
                AudioEngine.MicMode.VOICE_GATE -> R.string.mic_mode_gate_hint
            }
        )
        binding.sensitivityRow.visibility =
            if (mode == AudioEngine.MicMode.VOICE_GATE) View.VISIBLE else View.GONE
    }

    private fun wireSensitivitySlider() {
        val initial = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(IntercomService.KEY_GATE_BIAS, 0f)
        binding.sensitivitySlider.value = initial.coerceIn(-10f, 10f)
        renderSensitivityValue(initial)
        binding.sensitivitySlider.addOnChangeListener { _, value, _ ->
            renderSensitivityValue(value)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(IntercomService.KEY_GATE_BIAS, value).apply()
            service?.setGateBiasDb(value)
        }
    }

    private fun renderSensitivityValue(value: Float) {
        binding.sensitivityValue.text = getString(R.string.sensitivity_label, value.toInt())
    }

    private fun wirePeerVolumeSlider() {
        val initial = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(IntercomService.KEY_PEER_VOLUME, 1.0f)
        binding.peerVolumeSlider.value = initial.coerceIn(0f, 1f)
        binding.peerVolumeSlider.addOnChangeListener { _, value, _ ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(IntercomService.KEY_PEER_VOLUME, value).apply()
            service?.setPeerVolume(value)
        }
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_settings -> {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); true
                    }
                    R.id.action_help -> {
                        startActivity(Intent(this@MainActivity, HelpActivity::class.java)); true
                    }
                    R.id.action_feedback -> {
                        Feedback.show(this@MainActivity); true
                    }
                    R.id.action_diagnostic -> {
                        startActivity(Intent(this@MainActivity, DiagnosticActivity::class.java)); true
                    }
                    R.id.action_about -> {
                        startActivity(Intent(this@MainActivity, LicensesActivity::class.java)); true
                    }
                    else -> false
                }
            }
        }.show()
    }

    private fun handleLogoTap() {
        val now = System.currentTimeMillis()
        if (now - lastLogoTapMs > 600L) logoTapCount = 0
        lastLogoTapMs = now
        logoTapCount++
        if (logoTapCount >= 3) {
            logoTapCount = 0
            startActivity(Intent(this, DiagnosticActivity::class.java))
        }
    }

    private fun onPeerBatteryChanged(percent: Int) {
        runOnUiThread {
            if (percent !in 0..100) {
                binding.peerBatteryPill.visibility = View.GONE
                return@runOnUiThread
            }
            // Farbcode: ≤10 % rot (kritisch), ≤25 % gelb (Vorwarnung), sonst grün.
            val colorRes = when {
                percent <= 10 -> R.color.state_error
                percent <= 25 -> R.color.state_listening
                else -> R.color.state_connected
            }
            val color = ContextCompat.getColor(this, colorRes)
            binding.peerBattery.text = getString(R.string.peer_battery, percent)
            binding.peerBattery.setTextColor(color)
            DrawableCompat.setTint(
                DrawableCompat.wrap(binding.peerBatteryIcon.drawable.mutate()),
                color,
            )
            binding.peerBatteryPill.visibility = View.VISIBLE
        }
    }

    private fun onRttChanged(rttMs: Long) {
        runOnUiThread {
            binding.rttLabel.text = getString(R.string.rtt_label, rttMs)
            binding.rttLabel.visibility = View.VISIBLE
            binding.rttBars.visibility = View.VISIBLE
            // Kalibrierung für BT-Classic-RFCOMM mit gleichzeitig aktivem SCO-Audio:
            // realistische normale RTT liegt bei 200–500 ms (auch bei direkter Nähe –
            // limitiert durch Funkzeit-Sharing zwischen SCO und RFCOMM, nicht durch
            // Signalstärke). Erst über 500 ms ist tatsächlich was im Argen.
            val good = ContextCompat.getColor(this, R.color.state_connected)
            val warn = ContextCompat.getColor(this, R.color.state_listening)
            val bad = ContextCompat.getColor(this, R.color.state_error)
            val muted = ContextCompat.getColor(this, R.color.state_idle)
            val (c1, c2, c3) = when {
                rttMs < 500 -> Triple(good, good, good)
                rttMs < 1000 -> Triple(good, warn, muted)
                else -> Triple(bad, muted, muted)
            }
            tintBar(binding.rttBar1, c1)
            tintBar(binding.rttBar2, c2)
            tintBar(binding.rttBar3, c3)
        }
    }

    private fun tintBar(view: View, color: Int) {
        val drawable = ResourcesCompat.getDrawable(resources, R.drawable.rtt_bar, theme)?.mutate() ?: return
        val tinted = DrawableCompat.wrap(drawable)
        DrawableCompat.setTint(tinted, color)
        view.background = tinted
    }

    private fun onSearchClicked() {
        if (!ensureReady()) return
        if (!ensureScanReady()) return
        // Discoverable-Dauer aus den Settings (Default 60 s).
        val timeoutS = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(IntercomService.KEY_DISCOVERABLE_TIMEOUT, 60)
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, timeoutS)
        discoverableLauncher.launch(intent)
    }

    private fun ensureScanReady(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!granted(Manifest.permission.BLUETOOTH_ADVERTISE)) needed += Manifest.permission.BLUETOOTH_ADVERTISE
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun beginPeerSearch() {
        val a = adapter ?: return

        // Service in den Such-Modus bringen (öffnet SDP-Server, damit andere uns
        // als SpeakEasy erkennen). Audio läuft erst nach erfolgreicher Verbindung.
        IntercomService.search(this)

        val sheetView = LayoutInflater.from(this).inflate(R.layout.sheet_search, null)
        val sheet = BottomSheetDialog(this).apply { setContentView(sheetView) }

        val titleView = sheetView.findViewById<TextView>(R.id.searchTitle)
        val progressView = sheetView.findViewById<ProgressBar>(R.id.searchProgress)
        val list = sheetView.findViewById<LinearLayout>(R.id.peerList)
        val emptyView = sheetView.findViewById<TextView>(R.id.emptyText)
        val cancelBtn = sheetView.findViewById<MaterialButton>(R.id.cancelBtn)
        val rescanBtn = sheetView.findViewById<MaterialButton>(R.id.rescanBtn)

        val d = PeerDiscovery(this, a)
        discovery = d
        searchSheet = sheet

        val listener = object : PeerDiscovery.Listener {
            override fun onPeerFound(device: BluetoothDevice, name: String) {
                if (list.findViewWithTag<View>(device.address) != null) return
                emptyView.visibility = View.GONE
                val row = LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_peer, list, false)
                row.tag = device.address
                row.findViewById<TextView>(R.id.peerName).text = name
                row.findViewById<TextView>(R.id.peerMac).text = device.address
                row.setOnClickListener { askConnect(device, name, sheet) }
                list.addView(row)
            }

            override fun onScanFinished() {
                progressView.visibility = View.GONE
                rescanBtn.visibility = View.VISIBLE
                titleView.setText(R.string.search_finished)
                val onlyEmpty = (0 until list.childCount)
                    .all { list.getChildAt(it) === emptyView || list.getChildAt(it).visibility == View.GONE }
                if (onlyEmpty) {
                    emptyView.visibility = View.VISIBLE
                    emptyView.setText(R.string.no_peers_found)
                }
            }
        }

        cancelBtn.setOnClickListener { sheet.dismiss() }
        rescanBtn.setOnClickListener {
            d.stop()
            list.removeAllViews()
            emptyView.visibility = View.VISIBLE
            emptyView.setText(R.string.searching_for_peers)
            list.addView(emptyView)
            progressView.visibility = View.VISIBLE
            rescanBtn.visibility = View.GONE
            titleView.setText(R.string.search_title)
            d.start(listener)
        }

        sheet.setOnDismissListener {
            d.stop()
            discovery = null
            searchSheet = null
            // Wenn der Nutzer die Suche abbricht, ohne dass jemand verbunden ist,
            // räumt der Service auf.
            if (service?.state == IntercomService.State.SEARCHING) {
                IntercomService.stopIntent(this@MainActivity)
            }
        }

        d.start(listener)
        sheet.show()
    }

    private fun askConnect(device: BluetoothDevice, name: String, sheet: BottomSheetDialog) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connect_question_title)
            .setMessage(getString(R.string.connect_question_msg, name))
            .setPositiveButton(R.string.yes) { _, _ ->
                // Discovery synchron stoppen, bevor der Service den Connect startet –
                // sonst läuft der RFCOMM-Connect oft in den "read ret: -1"-Fehler.
                discovery?.stop(); discovery = null
                IntercomService.acceptInvitation(this, device)
                sheet.dismiss()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun onJoinClicked() {
        if (!ensureReady()) return
        val a = adapter ?: return
        val paired: List<BluetoothDevice> = try {
            a.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) { emptyList() }

        if (paired.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_LONG).show()
            return
        }

        val labels = paired.map { d ->
            val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
            "$name\n${d.address}"
        }
        val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_peer)
            .setAdapter(arrayAdapter) { _, which ->
                IntercomService.join(this, paired[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensureReady(): Boolean {
        val a = adapter ?: return false
        if (!hasBluetoothPermissions() || !hasRecordPermission()) {
            requestRuntimePermissions()
            return false
        }
        if (!a.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }

    private fun onStateChanged() {
        runOnUiThread {
            val s = service
            val state = s?.state ?: IntercomService.State.IDLE

            val statusText = when (state) {
                IntercomService.State.IDLE -> getString(R.string.status_idle)
                IntercomService.State.SEARCHING -> getString(R.string.status_searching)
                IntercomService.State.LISTENING -> getString(R.string.status_listening)
                IntercomService.State.CONNECTING -> getString(R.string.status_connecting)
                IntercomService.State.CONNECTED -> getString(R.string.status_connected, s?.peerName ?: "")
                IntercomService.State.RECONNECTING -> getString(R.string.status_reconnecting, s?.reconnectAttempt ?: 1)
                IntercomService.State.ERROR -> getString(R.string.status_error, s?.lastError ?: "")
            }
            binding.statusText.text = statusText

            binding.peerName.text = when (state) {
                IntercomService.State.CONNECTED -> s?.peerName ?: getString(R.string.peer_none)
                IntercomService.State.SEARCHING -> getString(R.string.status_searching)
                IntercomService.State.LISTENING -> getString(R.string.status_listening)
                IntercomService.State.CONNECTING -> getString(R.string.status_connecting)
                IntercomService.State.RECONNECTING -> s?.peerName ?: getString(R.string.peer_none)
                else -> getString(R.string.peer_none)
            }

            val dotColorRes = when (state) {
                IntercomService.State.IDLE -> R.color.state_idle
                IntercomService.State.SEARCHING -> R.color.state_listening
                IntercomService.State.LISTENING -> R.color.state_listening
                IntercomService.State.CONNECTING -> R.color.state_connecting
                IntercomService.State.CONNECTED -> R.color.state_connected
                IntercomService.State.RECONNECTING -> R.color.state_error
                IntercomService.State.ERROR -> R.color.state_error
            }
            tintDot(dotColorRes)
            val pulsing = state == IntercomService.State.SEARCHING ||
                state == IntercomService.State.LISTENING ||
                state == IntercomService.State.CONNECTING ||
                state == IntercomService.State.RECONNECTING
            if (pulsing) startDotPulse() else stopDotPulse()

            binding.connectingSpinner.visibility =
                if (pulsing) View.VISIBLE else View.GONE

            val connected = state == IntercomService.State.CONNECTED
            val busy = pulsing || connected

            binding.searchBtn.isEnabled = !busy
            binding.hostBtn.isEnabled = !busy
            binding.joinBtn.isEnabled = !busy
            binding.disconnectBtn.isEnabled = busy
            binding.muteSwitch.isEnabled = connected
            if (!connected) binding.muteSwitch.isChecked = false

            // VU-Karte nur sichtbar, wenn aktive Verbindung steht.
            binding.levelCard.visibility = if (connected) View.VISIBLE else View.GONE
            if (!connected) {
                onLevelChanged(0f, 0f)
                binding.rttLabel.visibility = View.GONE
                binding.rttBars.visibility = View.GONE
                binding.peerBatteryPill.visibility = View.GONE
            }

            // Direkt-Karte erscheint nur in IDLE/ERROR und wenn ein letzter Peer bekannt ist.
            renderDirectCard()

            // Auto-Dim nur während aktiver Verbindung; sonst System-Default und
            // KEEP_SCREEN_ON zurückgeben, damit das System normal abschalten kann.
            if (connected) {
                wakeScreen()
            } else {
                cancelDimSchedule()
                applyBrightness(BRIGHTNESS_DEFAULT)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            if (connected) {
                searchSheet?.dismiss()
                val peer = s?.peerName ?: ""
                Toast.makeText(this, getString(R.string.incoming_connected, peer), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Pegelbalken aus dem Audio-Engine-Callback aktualisieren. Wir lassen den Balken
     * bei steigendem Pegel sofort folgen, bei sinkendem aber langsam zurückfallen –
     * so verhält er sich wie ein klassisches VU-Meter mit Peak-Hold.
     */
    private fun onLevelChanged(sent: Float, recv: Float) {
        smoothSent = if (sent > smoothSent) sent else smoothSent * VU_DECAY
        smoothRecv = if (recv > smoothRecv) recv else smoothRecv * VU_DECAY
        val showSent = smoothSent
        val showRecv = smoothRecv
        runOnUiThread {
            binding.selfLevel.progress = (showSent * 1000f).toInt()
            binding.peerLevel.progress = (showRecv * 1000f).toInt()
        }
    }

    private fun tintDot(@androidx.annotation.ColorRes colorRes: Int) {
        val drawable = ResourcesCompat.getDrawable(resources, R.drawable.state_dot, theme)
            ?.mutate() ?: return
        val tinted = DrawableCompat.wrap(drawable)
        DrawableCompat.setTint(tinted, ResourcesCompat.getColor(resources, colorRes, theme))
        binding.stateDot.background = tinted
    }

    private fun startDotPulse() {
        if (binding.stateDot.animation != null) return
        val pulse = AlphaAnimation(1.0f, 0.25f).apply {
            duration = 700
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.stateDot.startAnimation(pulse)
    }

    private fun stopDotPulse() {
        binding.stateDot.clearAnimation()
        binding.stateDot.alpha = 1f
    }

    /**
     * Edge-to-edge: Header bekommt zusätzlichen Top-Inset (sodass Titel nicht hinter
     * den Status-Bar-Symbolen liegt), die Wurzel zusätzliches Bottom-Padding
     * (sodass die letzte Karte nicht hinter der Navigations-/Geste-Leiste verschwindet).
     */
    private fun applySystemBarInsets() {
        val baseHeaderTopPx = binding.headerContent.paddingTop
        val baseRootBottomPx = binding.root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sb = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.headerContent.updatePadding(top = baseHeaderTopPx + sb.top)
            binding.root.updatePadding(bottom = baseRootBottomPx + sb.bottom)
            insets
        }
    }

    // -- Onboarding-Kette -----------------------------------------------------

    /**
     * Bei einer neuen App-Version werden die "schon gesehen"-Flags zurückgesetzt,
     * damit Anleitung + Akku-Dialog nach jedem Update einmalig wieder auftauchen.
     */
    private fun resetOnboardingFlagsIfNewVersion() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (_: Throwable) { 0 }
        if (prefs.getInt(KEY_LAST_VERSION, -1) != current) {
            prefs.edit()
                .putInt(KEY_LAST_VERSION, current)
                .putBoolean(KEY_INTRO_SEEN, false)
                .putBoolean(KEY_BATTERY_PROMPTED, false)
                .apply()
        }
    }

    /**
     * Onboarding-Schritte erscheinen nacheinander, jeweils erst nachdem der
     * vorherige Schritt abgeschlossen wurde:
     * Berechtigungen → Erst-Anleitung → Akku-Optimierung → Bluetooth aktivieren.
     */
    private fun runOnboardingChain() {
        onboardingInProgress = true
        val needed = collectMissingPermissions()
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            // weiter geht's im permissionLauncher-Callback → onPermissionsResolved().
        } else {
            onPermissionsResolved()
        }
    }

    private fun onPermissionsResolved() {
        showDisclaimer(onComplete = ::onDisclaimerComplete)
    }

    private fun onDisclaimerComplete() {
        showIntro(onComplete = ::onIntroComplete)
    }

    private fun onIntroComplete() {
        showBatteryDialog(onComplete = ::onBatteryComplete)
    }

    private fun onBatteryComplete() {
        // Letzter Schritt: Bluetooth aktivieren, falls aus.
        val a = adapter
        if (a != null && !a.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        onboardingInProgress = false
        maybeAutoConnect()
    }

    /**
     * Nach abgeschlossener Onboarding-Kette: wenn der User in den Einstellungen
     * Auto-Verbinden aktiviert hat und der Service noch in IDLE ist, direkt zum
     * meistbenutzten gespeicherten Profil verbinden. Stille Operation – greift
     * nicht ein, falls keine Profile existieren oder BT noch aus ist.
     */
    @SuppressLint("MissingPermission")
    private fun maybeAutoConnect() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(IntercomService.KEY_AUTO_CONNECT, false)) return
        val a = adapter ?: return
        if (!a.isEnabled) return
        val state = service?.state ?: IntercomService.State.IDLE
        if (state != IntercomService.State.IDLE && state != IntercomService.State.ERROR) return
        if (!hasBluetoothPermissions() || !hasRecordPermission()) return
        val mru = ProfileStore.loadAll(this).firstOrNull() ?: return
        val device = try {
            a.getRemoteDevice(mru.mac)
        } catch (_: IllegalArgumentException) { return }
        IntercomService.directConnect(this, device)
    }

    // -- Haftungs-Disclaimer (einmalig, nicht versions-resettet) -------------

    /**
     * Sachlicher Hinweis vor erster Nutzung: Hobby-Projekt ohne Garantie,
     * Bedienung nur im Stand, keine Haftung für Schäden im Straßenverkehr.
     * Anders als Intro/Akku-Dialog wird das Flag NICHT bei neuer Version
     * zurückgesetzt – einmal akzeptiert, dauerhaft akzeptiert.
     */
    private fun showDisclaimer(onComplete: () -> Unit) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)) {
            onComplete(); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
            }
            .setOnDismissListener { onComplete() }
            .show()
    }

    // -- Erst-Start-Tutorial --------------------------------------------------

    private data class IntroPage(val icon: Int, val title: Int, val body: Int)

    private fun showIntro(onComplete: () -> Unit) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INTRO_SEEN, false)) {
            onComplete(); return
        }

        val pages = listOf(
            IntroPage(R.drawable.ic_headset, R.string.intro_1_title, R.string.intro_1_body),
            IntroPage(R.drawable.ic_search, R.string.intro_2_title, R.string.intro_2_body),
            IntroPage(R.drawable.ic_mic, R.string.intro_3_title, R.string.intro_3_body),
        )

        val view = layoutInflater.inflate(R.layout.dialog_intro, null)
        val dialog: AlertDialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(view)
            .setCancelable(false)
            .create()

        val iconView = view.findViewById<ImageView>(R.id.introIcon)
        val titleView = view.findViewById<TextView>(R.id.introTitle)
        val bodyView = view.findViewById<TextView>(R.id.introBody)
        val pageView = view.findViewById<TextView>(R.id.introPage)
        val nextBtn = view.findViewById<MaterialButton>(R.id.introNext)
        val skipBtn = view.findViewById<MaterialButton>(R.id.introSkip)

        var index = 0
        fun render() {
            val p = pages[index]
            iconView.setImageResource(p.icon)
            titleView.setText(p.title)
            bodyView.setText(p.body)
            pageView.text = "${index + 1} / ${pages.size}"
            nextBtn.setText(if (index == pages.lastIndex) R.string.intro_finish else R.string.intro_next)
            skipBtn.visibility = if (index == pages.lastIndex) View.GONE else View.VISIBLE
        }
        render()

        nextBtn.setOnClickListener {
            if (index < pages.lastIndex) {
                index++; render()
            } else {
                prefs.edit().putBoolean(KEY_INTRO_SEEN, true).apply()
                dialog.dismiss()
            }
        }
        skipBtn.setOnClickListener {
            prefs.edit().putBoolean(KEY_INTRO_SEEN, true).apply()
            dialog.dismiss()
        }
        dialog.setOnDismissListener { onComplete() }

        dialog.show()
    }

    // -- Battery-Optimization-Whitelist ---------------------------------------

    private fun showBatteryDialog(onComplete: () -> Unit) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null || pm.isIgnoringBatteryOptimizations(packageName)) {
            onComplete(); return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) {
            onComplete(); return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_msg)
            .setPositiveButton(R.string.battery_dialog_open) { _, _ ->
                openBatterySettings()
            }
            .setNegativeButton(R.string.battery_dialog_later, null)
            .setOnDismissListener {
                prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
                onComplete()
            }
            .show()
    }

    private fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // -- Permissions -----------------------------------------------------------

    private fun requestRuntimePermissions() {
        val needed = collectMissingPermissions()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun collectMissingPermissions(): List<String> {
        val needed = mutableListOf<String>()
        if (!hasRecordPermission()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!granted(Manifest.permission.BLUETOOTH_ADVERTISE)) needed += Manifest.permission.BLUETOOTH_ADVERTISE
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        return needed
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        else true

    private fun hasRecordPermission(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFS = "speakeasy"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"
        private const val KEY_INTRO_SEEN = "intro_seen"
        private const val KEY_LAST_VERSION = "last_seen_version_code"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        // Pro Frame (~20 ms) zerfällt der Balken um 12 % – fühlt sich wie ein
        // klassisches VU-Meter an (sichtbarer Ausschlag, kein Wackeln).
        private const val VU_DECAY = 0.88f

        // Auto-Dim-Helligkeitsstufen. Die Verzögerungen leitet
        // [autoDimDelayMs]/[autoOffDelayMs] aus dem User-Preset ab
        // (KEY_AUTO_DIM_SPEED, Default 10 s / 30 s).
        private const val BRIGHTNESS_DEFAULT = -1f
        private const val BRIGHTNESS_DIM = 0.05f
        private const val BRIGHTNESS_OFF = 0.0f

    }
}

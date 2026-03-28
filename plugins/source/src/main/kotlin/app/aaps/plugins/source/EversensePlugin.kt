package app.aaps.plugins.source

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.keys.interfaces.Preferences
import com.nightscout.eversense.EversenseCGMPlugin
import com.nightscout.eversense.callbacks.EversenseScanCallback
import com.nightscout.eversense.callbacks.EversenseWatcher
import com.nightscout.eversense.enums.CalibrationReadiness
import com.nightscout.eversense.enums.EversenseType
import com.nightscout.eversense.models.EversenseCGMResult
import com.nightscout.eversense.models.EversenseScanResult
import com.nightscout.eversense.models.EversenseSecureState
import com.nightscout.eversense.models.EversenseState
import com.nightscout.eversense.util.StorageKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class EversensePlugin @Inject constructor(
    rh: ResourceHelper,
    private val context: Context,
    aapsLogger: AAPSLogger,
    preferences: Preferences
) : AbstractBgSourcePlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_blooddrop_48)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.source_eversense)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_eversense),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences
), BgSource, EversenseWatcher {

    @Inject lateinit var persistenceLayer: PersistenceLayer

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val json = Json { ignoreUnknownKeys = true }

    // SharedPreferences for reading/writing EversenseSecureState (credentials).
    // Uses the same tag as EversenseCGMPlugin so all Eversense state lives in one file.
    private val securePrefs by lazy {
        context.getSharedPreferences("EversenseCGMManager", Context.MODE_PRIVATE)
    }

    private var connectedPreference: Preference? = null
    private var batteryPreference: Preference? = null
    private var insertionPreference: Preference? = null
    private var lastSyncPreference: Preference? = null
    private var currentPhasePreference: Preference? = null
    private var lastCalibrationPreference: Preference? = null
    private var nextCalibrationPreference: Preference? = null
    private var calibrationActionPreference: Preference? = null

    init {
        eversense.setContext(context, true)
        eversense.addWatcher(this)
    }

    override fun onStart() {
        super.onStart()
        if (hasBluetoothPermissions()) {
            eversense.connect(null)
        } else {
            aapsLogger.warn(LTag.BGSOURCE, "Bluetooth permissions not granted — requesting permissions")
            requestBluetoothPermissions()
        }
    }

    override fun onStop() {
        super.onStop()
        eversense.disconnect()
        eversense.removeWatcher(this)
    }

    // Launch the transparent permission request activity which handles the system dialog.
    private fun requestBluetoothPermissions() {
        val intent = Intent(context, app.aaps.plugins.source.activities.RequestEversensePermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Read the current secure state (credentials) from SharedPreferences.
    private fun getSecureState(): EversenseSecureState {
        val stateJson = securePrefs.getString(StorageKeys.SECURE_STATE, null) ?: "{}"
        return json.decodeFromString(stateJson)
    }

    // Write an updated secure state back to SharedPreferences.
    private fun saveSecureState(state: EversenseSecureState) {
        securePrefs.edit(commit = true) {
            putString(StorageKeys.SECURE_STATE, json.encodeToString(EversenseSecureState.serializer(), state))
        }
    }

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        val state = eversense.getCurrentState()
        val notConnected = rh.gs(R.string.eversense_not_connected)
        val secureState = getSecureState()

        super.addPreferenceScreen(preferenceManager, parent, context, requiredKey)

        val bgSourceCategory = parent.findPreference<PreferenceCategory>("bg_source_upload_settings")
        bgSourceCategory?.let { category ->
            val eselSmoothing = SwitchPreference(context)
            eselSmoothing.key = "eversense_use_smoothing"
            eselSmoothing.title = rh.gs(R.string.eversense_use_smoothing)
            eselSmoothing.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                eversense.setSmoothing(newValue as Boolean)
                true
            }
            category.addPreference(eselSmoothing)
        }

        // Credentials section — username and password for Eversense 365 DMS login.
        // Not required for E3, but harmless to show for both transmitter types.
        val credentials = PreferenceCategory(context)
        parent.addPreference(credentials)
        credentials.apply {
            title = rh.gs(R.string.eversense_credentials_title)

            val username = EditTextPreference(context)
            username.key = "eversense_credentials_username"
            username.title = rh.gs(R.string.eversense_credentials_username)
            username.summary = if (secureState.username.isNotEmpty()) secureState.username
            else rh.gs(R.string.eversense_credentials_not_set)
            username.text = secureState.username
            username.dialogTitle = rh.gs(R.string.eversense_credentials_username)
            username.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val value = newValue as String
                val updated = getSecureState().also { it.username = value }
                saveSecureState(updated)
                pref.summary = if (value.isNotEmpty()) value
                else rh.gs(R.string.eversense_credentials_not_set)
                aapsLogger.info(LTag.BGSOURCE, "Eversense username updated")
                true
            }
            addPreference(username)

            val password = EditTextPreference(context)
            password.key = "eversense_credentials_password"
            password.title = rh.gs(R.string.eversense_credentials_password)
            password.summary = if (secureState.password.isNotEmpty()) rh.gs(R.string.eversense_credentials_password_set)
            else rh.gs(R.string.eversense_credentials_not_set)
            password.text = secureState.password
            password.dialogTitle = rh.gs(R.string.eversense_credentials_password)
            // Mask the password field in the dialog
            password.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            password.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val value = newValue as String
                val updated = getSecureState().also { it.password = value }
                saveSecureState(updated)
                pref.summary = if (value.isNotEmpty()) rh.gs(R.string.eversense_credentials_password_set)
                else rh.gs(R.string.eversense_credentials_not_set)
                aapsLogger.info(LTag.BGSOURCE, "Eversense password updated")
                true
            }
            addPreference(password)
        }

        val calibration = PreferenceCategory(context)
        parent.addPreference(calibration)
        calibration.apply {
            title = rh.gs(R.string.eversense_calibration_title)

            val currentPhase = Preference(context)
            currentPhase.key = "eversense_calibration_phase"
            currentPhase.title = rh.gs(R.string.eversense_calibration_phase)
            currentPhase.summary = state?.calibrationPhase?.name ?: notConnected
            addPreference(currentPhase)
            currentPhasePreference = currentPhase

            val lastCalibration = Preference(context)
            lastCalibration.key = "eversense_calibration_last"
            lastCalibration.title = rh.gs(R.string.eversense_calibration_last)
            lastCalibration.summary = state?.let { dateFormatter.format(Date(it.lastCalibrationDate)) } ?: notConnected
            addPreference(lastCalibration)
            lastCalibrationPreference = lastCalibration

            val nextCalibration = Preference(context)
            nextCalibration.key = "eversense_calibration_next"
            nextCalibration.title = rh.gs(R.string.eversense_calibration_next)
            nextCalibration.summary = state?.let { dateFormatter.format(Date(it.nextCalibrationDate)) } ?: notConnected
            addPreference(nextCalibration)
            nextCalibrationPreference = nextCalibration

            val calibrationAction = Preference(context)
            calibrationAction.key = "eversense_calibration_action"
            calibrationAction.title = rh.gs(R.string.eversense_calibration_action)
            calibrationAction.summary = when {
                state == null -> notConnected
                state.calibrationReadiness != CalibrationReadiness.READY -> state.calibrationReadiness.name
                else -> ""
            }
            calibrationAction.isEnabled = state?.calibrationReadiness == CalibrationReadiness.READY
            calibrationAction.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val latestState = eversense.getCurrentState()
                if (latestState == null) {
                    aapsLogger.warn(LTag.BGSOURCE, "Calibration tapped but state is null — device not connected?")
                    return@OnPreferenceClickListener false
                }
                if (latestState.calibrationReadiness != CalibrationReadiness.READY) {
                    aapsLogger.warn(LTag.BGSOURCE, "Calibration tapped but readiness is ${latestState.calibrationReadiness}")
                    return@OnPreferenceClickListener false
                }
                val intent = Intent(context, app.aaps.plugins.source.activities.EversenseCalibrationActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return@OnPreferenceClickListener true
            }
            addPreference(calibrationAction)
            calibrationActionPreference = calibrationAction
        }

        val information = PreferenceCategory(context)
        parent.addPreference(information)
        information.apply {
            title = rh.gs(R.string.eversense_information_title)

            val connected = Preference(context)
            connected.key = "eversense_information_connected"
            connected.title = rh.gs(R.string.eversense_information_connected)
            connected.summary = if (eversense.isConnected()) "✅" else "❌"
            connected.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                if (!hasBluetoothPermissions()) {
                    aapsLogger.warn(LTag.BGSOURCE, "Cannot start scan — requesting Bluetooth permissions")
                    requestBluetoothPermissions()
                    return@OnPreferenceClickListener false
                }
                aapsLogger.debug(LTag.BGSOURCE, "User tapped connect — starting BLE scan")
                showDeviceSelectionDialog(context)
                return@OnPreferenceClickListener true
            }
            addPreference(connected)
            connectedPreference = connected

            val battery = Preference(context)
            battery.key = "eversense_information_battery"
            battery.title = rh.gs(R.string.eversense_information_battery)
            battery.summary = state?.let { "${it.batteryPercentage}%" } ?: notConnected
            addPreference(battery)
            batteryPreference = battery

            val insertion = Preference(context)
            insertion.key = "eversense_information_insertion_date"
            insertion.title = rh.gs(R.string.eversense_information_insertion_date)
            insertion.summary = state?.let { dateFormatter.format(Date(it.insertionDate)) } ?: notConnected
            addPreference(insertion)
            insertionPreference = insertion

            val lastSync = Preference(context)
            lastSync.key = "eversense_information_last_sync"
            lastSync.title = rh.gs(R.string.eversense_information_last_sync)
            lastSync.summary = state?.let { dateFormatter.format(Date(it.lastSync)) } ?: notConnected
            lastSync.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                aapsLogger.debug(LTag.BGSOURCE, "User tapped Last Sync — triggering full sync and glucose read")
                if (!eversense.isConnected()) {
                    aapsLogger.warn(LTag.BGSOURCE, "Cannot sync — not connected")
                    return@OnPreferenceClickListener false
                }
                ioScope.launch {
                    eversense.triggerFullSync()
                }
                return@OnPreferenceClickListener true
            }
            addPreference(lastSync)
            lastSyncPreference = lastSync
        }
    }

    override fun onStateChanged(state: EversenseState) {
        aapsLogger.info(LTag.BGSOURCE, "New state received: ${Json.encodeToString(state)}")
        mainHandler.post {
            batteryPreference?.summary = "${state.batteryPercentage}%"
            insertionPreference?.summary = dateFormatter.format(Date(state.insertionDate))
            lastSyncPreference?.summary = dateFormatter.format(Date(state.lastSync))
            currentPhasePreference?.summary = state.calibrationPhase.name
            lastCalibrationPreference?.summary = dateFormatter.format(Date(state.lastCalibrationDate))
            nextCalibrationPreference?.summary = dateFormatter.format(Date(state.nextCalibrationDate))
            calibrationActionPreference?.let {
                it.summary = if (state.calibrationReadiness != CalibrationReadiness.READY) state.calibrationReadiness.name else ""
                it.isEnabled = state.calibrationReadiness == CalibrationReadiness.READY
            }
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        aapsLogger.info(LTag.BGSOURCE, "Connection changed — connected: $connected")
        mainHandler.post {
            connectedPreference?.summary = if (connected) "✅" else "❌"
        }
    }

    override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {
        val glucoseValues = readings.map { reading ->
            GV(
                timestamp = reading.datetime,
                value = reading.glucoseInMgDl.toDouble(),
                noise = null,
                raw = null,
                trendArrow = TrendArrow.fromString(reading.trend.type),
                sourceSensor = when (type) {
                    EversenseType.EVERSENSE_365 -> SourceSensor.EVERSENSE_365
                    EversenseType.EVERSENSE_E3  -> SourceSensor.EVERSENSE_E3
                }
            )
        }

        ioScope.launch {
            val result = persistenceLayer.insertCgmSourceData(
                Sources.Eversense,
                glucoseValues,
                listOf(),
                null
            ).blockingGet()
            aapsLogger.info(LTag.BGSOURCE, "CGM insert complete — inserted: ${result.inserted}, updated: ${result.updated}")
        }
    }

    // Shows a live-updating AlertDialog listing discovered Eversense devices as they are found.
    // The user taps a device to connect; Cancel stops the scan and dismisses the dialog.
    private fun showDeviceSelectionDialog(context: Context) {
        val foundDevices = mutableListOf<EversenseScanResult>()
        val displayItems = mutableListOf<String>()
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, displayItems)

        val listView = ListView(context)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setTitle(rh.gs(R.string.eversense_scan_title))
            .setView(listView)
            .setNegativeButton(rh.gs(R.string.eversense_scan_cancel)) { _, _ ->
                eversense.stopScan()
                aapsLogger.info(LTag.BGSOURCE, "Device scan cancelled by user")
            }
            .setCancelable(false)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = foundDevices[position]
            aapsLogger.info(LTag.BGSOURCE, "User selected device: ${selected.device.name} (${selected.device.address})")
            eversense.stopScan()
            eversense.connect(selected.device)
            dialog.dismiss()
        }

        val scanCallback = object : EversenseScanCallback {
            override fun onResult(item: EversenseScanResult) {
                // Avoid duplicate entries for the same device address.
                if (foundDevices.none { it.device.address == item.device.address }) {
                    foundDevices.add(item)
                    val label = "${item.device.name ?: rh.gs(R.string.eversense_scan_unknown_device)}  ${item.device.address}"
                    mainHandler.post {
                        displayItems.add(label)
                        adapter.notifyDataSetChanged()
                    }
                    aapsLogger.info(LTag.BGSOURCE, "Scan found: ${item.device.name} (${item.device.address})")
                }
            }
        }

        eversense.startScan(scanCallback)
        dialog.show()
    }

    companion object {
        private val eversense get() = EversenseCGMPlugin.instance
    }
}
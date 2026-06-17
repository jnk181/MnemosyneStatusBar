package com.mnemosynesuite.mnemosynestatusbar

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.net.Network
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Environment

class OverlayAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var visibleBar: View // Class property to handle setup and teardown

    private var clockText: TextView? = null
    private var batteryPercentageText: TextView? = null
    private var batteryBaseIcon: ImageView? = null
    private var batteryBoltIcon: ImageView? = null
    private var signalIcon: ImageView? = null
    private var wifiIcon: ImageView? = null
    private var mediaIcon: ImageView? = null
    private var messageIcon: ImageView? = null

    private var bluetoothIcon: ImageView? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    private var timeReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var telephonyManager: TelephonyManager? = null
    private var modernSignalCallback: Any? = null
    private var legacySignalListener: PhoneStateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    private var isCurrentlyCharging = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            updateStatusBarVisibilityFromSystemUi()
        }
    }

    private fun updateStatusBarVisibilityFromSystemUi() {
        val windowList = windows ?: return

        val bounds = android.graphics.Rect()
        val systemStatusBarPresent = windowList.any { w ->
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) {
                w.getBoundsInScreen(bounds)
                bounds.top == 0
            } else false
        }

        if (::visibleBar.isInitialized) {
            visibleBar.visibility = if (systemStatusBarPresent) View.VISIBLE else View.GONE
        }
    }

    override fun onInterrupt() {}

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isMediaPlaying = intent.getBooleanExtra("media_playing", false)
            val hasNewMessage = intent.getBooleanExtra("new_message", false)

            animateVisibility(mediaIcon, isMediaPlaying)
            animateVisibility(messageIcon, hasNewMessage)
        }
    }

    private fun animateVisibility(view: ImageView?, isVisible: Boolean) {
        view ?: return

        val isCurrentlyVisible = (view.visibility == View.VISIBLE)

        if (isVisible && !isCurrentlyVisible) {
            // Prepare to slide up and fade in
            view.alpha = 0f
            view.translationY = 20f // Start slightly lower
            view.visibility = View.VISIBLE

            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start()
        } else if (!isVisible && isCurrentlyVisible) {
            // Slide down and fade out
            view.animate()
                .alpha(0f)
                .translationY(20f)
                .setDuration(300)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. Fetch exact system status bar height
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 100

        // 2. Inflate directly to the class property (No local 'val' shadowing keyword)
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        visibleBar = inflater.inflate(R.layout.mnemosyne_status_bar, null)

        monitorMediaStatus()

        clockText = visibleBar.findViewById(R.id.status_bar_clock)
        batteryPercentageText  = visibleBar.findViewById(R.id.battery_percentage_text)
        batteryBaseIcon = visibleBar.findViewById(R.id.battery_base)
        batteryBoltIcon = visibleBar.findViewById(R.id.battery_bolt)
        signalIcon = visibleBar.findViewById(R.id.signal_indicator)
        wifiIcon = visibleBar.findViewById(R.id.wifi_indicator)
        mediaIcon = visibleBar.findViewById(R.id.media_indicator)
        messageIcon = visibleBar.findViewById(R.id.message_indicator)

        applyCustomFont()

        // 3. Set up secure overlay windows parameters
        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // Places layout over native icons
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        // 4. Inject canvas into the window hierarchy
        windowManager.addView(visibleBar, windowParams)

        monitorClockTime()
        monitorBatteryStatus()
        monitorSignalStrength()
        monitorWifiStatus()
        monitorMediaStatus()

        bluetoothIcon = visibleBar.findViewById(R.id.bluetooth_indicator)

        // Check initial state
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        updateBluetoothUI(adapter?.isEnabled == true)

        monitorBluetoothStatus()
    }

    private fun monitorBluetoothStatus() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                val isEnabled = (state == BluetoothAdapter.STATE_ON)
                updateBluetoothUI(isEnabled)
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun updateBluetoothUI(isEnabled: Boolean) {
        mainExecutor.execute {
            bluetoothIcon?.visibility = if (isEnabled) View.VISIBLE else View.GONE
        }
    }

    private fun monitorMediaStatus() {
        registerReceiver(statusReceiver, IntentFilter("com.mnemosynesuite.STATUS_UPDATE"))
    }

    private fun monitorClockTime() {
        // 1. Force the layout clock text to display current system time immediately on startup
        updateClockDisplay()

        // 2. Setup receiver to check for minute updates pushed by the system clock
        timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateClockDisplay()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK) // Dispatched exactly every 60 seconds
            addAction(Intent.ACTION_TIME_CHANGED) // Dispatched if user adjusts clock manually
            addAction(Intent.ACTION_TIMEZONE_CHANGED) // Dispatched if crossing geographic borders
        }
        registerReceiver(timeReceiver, filter)
    }

    /**
     * Computes the current time formatted to standard 24-hour style "HH:mm"
     */
    private fun updateClockDisplay() {
        val currentTime = Calendar.getInstance().time
        // Replace "HH:mm" with "hh:mm" if you want a 12-hour clock standard style instead
        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = timeFormatter.format(currentTime)

        clockText?.text = formattedTime
    }

    private fun monitorBatteryStatus() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val batteryPct = (level / scale.toFloat() * 100).toInt()
                    updateBatteryIcon(batteryPct)
                }

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                handleChargingAnimationState(isCharging)
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Manages starting and stopping the pulse cycle based on hardware state changes
     */
    private fun handleChargingAnimationState(shouldCharge: Boolean) {
        val bolt = batteryBoltIcon ?: return

        if (shouldCharge) {
            if (!isCurrentlyCharging) {
                isCurrentlyCharging = true
                bolt.visibility = View.VISIBLE
                bolt.alpha = 1.0f // Reset starting opacity anchor
                startPulsingLoop(bolt, fadeOut = false)
            }
        } else {
            isCurrentlyCharging = false
            bolt.animate().cancel() // Kill current running transitions
            bolt.visibility = View.GONE
        }
    }

    /**
     * Alternates alpha parameters continuously every 2000ms using sequential callbacks
     */
    private fun startPulsingLoop(view: ImageView, fadeOut: Boolean) {
        // Safe check to verify we aren't chasing layout tails after power disconnected
        if (!isCurrentlyCharging) return

        val targetAlpha = if (fadeOut) 0.0f else 1.0f

        view.animate()
            .alpha(targetAlpha)
            .setDuration(1000) // Exactly 2 seconds per direction
            .setInterpolator(LinearInterpolator()) // Maintains an even, consistent pace
            .withEndAction {
                // Invert direction and trigger loop recursion seamlessly
                startPulsingLoop(view, !fadeOut)
            }
            .start()
    }

    private fun updateBatteryIcon(percentage: Int) {
        batteryPercentageText?.text = "${percentage}%" //

        // 1. Determine the ideal target string suffix based on your 5-step rules
        val targetSuffix = when {
            percentage <= 1   -> "001"
            percentage <= 5   -> "005"
            percentage <= 10  -> "010"
            percentage <= 15  -> "015"
            percentage <= 20  -> "020"
            percentage <= 25  -> "025"
            percentage <= 30  -> "030"
            percentage <= 35  -> "035"
            percentage <= 40  -> "040"
            percentage <= 45  -> "045"
            percentage <= 50  -> "050"
            percentage <= 55  -> "055"
            percentage <= 60  -> "060"
            percentage <= 65  -> "065"
            percentage <= 70  -> "070"
            percentage <= 75  -> "075"
            percentage <= 80  -> "080"
            percentage <= 85  -> "085"
            percentage <= 90  -> "090"
            percentage <= 95  -> "095"
            else              -> "100"
        }

        var drawableResId = resources.getIdentifier("battery_$targetSuffix", "drawable", packageName)

        if (drawableResId == 0) {
            // Convert target suffix to an integer to safely math backwards
            var fallbackStep = targetSuffix.toInt()

            while (fallbackStep > 1 && drawableResId == 0) {
                // Round down to the nearest 5-interval step
                fallbackStep = if (fallbackStep == 100) 95 else ((fallbackStep - 1) / 5) * 5

                // If we fall all the way down below 5, evaluate the 1% threshold anchor
                val fallbackString = if (fallbackStep <= 1) "001" else String.format(Locale.US, "%03d", fallbackStep)
                drawableResId = resources.getIdentifier("battery_$fallbackString", "drawable", packageName)
            }
        }

        // 4. Ultimate Fallback: If absolutely zero battery assets match, default back to the layout baseline anchor
        if (drawableResId == 0) {
            drawableResId = R.drawable.battery_100
        }

        // 5. Update the UI asset container securely on the main canvas thread layout
        batteryBaseIcon?.setImageResource(drawableResId) //
    }

    private fun monitorSignalStrength() {
        // Fetch and initialize the system service directly inside the method safely
        val manager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (manager == null) {
            signalIcon?.setImageResource(R.drawable.signal_0)
            return
        }
        telephonyManager = manager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        updateSignalIcon(signalStrength.level)
                    }
                }
                modernSignalCallback = callback
                manager.registerTelephonyCallback(mainExecutor, callback)
            } else {
                legacySignalListener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        super.onSignalStrengthsChanged(signalStrength)
                        updateSignalIcon(signalStrength.level)
                    }
                }
                @Suppress("DEPRECATION")
                manager.listen(legacySignalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            }
        } catch (e: SecurityException) {
            signalIcon?.setImageResource(R.drawable.signal_0)
        }
    }

    private fun updateSignalIcon(nativeLevel: Int) {
        val drawableRes = when (nativeLevel) {
            0 -> R.drawable.signal_0
            1 -> R.drawable.signal_1
            2 -> R.drawable.signal_2
            3 -> R.drawable.signal_3
            4 -> R.drawable.signal_5 // Maxes out to your maximum bar graphic
            else -> R.drawable.signal_0
        }
        signalIcon?.setImageResource(drawableRes)
    }

    /**
     * Monitors transport layers specifically for Wi-Fi activation, connection status,
     * and ongoing RSSI signal strength changes.
     */
    private fun monitorWifiStatus() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cm = connectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {

            // Triggered whenever a active valid Wi-Fi connection is negotiated
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val capabilities = cm.getNetworkCapabilities(network)
                updateWifiUI(true, capabilities)
            }

            // Triggered dynamically if RSSI dbm attributes change while remaining connected
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                updateWifiUI(true, networkCapabilities)
            }

            // Triggered if the link breaks completely or the Wi-Fi radio antenna turns off
            override fun onLost(network: Network) {
                super.onLost(network)
                updateWifiUI(false, null)
            }
        }

        // Initialize display configuration state on boot
        val currentCapabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val isWifiInitiallyConnected = currentCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        updateWifiUI(isWifiInitiallyConnected, if (isWifiInitiallyConnected) currentCapabilities else null)

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Fallback for older versions if necessary
            true
        }
    }
    private fun applyCustomFont() {
        if (!hasStorageAccess()) {
            Log.e("FONT_DEBUG", "Permission MANAGE_EXTERNAL_STORAGE not granted!")
            return
        }

        val root = Environment.getExternalStorageDirectory()
        val configFile = java.io.File(root, "Mnemosyne/font_config.json")

        if (!configFile.exists()) {
            android.util.Log.d("FONTNAME_DEBUG", "Config file does not exist at ${configFile.absolutePath}")
            return
        }

        try {
            val jsonString = configFile.readText(Charsets.UTF_8)
            android.util.Log.d("FONTNAME_DEBUG", "JSON Content: $jsonString")

            val json = org.json.JSONObject(jsonString)
            val fontName = json.optString("display_font")

            android.util.Log.d("FONTNAME_DEBUG", "Extracted font name: '$fontName'")

            if (fontName.isNotEmpty()) {
                val fontsDir = java.io.File(root, "Mnemosyne/Fonts")
                val fontFile = java.io.File(fontsDir, fontName)

                if (fontFile.exists()) {
                    val typeface = android.graphics.Typeface.createFromFile(fontFile)
                    clockText?.typeface = typeface
                } else {
                    android.util.Log.e("FONTNAME_DEBUG", "Font file not found at ${fontFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FONTNAME_DEBUG", "Error parsing font config", e)
        }
    }

    private fun updateWifiUI(isConnected: Boolean, capabilities: NetworkCapabilities?) {
        // Enforce synchronization onto the Main UI Thread context safely
        mainExecutor.execute {
            val icon = wifiIcon ?: return@execute

            if (!isConnected) {
                animateVisibility(icon,false)
                return@execute
            }

            animateVisibility(icon,true)

            if (capabilities == null) {
                icon.setImageResource(R.drawable.wifi0)
                return@execute
            }

            // If Android 12+, we can pull the signal level directly out of the capabilities object
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val level = capabilities.signalStrength
                // Scale map: rssi signal values generally match level indices 0..4
                Log.d("wifi signal strength",":::${level}")
                val resourceId = when {
                    level >= -60 -> R.drawable.wifi3 // Excellent
                    level >= -70 -> R.drawable.wifi2 // Good
                    level >= -80 -> R.drawable.wifi1 // Fair
                    else        -> R.drawable.wifi0 // Weak
                }
                icon.setImageResource(resourceId)
            } else {
                // Fallback implementation handling RSSI metrics on legacy targets
                @Suppress("DEPRECATION")
                val rssi = capabilities.linkDownstreamBandwidthKbps // or matching structural markers
                // General default to Mid-Max if exact calculation isn't exposed on early levels
                icon.setImageResource(R.drawable.wifi3)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        isCurrentlyCharging = false
        batteryBoltIcon?.animate()?.cancel()

        if (timeReceiver != null) {
            unregisterReceiver(timeReceiver)
            timeReceiver = null
        }

        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver)
            batteryReceiver = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modernSignalCallback != null) {
            (modernSignalCallback as? TelephonyCallback)?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
        } else if (legacySignalListener != null) {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(legacySignalListener, PhoneStateListener.LISTEN_NONE)
        }

        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }

        if (::visibleBar.isInitialized) {
            windowManager.removeView(visibleBar)
        }

        unregisterReceiver(statusReceiver)
    }
}
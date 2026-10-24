package com.example.proximityalert

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.proximityalert.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    // ── Binding ───────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding

    // ── Location ──────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentUserLatLng: LatLng? = null

    // ── Map ───────────────────────────────────────────────────
    private var mapLibreMap: MapLibreMap? = null
    private var userLocationSource: GeoJsonSource? = null
    private val USER_SOURCE = "user-location-source"
    private val PULSE_LAYER = "user-pulse-layer"
    private val DOT_LAYER   = "user-dot-layer"

    // ── Target ────────────────────────────────────────────────
    private var targetLatLng: LatLng? = null
    private var targetName: String    = ""

    // ── Alarm ─────────────────────────────────────────────────
    private var alarmRadiusMeters = 100
    private var isMonitoring   = false
    private var alarmTriggered = false
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var alarmHandler: Handler? = null
    private var alarmRunnable: Runnable? = null
    private var rgbAnimator: ValueAnimator? = null

    // ── GPS interval tracking ────────────────────────────────
    private var currentIntervalMs = 15_000L  // starts at far interval

    // ── Search ────────────────────────────────────────────────
    private var searchPanelVisible    = false
    private var currentSearchResults  = listOf<GeocodingHelper.GeoResult>()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001

        // Adaptive GPS interval thresholds
        private const val INTERVAL_CLOSE  = 3_000L   // <= 500m  → 3 seconds
        private const val INTERVAL_MEDIUM = 8_000L   // <= 1000m → 8 seconds
        private const val INTERVAL_FAR    = 15_000L  // >  1000m → 15 seconds

        private const val THRESHOLD_CLOSE  = 500f    // metres
        private const val THRESHOLD_MEDIUM = 1000f   // metres
    }

    // ─────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupMap(savedInstanceState)
        setupUI()
        setupLocationCallback()
        requestLocationPermissions()
    }

    override fun onStart()     { super.onStart();     binding.mapView.onStart() }
    override fun onResume()    { super.onResume();    binding.mapView.onResume() }
    override fun onPause()     { super.onPause();     binding.mapView.onPause() }
    override fun onStop()      { super.onStop();      binding.mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) stopMonitoring()
        rgbAnimator?.cancel()
        binding.mapView.onDestroy()
    }

    // ─────────────────────────────────────────────────────────
    // MAP SETUP
    // ─────────────────────────────────────────────────────────

    private fun setupMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        binding.mapLoadingProgress.visibility = View.VISIBLE

        binding.mapView.getMapAsync { map ->
            mapLibreMap = map

            map.setStyle(
                Style.Builder().fromUri(
                    "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"
                )
            ) { style ->
                binding.mapLoadingProgress.visibility = View.GONE
                setupUserLocationLayer(style)
                currentUserLatLng?.let { updateBlueDot(it) }
            }

            binding.mapView.addOnDidFailLoadingMapListener {
                runOnUiThread {
                    binding.mapLoadingProgress.visibility = View.GONE
                    toast("Failed to load map. Check internet connection.")
                }
            }

            // Long press to drop pin
            map.addOnMapLongClickListener { point ->
                if (!isMonitoring) {
                    setTarget(point, "Pinned Location")
                    pulseFab()
                }
                true
            }

            moveToCurrentLocation()
        }
    }

    // ─────────────────────────────────────────────────────────
    // BLUE DOT
    // ─────────────────────────────────────────────────────────

    private fun setupUserLocationLayer(style: Style) {
        userLocationSource = GeoJsonSource(USER_SOURCE)
        style.addSource(userLocationSource!!)

        style.addLayer(CircleLayer(PULSE_LAYER, USER_SOURCE).apply {
            setProperties(
                circleRadius(18f),
                circleColor("#4A90D9"),
                circleOpacity(0.20f),
                circleStrokeWidth(0f)
            )
        })
        style.addLayer(CircleLayer(DOT_LAYER, USER_SOURCE).apply {
            setProperties(
                circleRadius(8f),
                circleColor("#2979FF"),
                circleOpacity(1.0f),
                circleStrokeWidth(2.5f),
                circleStrokeColor("#FFFFFF")
            )
        })
    }

    private fun updateBlueDot(latLng: LatLng) {
        userLocationSource?.setGeoJson(
            Feature.fromGeometry(
                Point.fromLngLat(latLng.longitude, latLng.latitude)
            )
        )
    }

    // ─────────────────────────────────────────────────────────
    // UI SETUP
    // ─────────────────────────────────────────────────────────

    private fun setupUI() {
        // Radius slider
        binding.seekBarRadius.max      = 39
        binding.seekBarRadius.progress = 1
        updateRadiusLabel(100)

        binding.seekBarRadius.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    alarmRadiusMeters = (50 + p * 50).coerceIn(50, 2000)
                    updateRadiusLabel(alarmRadiusMeters)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })

        // Drop pin hint
        binding.btnSetTarget.setOnClickListener {
            if (isMonitoring) { toast("Stop monitoring first"); return@setOnClickListener }
            toast("Long press anywhere on the map to drop a pin")
        }

        // Toggle search panel
        binding.btnToggleSearch.setOnClickListener {
            toggleSearchPanel()
        }

        // Search button
        binding.btnSearchAddress.setOnClickListener {
            val query = binding.etAddressSearch.text.toString().trim()
            if (query.isEmpty()) { toast("Please type a place name"); return@setOnClickListener }
            searchByAddress(query)
        }

        // Press Enter on keyboard to search
        binding.etAddressSearch.setOnEditorActionListener { _, _, _ ->
            binding.btnSearchAddress.performClick()
            true
        }

        // Search result buttons — all 3 wired individually (no listOf)
        binding.btnResult1.setOnClickListener {
            if (currentSearchResults.isNotEmpty()) selectSearchResult(0)
        }
        binding.btnResult2.setOnClickListener {
            if (currentSearchResults.size > 1) selectSearchResult(1)
        }
        binding.btnResult3.setOnClickListener {
            if (currentSearchResults.size > 2) selectSearchResult(2)
        }

        // GPS coordinates
        binding.btnSetCoords.setOnClickListener {
            setTargetFromCoordinates()
        }

        // Favourites bottom sheet
        binding.btnFavourites.setOnClickListener {
            FavouritesBottomSheet { fav ->
                val point = LatLng(fav.lat, fav.lon)
                setTarget(point, fav.name)
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(point, 15.0)
                )
            }.show(supportFragmentManager, FavouritesBottomSheet.TAG)
        }

        // History bottom sheet
        binding.btnHistory.setOnClickListener {
            HistoryBottomSheet().show(supportFragmentManager, HistoryBottomSheet.TAG)
        }

        // FAB my location
        binding.fabMyLocation.setOnClickListener {
            if (!hasLocationPermission()) {
                requestLocationPermissions()
                return@setOnClickListener
            }
            currentUserLatLng?.let { latLng ->
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(latLng, 16.0)
                )
            } ?: moveToCurrentLocation()
        }

        // Start / Stop
        binding.btnStartStop.setOnClickListener {
            if (isMonitoring) stopMonitoring() else startMonitoring()
        }

        // Dismiss alarm
        binding.btnDismissAlarm.setOnClickListener {
            dismissAlarm()
        }
    }

    private fun updateRadiusLabel(m: Int) {
        binding.tvRadius.text      = "${m}m"
        binding.tvRadiusStats.text = "${m}m"
    }

    // ─────────────────────────────────────────────────────────
    // SEARCH RESULT SELECTION
    // ─────────────────────────────────────────────────────────

    private fun selectSearchResult(index: Int) {
        val result = currentSearchResults[index]
        val point  = LatLng(result.lat, result.lon)
        setTarget(point, result.displayName)
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(point, 15.0)
        )
        collapseSearchPanel()
    }

    // ─────────────────────────────────────────────────────────
    // SEARCH PANEL TOGGLE
    // ─────────────────────────────────────────────────────────

    private fun toggleSearchPanel() {
        searchPanelVisible = !searchPanelVisible
        if (searchPanelVisible) {
            binding.searchPanel.visibility = View.VISIBLE
            binding.btnToggleSearch.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#1C3328"))
            binding.btnToggleSearch.setTextColor(Color.parseColor("#00C896"))
        } else {
            collapseSearchPanel()
        }
    }

    private fun collapseSearchPanel() {
        searchPanelVisible = false
        binding.searchPanel.visibility       = View.GONE
        binding.searchResultsPanel.visibility = View.GONE
        binding.etAddressSearch.setText("")
        binding.btnToggleSearch.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor("#1C2133"))
        binding.btnToggleSearch.setTextColor(Color.parseColor("#A0A8C0"))
    }

    // ─────────────────────────────────────────────────────────
    // SEARCH BY ADDRESS (Photon)
    // ─────────────────────────────────────────────────────────

    private fun searchByAddress(query: String) {
        if (isMonitoring) { toast("Stop monitoring before changing target"); return }

        toast("Searching...")
        binding.btnSearchAddress.isEnabled = false
        binding.btnSearchAddress.text      = "..."
        binding.searchResultsPanel.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val results = GeocodingHelper.search(query)

            withContext(Dispatchers.Main) {
                binding.btnSearchAddress.isEnabled = true
                binding.btnSearchAddress.text      = "Search"

                if (results.isEmpty()) {
                    toast("No results found. Try a different name.")
                    return@withContext
                }

                currentSearchResults = results

                // Show result buttons
                binding.btnResult1.visibility = View.GONE
                binding.btnResult2.visibility = View.GONE
                binding.btnResult3.visibility = View.GONE

                if (results.isNotEmpty()) {
                    binding.btnResult1.text      = "📍  ${results[0].displayName}"
                    binding.btnResult1.visibility = View.VISIBLE
                }
                if (results.size > 1) {
                    binding.btnResult2.text      = "📍  ${results[1].displayName}"
                    binding.btnResult2.visibility = View.VISIBLE
                }
                if (results.size > 2) {
                    binding.btnResult3.text      = "📍  ${results[2].displayName}"
                    binding.btnResult3.visibility = View.VISIBLE
                }

                binding.searchResultsPanel.visibility = View.VISIBLE
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // SET TARGET FROM COORDINATES
    // ─────────────────────────────────────────────────────────

    private fun setTargetFromCoordinates() {
        if (isMonitoring) { toast("Stop monitoring before changing target"); return }

        val latText = binding.etLatitude.text.toString().trim()
        val lonText = binding.etLongitude.text.toString().trim()

        if (latText.isEmpty() || lonText.isEmpty()) {
            toast("Please enter both latitude and longitude")
            return
        }

        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()

        if (lat == null || lon == null) {
            toast("Invalid coordinates — use numbers like 13.0827")
            return
        }
        if (lat !in -90.0..90.0) {
            toast("Latitude must be between -90 and 90")
            return
        }
        if (lon !in -180.0..180.0) {
            toast("Longitude must be between -180 and 180")
            return
        }

        val point = LatLng(lat, lon)
        setTarget(point, "%.4f, %.4f".format(lat, lon))
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(point, 15.0)
        )
        binding.etLatitude.setText("")
        binding.etLongitude.setText("")
        collapseSearchPanel()
    }

    // ─────────────────────────────────────────────────────────
    // LOCATION CALLBACK & PERMISSIONS
    // ─────────────────────────────────────────────────────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateUserLocation(it) }
            }
        }
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(
                this, missing.toTypedArray(), LOCATION_PERMISSION_REQUEST
            )
        else
            moveToCurrentLocation()
    }

    override fun onRequestPermissionsResult(
        code: Int, perms: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == LOCATION_PERMISSION_REQUEST &&
            results.isNotEmpty() &&
            results[0] == PackageManager.PERMISSION_GRANTED
        )
            moveToCurrentLocation()
        else
            toast("Location permission is required")
    }

    // ─────────────────────────────────────────────────────────
    // LOCATION
    // ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun moveToCurrentLocation() {
        if (!hasLocationPermission()) return

        // getCurrentLocation forces a fresh GPS fix
        // instead of returning a stale cached location
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY, null
        ).addOnSuccessListener { loc ->
            if (loc != null) {
                val point = LatLng(loc.latitude, loc.longitude)
                currentUserLatLng = point
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(point, 16.0)
                )
                updateBlueDot(point)
            } else {
                // Fresh fix returned null — fall back to last known
                fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                    lastLoc?.let {
                        val point = LatLng(it.latitude, it.longitude)
                        currentUserLatLng = point
                        mapLibreMap?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(point, 16.0)
                        )
                        updateBlueDot(point)
                    } ?: toast("Could not get location. Make sure GPS is on.")
                }
            }
        }.addOnFailureListener {
            toast("Location fetch failed. Check that GPS is enabled.")
        }
    }

    private fun updateUserLocation(location: Location) {
        binding.tvAccuracy.text = "${location.accuracy.roundToInt()}m"

        val point = LatLng(location.latitude, location.longitude)
        currentUserLatLng = point
        updateBlueDot(point)

        targetLatLng?.let { target ->
            val results = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                target.latitude, target.longitude,
                results
            )
            val distFloat = results[0]
            val dist      = distFloat.roundToInt()
            binding.tvDistance.text = "${dist}m"

            // Adapt GPS polling interval based on distance
            updateGpsInterval(distFloat)

            if (dist <= alarmRadiusMeters && !alarmTriggered) {
                alarmTriggered = true
                triggerAlarm(dist)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // TARGET
    // ─────────────────────────────────────────────────────────

    private fun setTarget(point: LatLng, name: String) {
        targetLatLng = point
        targetName   = name

        mapLibreMap?.annotations?.forEach { mapLibreMap?.removeAnnotation(it) }
        mapLibreMap?.addMarker(
            MarkerOptions().position(point).title(name)
        )
        toast("Target: $name")
    }

    // Exposed for FavouritesBottomSheet to read current target
    fun getCurrentTarget(): Pair<Double, Double>? =
        targetLatLng?.let { Pair(it.latitude, it.longitude) }

    // ─────────────────────────────────────────────────────────
    // MONITORING
    // ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (!hasLocationPermission()) { requestLocationPermissions(); return }
        if (targetLatLng == null) {
            toast("Long press on map to set a target first")
            return
        }

        isMonitoring   = true
        alarmTriggered = false

        // Start with far interval — will auto-adjust once distance is known
        currentIntervalMs = INTERVAL_FAR
        val request = buildLocationRequest(currentIntervalMs)
        fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)

        binding.btnStartStop.text = "⏹  Stop Monitoring"
        binding.btnStartStop.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor("#3DFF4D6D"))
        binding.btnStartStop.setTextColor(Color.parseColor("#FF4D6D"))
        binding.tvStatus.text = "MONITORING"
        binding.tvStatus.setTextColor(Color.parseColor("#00F5A0"))

        ContextCompat.startForegroundService(
            this, Intent(this, LocationForegroundService::class.java)
        )
        toast("Monitoring started")
    }

    private fun stopMonitoring() {
        isMonitoring = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopService(Intent(this, LocationForegroundService::class.java))
        stopAlarmSound()

        binding.btnStartStop.text = "▶  Start Monitoring"
        binding.btnStartStop.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor("#FF00F5A0"))
        binding.btnStartStop.setTextColor(Color.parseColor("#0A0C10"))
        binding.tvStatus.text = "IDLE"
        binding.tvStatus.setTextColor(Color.parseColor("#5A6278"))
        binding.tvDistance.text = "--"
        binding.tvAccuracy.text = "--"

        toast("Monitoring stopped")
    }

    // ─────────────────────────────────────────────────────────
    // ADAPTIVE GPS INTERVAL
    // ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun updateGpsInterval(distanceMeters: Float) {
        val newInterval = when {
            distanceMeters <= THRESHOLD_CLOSE  -> INTERVAL_CLOSE
            distanceMeters <= THRESHOLD_MEDIUM -> INTERVAL_MEDIUM
            else                               -> INTERVAL_FAR
        }

        // Only restart if interval actually changed — avoids unnecessary restarts
        if (newInterval != currentIntervalMs && isMonitoring) {
            currentIntervalMs = newInterval
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val request = buildLocationRequest(newInterval)
            if (hasLocationPermission()) {
                fusedLocationClient.requestLocationUpdates(
                    request, locationCallback, mainLooper
                )
            }
            val label = when (newInterval) {
                INTERVAL_CLOSE  -> "3s — close range"
                INTERVAL_MEDIUM -> "8s — medium range"
                else            -> "15s — far range"
            }
            toast("GPS interval: $label")
        }
    }

    private fun buildLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 3)
            .build()
    }

    // ─────────────────────────────────────────────────────────
    // ALARM
    // ─────────────────────────────────────────────────────────

    private fun triggerAlarm(dist: Int) {
        // Save to history first
        targetLatLng?.let { target ->
            AlarmHistoryManager.add(
                context        = this,
                locationName   = targetName.ifEmpty {
                    "%.4f, %.4f".format(target.latitude, target.longitude)
                },
                lat            = target.latitude,
                lon            = target.longitude,
                distanceMeters = dist,
                radiusMeters   = alarmRadiusMeters
            )
        }

        runOnUiThread {
            binding.alarmOverlay.visibility = View.VISIBLE
            binding.tvAlarmDist.text =
                "You are ${dist}m away — within ${alarmRadiusMeters}m zone!"
            binding.tvAlarmLocation.text = targetName
            binding.tvStatus.text = "ALERT!"
            binding.tvStatus.setTextColor(Color.parseColor("#FF4D6D"))

            startRgbAnimation()
            startBellPulse()
        }
        playAlarmSound()
        vibrateRepeat()
    }

    private fun dismissAlarm() {
        binding.alarmOverlay.visibility = View.GONE
        rgbAnimator?.cancel()
        stopAlarmSound()
        alarmTriggered = false
        binding.tvStatus.text = if (isMonitoring) "MONITORING" else "IDLE"
        binding.tvStatus.setTextColor(
            Color.parseColor(if (isMonitoring) "#00F5A0" else "#5A6278")
        )
    }

    // ─────────────────────────────────────────────────────────
    // RGB BORDER ANIMATION
    // ─────────────────────────────────────────────────────────

    private fun startRgbAnimation() {
        val colors = arrayOf<Any>(
            Color.RED,
            Color.YELLOW,
            Color.GREEN,
            Color.CYAN,
            Color.BLUE,
            Color.MAGENTA,
            Color.RED
        )

        rgbAnimator = ValueAnimator.ofObject(ArgbEvaluator(), *colors).apply {
            duration    = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART

            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                binding.rgbTop.setBackgroundColor(color)
                binding.rgbRight.setBackgroundColor(shiftHue(color, 0.25f))
                binding.rgbBottom.setBackgroundColor(shiftHue(color, 0.50f))
                binding.rgbLeft.setBackgroundColor(shiftHue(color, 0.75f))
            }
            start()
        }
    }

    // Shift hue by fraction (0.0–1.0) for running-light effect on each edge
    private fun shiftHue(color: Int, fraction: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + fraction * 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    // ─────────────────────────────────────────────────────────
    // ANIMATIONS
    // ─────────────────────────────────────────────────────────

    private fun startBellPulse() {
        val pulse = ScaleAnimation(
            1f, 1.3f, 1f, 1.3f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration    = 500L
            repeatCount = Animation.INFINITE
            repeatMode  = Animation.REVERSE
        }
        binding.tvAlarmBell.startAnimation(pulse)
    }

    private fun pulseFab() {
        val pulse = ScaleAnimation(
            1f, 1.2f, 1f, 1.2f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration    = 200L
            repeatCount = 1
            repeatMode  = Animation.REVERSE
        }
        binding.fabMyLocation.startAnimation(pulse)
    }

    // ─────────────────────────────────────────────────────────
    // SOUND & VIBRATION
    // ─────────────────────────────────────────────────────────

    private fun playAlarmSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(applicationContext, uri)
        ringtone?.play()

        alarmHandler  = Handler(Looper.getMainLooper())
        alarmRunnable = object : Runnable {
            override fun run() {
                if (ringtone?.isPlaying == false) ringtone?.play()
                alarmHandler?.postDelayed(this, 2000)
            }
        }
        alarmHandler?.postDelayed(alarmRunnable!!, 2000)
    }

    private fun vibrateRepeat() {
        val pattern = longArrayOf(0, 400, 200, 400, 200, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
    }

    private fun stopAlarmSound() {
        alarmHandler?.removeCallbacks(alarmRunnable ?: return)
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        binding.tvAlarmBell.clearAnimation()
    }

    // ─────────────────────────────────────────────────────────
    // UTILS
    // ─────────────────────────────────────────────────────────

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
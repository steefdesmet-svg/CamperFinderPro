package com.steefdesmet.camperfinderpro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.steefdesmet.camperfinderpro.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var locationOverlay: MyLocationNewOverlay
    private var currentLocation: GeoPoint? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            enableLocation()
        } else {
            binding.statusText.text = "Locatietoegang geweigerd"
            Toast.makeText(
                this,
                "U kunt nog steeds een plaatsnaam ingeven om daar camperplaatsen te zoeken.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupControls()
        requestLocation()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(7.0)
        binding.mapView.controller.setCenter(GeoPoint(46.6, 2.5))

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
        locationOverlay.enableFollowLocation()
        binding.mapView.overlays.add(locationOverlay)
    }

    private fun setupControls() {
        val radii = listOf("5 km", "10 km", "20 km", "30 km", "50 km", "75 km", "100 km")
        binding.radiusSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, radii)
        binding.radiusSpinner.setSelection(2)
        binding.searchButton.setOnClickListener { searchCamperPlaces() }
        binding.locationButton.setOnClickListener { centerOnLocation() }
        binding.placeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchCamperPlaces()
                true
            } else {
                false
            }
        }
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            enableLocation()
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enableLocation() {
        locationOverlay.enableMyLocation()
        locationOverlay.runOnFirstFix {
            runOnUiThread {
                val loc = locationOverlay.myLocation
                if (loc != null) {
                    currentLocation = loc
                    binding.statusText.text = String.format(
                        Locale.getDefault(),
                        "GPS-locatie: %.5f, %.5f",
                        loc.latitude,
                        loc.longitude
                    )
                    binding.mapView.controller.animateTo(loc)
                    binding.mapView.controller.setZoom(13.0)
                }
            }
        }
    }

    private fun centerOnLocation() {
        val loc = currentLocation ?: locationOverlay.myLocation
        if (loc == null) {
            Toast.makeText(this, "GPS-locatie is nog niet beschikbaar.", Toast.LENGTH_SHORT).show()
            return
        }
        currentLocation = loc
        binding.placeInput.text?.clear()
        binding.statusText.text = "Zoeken vanaf mijn GPS-locatie"
        binding.mapView.controller.animateTo(loc)
        binding.mapView.controller.setZoom(14.0)
    }

    private fun selectedRadiusKm(): Int =
        binding.radiusSpinner.selectedItem.toString().substringBefore(" ").toInt()

    private fun searchCamperPlaces() {
        val typedPlace = binding.placeInput.text?.toString()?.trim().orEmpty()
        val radiusKm = selectedRadiusKm()
        setLoading(
            true,
            if (typedPlace.isBlank()) {
                "Camperplaatsen zoeken binnen $radiusKm km…"
            } else {
                "Locatie ‘$typedPlace’ opzoeken…"
            }
        )

        lifecycleScope.launch {
            try {
                val originAndLabel = withContext(Dispatchers.IO) {
                    if (typedPlace.isNotBlank()) {
                        geocodePlace(typedPlace)
                            ?: error("Plaats niet gevonden. Voeg eventueel het land toe.")
                    } else {
                        val gps = currentLocation ?: locationOverlay.myLocation
                            ?: error("GPS-locatie is nog niet beschikbaar. Vul een plaatsnaam in.")
                        gps to "mijn GPS-locatie"
                    }
                }

                val origin = originAndLabel.first
                val originLabel = originAndLabel.second
                binding.statusText.text = "Zoeken vanaf $originLabel"
                binding.mapView.controller.animateTo(origin)
                binding.mapView.controller.setZoom(12.0)
                binding.resultText.text = "Camperplaatsen zoeken rond $originLabel…"

                val places = withContext(Dispatchers.IO) {
                    queryOverpass(origin, radiusKm)
                }
                showPlaces(places, origin, radiusKm, originLabel)
            } catch (e: Exception) {
                val message = e.message ?: "Onbekende fout"
                binding.resultText.text = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false, null)
            }
        }
    }

    private fun geocodePlace(query: String): Pair<GeoPoint, String>? {
        val url = Uri.Builder()
            .scheme("https")
            .authority("nominatim.openstreetmap.org")
            .appendPath("search")
            .appendQueryParameter("q", query)
            .appendQueryParameter("format", "jsonv2")
            .appendQueryParameter("limit", "1")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CamperFinderPro/1.0 (Android)")
            .header("Accept-Language", "nl,en;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Locatiezoekdienst gaf fout ${response.code}")
            val results = JSONArray(response.body?.string() ?: "[]")
            if (results.length() == 0) return null
            val result = results.getJSONObject(0)
            val lat = result.getString("lat").toDouble()
            val lon = result.getString("lon").toDouble()
            val label = result.optString("display_name").ifBlank { query }
            return GeoPoint(lat, lon) to label
        }
    }

    private fun queryOverpass(origin: GeoPoint, radiusKm: Int): List<CamperPlace> {
        val radiusM = radiusKm * 1000
        val query = """
            [out:json][timeout:35];
            (
              nwr["tourism"="caravan_site"](around:$radiusM,${origin.latitude},${origin.longitude});
              nwr["amenity"="parking"]["motorhome"="yes"](around:$radiusM,${origin.latitude},${origin.longitude});
              nwr["motorhome_stopover"="yes"](around:$radiusM,${origin.latitude},${origin.longitude});
            );
            out center tags;
        """.trimIndent()

        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(body)
            .header("User-Agent", "CamperFinderPro/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Camperplaatsendienst gaf fout ${response.code}")
            val json = JSONObject(response.body?.string() ?: error("Leeg antwoord"))
            val elements = json.getJSONArray("elements")
            val results = mutableListOf<CamperPlace>()
            val seen = mutableSetOf<String>()
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                val lat = when {
                    element.has("lat") -> element.getDouble("lat")
                    element.has("center") -> element.getJSONObject("center").getDouble("lat")
                    else -> continue
                }
                val lon = when {
                    element.has("lon") -> element.getDouble("lon")
                    element.has("center") -> element.getJSONObject("center").getDouble("lon")
                    else -> continue
                }
                val key = "%.5f,%.5f".format(Locale.US, lat, lon)
                if (!seen.add(key)) continue
                val tags = element.optJSONObject("tags") ?: JSONObject()
                val distance = distanceKm(origin.latitude, origin.longitude, lat, lon)
                results += CamperPlace(
                    id = element.optLong("id", i.toLong()),
                    latitude = lat,
                    longitude = lon,
                    name = tags.optString("name").ifBlank {
                        tags.optString("operator").ifBlank { "Camperplaats" }
                    },
                    distanceKm = distance,
                    fee = tagValue(tags, "fee"),
                    capacity = tagValue(tags, "capacity"),
                    electricity = tagValue(tags, "power_supply", "electricity"),
                    water = tagValue(tags, "drinking_water", "water_point"),
                    sanitaryDump = tagValue(tags, "sanitary_dump_station"),
                    maxstay = tagValue(tags, "maxstay"),
                    website = tagValue(tags, "website", "contact:website")
                )
            }
            return results.sortedBy { it.distanceKm }
        }
    }

    private fun tagValue(tags: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = tags.optString(key)
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0] / 1000.0
    }

    private fun showPlaces(
        places: List<CamperPlace>,
        origin: GeoPoint,
        radiusKm: Int,
        originLabel: String
    ) {
        binding.mapView.overlays.removeAll { it is Marker }
        places.forEachIndexed { index, place ->
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(place.latitude, place.longitude)
                title = "${index + 1}. ${place.name}"
                snippet = String.format(Locale.getDefault(), "%.1f km afstand", place.distanceKm)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ ->
                    showPlaceDialog(place)
                    true
                }
            }
            binding.mapView.overlays.add(marker)
        }
        binding.mapView.invalidate()
        binding.resultText.text = if (places.isEmpty()) {
            "Geen geregistreerde camperplaatsen gevonden binnen $radiusKm km van $originLabel."
        } else {
            "${places.size} camperplaats(en) gevonden rond $originLabel. Dichtstbij: ${places.first().name} (${String.format(Locale.getDefault(), "%.1f", places.first().distanceKm)} km)."
        }
        binding.mapView.controller.animateTo(origin)
        binding.mapView.controller.setZoom(zoomForRadius(radiusKm))
    }

    private fun zoomForRadius(radiusKm: Int): Double = when {
        radiusKm <= 5 -> 13.0
        radiusKm <= 10 -> 12.0
        radiusKm <= 20 -> 11.0
        radiusKm <= 50 -> 9.5
        else -> 8.5
    }

    private fun showPlaceDialog(place: CamperPlace) {
        fun yesNo(value: String?): String = when (value?.lowercase()) {
            "yes", "true", "1" -> "Ja"
            "no", "false", "0" -> "Nee"
            null, "" -> "Onbekend"
            else -> value
        }
        val message = buildString {
            appendLine("Afstand: ${String.format(Locale.getDefault(), "%.1f", place.distanceKm)} km")
            appendLine("Betalend: ${yesNo(place.fee)}")
            appendLine("Capaciteit: ${place.capacity ?: "Onbekend"}")
            appendLine("Elektriciteit: ${yesNo(place.electricity)}")
            appendLine("Drinkwater: ${yesNo(place.water)}")
            appendLine("Loospunt: ${yesNo(place.sanitaryDump)}")
            appendLine("Maximumverblijf: ${place.maxstay ?: "Onbekend"}")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(place.name)
            .setMessage(message)
            .setPositiveButton("NAVIGEER") { _, _ -> navigateTo(place) }
            .setNegativeButton("SLUITEN", null)
        if (!place.website.isNullOrBlank()) {
            dialog.setNeutralButton("WEBSITE") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.website))) }
            }
        }
        dialog.show()
    }

    private fun navigateTo(place: CamperPlace) {
        val uri = Uri.parse("google.navigation:q=${place.latitude},${place.longitude}&mode=d")
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.apps.maps")
        if (mapsIntent.resolveActivity(packageManager) != null) {
            startActivity(mapsIntent)
        } else {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})"
                    )
                )
            )
        }
    }

    private fun setLoading(loading: Boolean, text: String?) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.searchButton.isEnabled = !loading
        binding.placeInput.isEnabled = !loading
        if (text != null) binding.resultText.text = text
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        locationOverlay.disableMyLocation()
        super.onDestroy()
    }
}
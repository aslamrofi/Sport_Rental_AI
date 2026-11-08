package com.sportrental.ai.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sportrental.ai.R
import com.sportrental.ai.databinding.FragmentHomeBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

data class SportItem(
    val name: String,
    val hourlyRate: Int,
    val placeTypes: List<String>
)

data class Venue(
    val name: String,
    val url: String,
    val distanceKm: Double
)

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var placesClient: PlacesClient
    private var userId: String = ""

    private var selectedSport: SportItem? = null
    private var selectedLocation: String? = null
    private var selectedVenueUrl: String? = null
    private var bookingDateMillis: Long? = null

    private val sportsList = listOf(
        SportItem("Badminton 🏸", 20, listOf("sports_complex", "sports_club")),
        SportItem("Futsal ⚽", 50, listOf("sports_complex", "stadium")),
        SportItem("Tennis 🎾", 30, listOf("sports_complex", "park", "sports_club")),
        SportItem("Basketball 🏀", 40, listOf("sports_complex", "park", "athletic_field")),
        SportItem("Squash 🏸", 25, listOf("sports_complex", "sports_club")),
        SportItem("Volleyball 🏐", 30, listOf("sports_complex", "park")),
        SportItem("Swimming 🏊", 15, listOf("swimming_pool", "sports_complex")),
        SportItem("University Sports Complex 🏛️", 35, listOf("university", "sports_complex", "stadium"))
    )

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            fetchDeviceLocationAndSearchGoogleMaps()
        } else {
            Toast.makeText(context, "Location permission is required to find nearby venues.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        database = FirebaseDatabase.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        val prefs = requireActivity().getSharedPreferences("SportRentalPrefs", android.content.Context.MODE_PRIVATE)
        userId = prefs.getString("username", "anonymous") ?: "anonymous"

        if (!Places.isInitialized()) {
            Places.initialize(requireContext(), getString(R.string.google_maps_key))
        }
        placesClient = Places.createClient(requireContext())

        setupSportSpinner()
        setupLocationSelection()
        setupDatePicker()
        setupReservationConfirmation()

        binding.btnTestOpenAi.setOnClickListener {
            val currentSport = selectedSport?.name ?: sportsList[0].name
            fetchLiveOpenAiSafetyAndNews(currentSport)
        }

        return binding.root
    }

    private fun setupSportSpinner() {
        val sportNames = sportsList.map { it.name }
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, sportNames)
        adapter.setDropDownViewResource(R.layout.spinner_item)

        binding.spinnerEquipment.adapter = adapter
        selectedSport = sportsList[0]

        binding.spinnerEquipment.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSport = sportsList[position]

                binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
                selectedLocation = null
                selectedVenueUrl = null
                binding.tvLocationLink.visibility = View.GONE
                binding.tvAiOpinion.visibility = View.GONE

                fetchLiveOpenAiSafetyAndNews(selectedSport!!.name)
                updateBookingDisplay()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun cleanMarkdown(text: String): String {
        return text
            .replace(Regex("##+\\s*"), "")           // Remove ## or ### headers
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // Remove ** bold
            .replace(Regex("\\*(.*?)\\*"), "$1")       // Remove * italic
            .trim()
    }

    private fun fetchLiveOpenAiSafetyAndNews(sportName: String) {
        binding.tvPersonalizedRecommendation.text = "🤖 Loading safety guidelines for $sportName..."
        binding.tvSportsNews.text = "📰 Loading live Google News for $sportName..."

        // 1. Fetch real Google News article using Google News Light API via SerpApi
        fetchGoogleNewsForSport(sportName)

        // 2. Fetch AI Safety Guidelines
        val apiKey = getString(R.string.openai_api_key)
        val client = OkHttpClient()

        val prompt = "Give me 2 concise bullet points on essential court safety precautions and warm-up rules for $sportName."

        val jsonPayload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val body = RequestBody.create("application/json; charset=utf-8".toMediaType(), jsonPayload.toString())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.tvPersonalizedRecommendation.text = "🤖 Safety Guidelines ($sportName):\n• Warm up thoroughly before playing.\n• Wear proper non-marking sports shoes."
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                activity?.runOnUiThread {
                    if (_binding != null) {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                val content = JSONObject(responseBody)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                    .trim()

                                val cleanContent = cleanMarkdown(content)
                                binding.tvPersonalizedRecommendation.text = "🤖 Safety & Warm-Up Guidelines ($sportName):\n$cleanContent"
                            } catch (e: Exception) {
                                binding.tvPersonalizedRecommendation.text = "🤖 Safety Guidelines ($sportName):\n• Do a 10-minute warm-up session before playing.\n• Inspect court conditions and wear appropriate footwear."
                            }
                        } else {
                            binding.tvPersonalizedRecommendation.text = "🤖 Safety Guidelines ($sportName):\n• Warm up properly before playing.\n• Follow court safety regulations."
                        }
                    }
                }
            }
        })
    }

    private fun fetchGoogleNewsForSport(sportName: String) {
        val cleanSport = sportName.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val query = "$cleanSport news"
        val serpApiKey = getString(R.string.serp_api_key)

        val url = "https://serpapi.com/search.json?engine=google_news_light&q=${Uri.encode(query)}&api_key=$serpApiKey"

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.tvSportsNews.text = "📰 Google News ($cleanSport):\n• Unable to fetch live news feed. Please check internet connection."
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread

                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            val newsResults = json.optJSONArray("news_results")

                            if (newsResults != null && newsResults.length() > 0) {
                                val article = newsResults.getJSONObject(0)
                                val title = cleanMarkdown(article.optString("title", "").trim())
                                val source = article.optString("source", "Google News").trim()
                                val date = article.optString("date", "Recent").trim()

                                val newsText = "• \"$title\"\n   — 📰 Source: $source ($date)"
                                binding.tvSportsNews.text = "📰 Live Google News ($cleanSport):\n\n$newsText"
                            } else {
                                binding.tvSportsNews.text = "📰 Live Google News ($cleanSport):\n• No recent news articles found for $cleanSport."
                            }
                        } catch (e: Exception) {
                            Log.e("GoogleNewsError", "Error parsing Google News JSON", e)
                            binding.tvSportsNews.text = "📰 Live Google News ($cleanSport):\n• Error parsing news response."
                        }
                    } else {
                        binding.tvSportsNews.text = "📰 Live Google News ($cleanSport):\n• Search service busy. Please try again."
                    }
                }
            }
        })
    }

    private fun setupLocationSelection() {
        binding.btnSelectLocation.setOnClickListener {
            if (selectedSport == null) {
                Toast.makeText(context, "Please select a sport category first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fetchDeviceLocationAndSearchGoogleMaps()
            } else {
                locationPermissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }
    }

    private fun fetchDeviceLocationAndSearchGoogleMaps() {
        binding.btnSelectLocation.text = "📡 Locking GPS coordinates..."
        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        searchNearbySportsVenues(location.latitude, location.longitude)
                    } else {
                        Toast.makeText(context, "Unable to detect GPS location. Please ensure Location Services (GPS) are enabled.", Toast.LENGTH_LONG).show()
                        binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e("Location", "Failed to get location: ${exception.message}")
                    Toast.makeText(context, "Location request failed. Please check your GPS connection.", Toast.LENGTH_SHORT).show()
                    binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
                }
        } catch (e: SecurityException) {
            Log.e("Location", "Security Exception: ${e.message}")
            Toast.makeText(context, "Location permission denied.", Toast.LENGTH_SHORT).show()
            binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
        }
    }

    private fun getAddressFromCoordinates(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val fullAddress = address.getAddressLine(0)
                if (!fullAddress.isNullOrEmpty()) {
                    fullAddress
                } else {
                    val subLocality = address.subLocality
                    val locality = address.locality ?: address.adminArea
                    if (!subLocality.isNullOrEmpty() && !locality.isNullOrEmpty()) {
                        "$subLocality, $locality"
                    } else locality ?: String.format(Locale.US, "Lat: %.4f, Lng: %.4f", lat, lng)
                }
            } else {
                String.format(Locale.US, "Lat: %.4f, Lng: %.4f", lat, lng)
            }
        } catch (e: Exception) {
            String.format(Locale.US, "Lat: %.4f, Lng: %.4f", lat, lng)
        }
    }

    private fun searchNearbySportsVenues(userLat: Double, userLng: Double) {
        val sport = selectedSport
        if (sport == null) {
            Toast.makeText(context, "Please select a sport category first.", Toast.LENGTH_SHORT).show()
            binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
            return
        }

        val cleanSportName = sport.name.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val searchQuery = "$cleanSportName court"

        val locationName = getAddressFromCoordinates(userLat, userLng)

        binding.tvCurrentLocation.text = "📍 Your Location:\n$locationName"
        binding.btnSelectLocation.text = "🔍 Searching '$searchQuery' (5 km)..."

        // 5 km bounding box for strict LocationRestriction
        val latOffset = 5.0 / 111.0
        val lngOffset = 5.0 / (111.0 * kotlin.math.cos(Math.toRadians(userLat)))
        val southWest = LatLng(userLat - latOffset, userLng - lngOffset)
        val northEast = LatLng(userLat + latOffset, userLng + lngOffset)
        val rectBounds = com.google.android.libraries.places.api.model.RectangularBounds.newInstance(southWest, northEast)

        val placeFields = listOf(Place.Field.NAME, Place.Field.WEBSITE_URI, Place.Field.LAT_LNG)

        val searchByTextRequest = SearchByTextRequest.builder(searchQuery, placeFields)
            .setLocationRestriction(rectBounds)
            .setMaxResultCount(10)
            .build()

        placesClient.searchByText(searchByTextRequest)
            .addOnSuccessListener { response ->
                val dynamicVenues = response.places.mapNotNull { place ->
                    val placeName = place.name ?: return@mapNotNull null
                    val placeUrl = place.websiteUri?.toString() ?: "https://www.google.com/search?q=${placeName.replace(" ", "+")}"
                    val placeLatLng = place.latLng ?: return@mapNotNull null

                    val distanceResults = FloatArray(1)
                    android.location.Location.distanceBetween(
                        userLat, userLng,
                        placeLatLng.latitude, placeLatLng.longitude,
                        distanceResults
                    )

                    val distanceKm = distanceResults[0] / 1000.0
                    if (distanceKm <= 5.0) {
                        Venue(placeName, placeUrl, distanceKm)
                    } else null
                }.sortedBy { it.distanceKm }

                if (dynamicVenues.isNotEmpty()) {
                    showDynamicVenuesDialog(dynamicVenues)
                } else {
                    Toast.makeText(context, "No live $cleanSportName venues found within 5 km of your location.", Toast.LENGTH_LONG).show()
                    binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
                }
            }
            .addOnFailureListener { exception ->
                Log.e("PlacesAPI", "SearchByText Places API error: ${exception.message}")
                val msg = exception.message ?: ""
                val errMsg = when {
                    msg.contains("Unable to resolve host", true) || msg.contains("7") -> {
                        "📶 Network Error: Unable to connect to Google Places. Please check your internet / Wi-Fi connection."
                    }
                    msg.contains("9011") -> {
                        "Places API permission error (9011). Please enable 'Places API (New)' in Google Cloud Console."
                    }
                    else -> {
                        "Failed to search nearby venues: ${exception.localizedMessage}"
                    }
                }
                Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
                binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
            }
    }

    private fun showDynamicVenuesDialog(venues: List<Venue>) {
        if (venues.isEmpty()) {
            Toast.makeText(context, "No venues found within 5km.", Toast.LENGTH_SHORT).show()
            binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
            return
        }

        val displayNames = venues.map { String.format(Locale.US, "%s (%.1f km away)", it.name, it.distanceKm) }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Available Venues for ${selectedSport?.name}")
            .setItems(displayNames) { _, which ->
                val selectedVenue = venues[which]

                selectedLocation = selectedVenue.name
                selectedVenueUrl = selectedVenue.url
                binding.btnSelectLocation.text = "📍 $selectedLocation"

                binding.tvLocationLink.visibility = View.VISIBLE
                binding.tvLocationLink.text = "🌐 Visit Official Website"
                binding.tvLocationLink.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(selectedVenueUrl))
                    startActivity(intent)
                }
            }
            .show()
    }

    private fun setupDatePicker() {
        binding.btnSelectDates.setOnClickListener {
            val builder = MaterialDatePicker.Builder.datePicker()
            builder.setTitleText("Select Booking Date")
            val picker = builder.build()
            picker.addOnPositiveButtonClickListener { selection ->
                bookingDateMillis = selection
                updateBookingDisplay()
            }
            picker.show(childFragmentManager, "date_picker")
        }
    }

    private fun updateBookingDisplay() {
        if (bookingDateMillis != null && selectedSport != null) {
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date(bookingDateMillis!!))

            binding.tvSelectedDates.text = "Date: $dateStr"
        }
    }

    private fun setupReservationConfirmation() {
        binding.btnConfirmRental.setOnClickListener {
            if (selectedSport == null || bookingDateMillis == null || selectedLocation == null) {
                Toast.makeText(context, "Please select a sport, location, and date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val dateStr = sdf.format(Date(bookingDateMillis!!))

            // DOUBLE BOOKING PREVENTION CHECK
            database.child("rentals").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var isDoubleBooked = false

                    for (rental in snapshot.children) {
                        val status = rental.child("status").value?.toString() ?: ""
                        if (status.lowercase() == "cancelled") continue

                        val loc = rental.child("location").value?.toString()
                        val date = rental.child("bookingDateStr").value?.toString()

                        if (loc == selectedLocation && date == dateStr) {
                            isDoubleBooked = true
                            break
                        }
                    }

                    if (isDoubleBooked) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("⚠️ Venue Already Reserved!")
                            .setMessage("Sorry! $selectedLocation is already reserved for $dateStr. Please choose another date or venue.")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        saveBookingToFirebase(dateStr)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(context, "Error checking availability: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun saveBookingToFirebase(dateStr: String) {
        val rentalId = database.child("rentals").push().key ?: return

        val savedSportName = selectedSport!!.name
        val savedLocation = selectedLocation!!

        val rentalData = mapOf(
            "userId" to userId,
            "sportName" to savedSportName,
            "itemName" to savedSportName,
            "location" to savedLocation,
            "venueUrl" to (selectedVenueUrl ?: ""),
            "bookingDateMillis" to bookingDateMillis,
            "bookingDateStr" to dateStr,
            "dailyRate" to selectedSport!!.hourlyRate,
            "totalPrice" to selectedSport!!.hourlyRate,
            "timestamp" to ServerValue.TIMESTAMP,
            "status" to "active"
        )

        database.child("rentals").child(rentalId).setValue(rentalData)
            .addOnSuccessListener {
                Toast.makeText(context, "🎉 Reservation Confirmed Successfully!", Toast.LENGTH_LONG).show()
                fetchAIVenueAdvice(savedSportName, savedLocation, dateStr)

                // Clear location and date fields after successful reservation (preserve selected sport category)
                selectedLocation = null
                selectedVenueUrl = null
                bookingDateMillis = null

                binding.btnSelectLocation.text = "Find Venues Near Me (5 km)"
                binding.tvLocationLink.visibility = View.GONE
                binding.tvSelectedDates.text = "No date selected"
            }
    }

    private fun fetchAIVenueAdvice(sport: String, location: String, dateStr: String) {
        binding.tvAiOpinion.visibility = View.VISIBLE
        binding.tvAiOpinion.text = "🤖 AI Venue Assistant: Analyzing booking..."

        val apiKey = getString(R.string.openai_api_key)
        val client = OkHttpClient()

        val prompt = "I just booked $location for $sport on $dateStr. Give me a short 2-sentence encouraging tip on venue preparation."

        val jsonPayload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val body = RequestBody.create("application/json; charset=utf-8".toMediaType(), jsonPayload.toString())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.tvAiOpinion.text = "🤖 AI Advice: Have a great match!"
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val content = JSONObject(responseBody).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content")

                        activity?.runOnUiThread {
                            if (_binding != null) {
                                val cleanAdvice = cleanMarkdown(content)
                                binding.tvAiOpinion.text = "🤖 AI Advice: $cleanAdvice"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AI_ERROR", "Parsing error", e)
                    }
                }
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

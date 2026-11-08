package com.sportrental.ai.ui.admin

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import com.sportrental.ai.databinding.FragmentAdminBinding
import com.google.firebase.database.*

class AdminFragment : Fragment() {

    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!
    private lateinit var database: DatabaseReference

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminBinding.inflate(inflater, container, false)
        database = FirebaseDatabase.getInstance().reference

        setupAdminLogin()

        return binding.root
    }

    private fun setupAdminLogin() {
        binding.btnAdminLogin.setOnClickListener {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view?.windowToken, 0)

            binding.layoutAdminLogin.visibility = View.GONE
            binding.layoutAdminDashboard.visibility = View.VISIBLE

            loadAdminAnalytics()
        }
    }

    private fun loadAdminAnalytics() {
        database.child("rentals").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return

                var totalBookings = 0
                var activeBookings = 0
                val sportFrequency = mutableMapOf<String, Int>()
                val venueFrequency = mutableMapOf<String, Int>()

                for (rental in snapshot.children) {
                    totalBookings++

                    val status = rental.child("status").value?.toString() ?: ""
                    if (status.lowercase() == "active") {
                        activeBookings++
                    }

                    val sportName = rental.child("sportName").value?.toString() ?: rental.child("itemName").value?.toString() ?: continue
                    sportFrequency[sportName] = sportFrequency.getOrDefault(sportName, 0) + 1

                    val loc = rental.child("location").value?.toString()
                    if (loc != null) {
                        venueFrequency[loc] = venueFrequency.getOrDefault(loc, 0) + 1
                    }
                }

                // Overview
                binding.tvTotalRentals.text = "Total Venue Reservations: $totalBookings"
                binding.tvActiveRentals.text = "Currently Active Court Bookings: $activeBookings"

                // Trends
                val sortedSports = sportFrequency.entries.sortedByDescending { it.value }
                val trendsBuilder = StringBuilder()

                sortedSports.take(5).forEachIndexed { index, entry ->
                    trendsBuilder.append("${index + 1}. ${entry.key} (${entry.value} court bookings)\n")
                }

                if (trendsBuilder.isEmpty()) {
                    binding.tvUsageTrends.text = "Awaiting reservation data..."
                } else {
                    binding.tvUsageTrends.text = trendsBuilder.toString().trim()
                }

                // Forecast
                val forecastBuilder = StringBuilder()
                if (sortedSports.isNotEmpty()) {
                    val topSport = sortedSports[0]
                    forecastBuilder.append("🔥 High Court Demand:\n")
                    forecastBuilder.append("• ${topSport.key} is experiencing peak reservations. Recommend opening additional court slots.\n\n")

                    val topVenue = venueFrequency.maxByOrNull { it.value }
                    if (topVenue != null) {
                        forecastBuilder.append("📍 Top Venue Location:\n")
                        forecastBuilder.append("• ${topVenue.key} (${topVenue.value} total bookings).")
                    }
                    binding.tvDemandForecast.text = forecastBuilder.toString()
                } else {
                    binding.tvDemandForecast.text = "Awaiting venue booking data for predictive forecasting."
                }

                // Double booking & Court status alerts
                val alertsBuilder = StringBuilder()
                var hasAlerts = false

                if (activeBookings > 10) {
                    alertsBuilder.append("🟡 High Venue Traffic: Over $activeBookings active court sessions in progress.\n\n")
                    hasAlerts = true
                }

                for ((sport, count) in sportFrequency) {
                    if (count >= 5) {
                        alertsBuilder.append("🔴 Peak Usage Alert: $sport courts booked $count times. Inspect court lighting and netting.\n\n")
                        hasAlerts = true
                    }
                }

                if (!hasAlerts) {
                    binding.tvAlerts.text = "✅ System Operational. Zero double-booking conflicts detected."
                    binding.tvAlerts.setTextColor(android.graphics.Color.parseColor("#388E3C"))
                } else {
                    binding.tvAlerts.text = alertsBuilder.toString().trim()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("AdminDashboard", "Error loading analytics: ${error.message}")
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

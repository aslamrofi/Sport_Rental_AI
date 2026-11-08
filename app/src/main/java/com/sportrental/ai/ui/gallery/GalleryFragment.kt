package com.sportrental.ai.ui.gallery

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sportrental.ai.databinding.FragmentGalleryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private lateinit var database: DatabaseReference

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        database = FirebaseDatabase.getInstance().reference

        loadPersonalBookings()

        return binding.root
    }

    private fun loadPersonalBookings() {
        val prefs = requireActivity().getSharedPreferences("SportRentalPrefs", android.content.Context.MODE_PRIVATE)
        val currentUserId = prefs.getString("username", null)

        if (currentUserId != null) {
            database.child("rentals")
                .orderByChild("userId")
                .equalTo(currentUserId)
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (_binding == null) return

                        binding.historyListContainer.removeAllViews()
                        val bookingCount = snapshot.childrenCount
                        binding.textGallery.text = "You have $bookingCount venue reservations."

                        if (bookingCount == 0L) {
                            val emptyText = TextView(context).apply {
                                text = "No sports venue bookings found."
                                setPadding(0, 32, 0, 0)
                                setTextColor(Color.WHITE)
                                textSize = 16f
                            }
                            binding.historyListContainer.addView(emptyText)
                            return
                        }

                        val rentals = snapshot.children.reversed()
                        for (item in rentals) {
                            val bookingId = item.key ?: continue
                            val sportName = item.child("sportName").value?.toString() ?: item.child("itemName").value?.toString() ?: "Sport"
                            val location = item.child("location").value?.toString() ?: "Unknown Venue"
                            val dateStr = item.child("bookingDateStr").value?.toString() ?: "N/A"
                            val status = item.child("status").value?.toString() ?: "active"
                            val venueUrl = item.child("venueUrl").value?.toString()

                            val emoji = getEmojiForSport(sportName)

                            val cardLayout = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(24, 24, 24, 24)
                                setBackgroundColor(Color.parseColor("#1A1A2E"))
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 16, 0, 16)
                                }
                            }

                            val infoText = TextView(context).apply {
                                val statusBadge = if (status.lowercase() == "active") "🟢 ACTIVE" else "🔴 CANCELLED"
                                text = "$emoji $sportName\n📍 Venue: $location\n📅 Date: $dateStr\nStatus: $statusBadge"
                                textSize = 15f
                                setTextColor(Color.WHITE)
                                setLineSpacing(6f, 1f)
                            }
                            cardLayout.addView(infoText)

                            // Action Buttons
                            val buttonRow = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(0, 16, 0, 0)
                            }

                            if (!venueUrl.isNull_or_Empty()) {
                                val webBtn = Button(context).apply {
                                    text = "🌐 Website"
                                    textSize = 12f
                                    setOnClickListener {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(venueUrl))
                                        startActivity(intent)
                                    }
                                }
                                buttonRow.addView(webBtn)
                            }

                            if (status.lowercase() == "active") {
                                val cancelBtn = Button(context).apply {
                                    text = "❌ Cancel"
                                    textSize = 12f
                                    setTextColor(Color.RED)
                                    setOnClickListener {
                                        cancelReservation(bookingId)
                                    }
                                }
                                buttonRow.addView(cancelBtn)
                            }

                            cardLayout.addView(buttonRow)
                            binding.historyListContainer.addView(cardLayout)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (_binding == null) return
                        Log.e("HistoryError", error.message)
                        binding.textGallery.text = "Error loading bookings."
                    }
                })
        } else {
            binding.textGallery.text = "Please log in to view reservations."
        }
    }

    private fun cancelReservation(bookingId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Cancel Reservation")
            .setMessage("Are you sure you want to cancel this booking? This court slot will become available for others.")
            .setPositiveButton("Yes, Cancel") { _, _ ->
                database.child("rentals").child(bookingId).child("status").setValue("cancelled")
                    .addOnSuccessListener {
                        Toast.makeText(context, "Reservation Cancelled", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun String?.isNull_or_Empty(): Boolean = this == null || this.trim().isEmpty()

    private fun getEmojiForSport(sportName: String): String {
        return when {
            sportName.contains("Badminton", true) -> "🏸"
            sportName.contains("Futsal", true) -> "⚽"
            sportName.contains("Basketball", true) -> "🏀"
            sportName.contains("Tennis", true) -> "🎾"
            sportName.contains("Squash", true) -> "🏸"
            sportName.contains("Volleyball", true) -> "🏐"
            sportName.contains("Swimming", true) -> "🏊"
            sportName.contains("University", true) -> "🏛️"
            else -> "🏟️"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

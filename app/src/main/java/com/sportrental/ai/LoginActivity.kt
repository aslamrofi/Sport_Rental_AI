package com.sportrental.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sportrental.ai.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is already logged in
        val prefs = getSharedPreferences("SportRentalPrefs", Context.MODE_PRIVATE)
        val savedUsername = prefs.getString("username", null)
        if (!savedUsername.isNullOrEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // LOGIN BUTTON (Passwordless)
        binding.btnLogin.setOnClickListener {
            val usernameInput = binding.etUsername.text.toString().trim()

            if (usernameInput.isEmpty()) {
                Toast.makeText(this, "Please enter your username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val cleanUsername = usernameInput.replace(" ", "").lowercase()

            // Store username locally in SharedPreferences
            prefs.edit().putString("username", cleanUsername).apply()

            Toast.makeText(this, "Welcome to SportRental AI, $usernameInput!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}

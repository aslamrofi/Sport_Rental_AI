package com.sportrental.ai.ui.advice

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import com.sportrental.ai.R
import com.sportrental.ai.databinding.FragmentAdviceBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class AdviceFragment : Fragment() {

    private var _binding: FragmentAdviceBinding? = null
    private val binding get() = _binding!!

    private var selectedSport: String = "Badminton 🏸"
    private val conversationHistory = mutableListOf<JSONObject>()

    private val sportTopics = listOf(
        "Badminton 🏸", "Futsal ⚽", "Tennis 🎾", "Basketball 🏀",
        "Squash 🏸", "Volleyball 🏐", "Swimming 🏊", "University Sports Complex 🏛️"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdviceBinding.inflate(inflater, container, false)

        setupSpinner()

        binding.btnSend.setOnClickListener {
            val question = binding.etUserQuestion.text.toString().trim()
            if (question.isNotEmpty()) {
                askAIForeAdvice(question)
                binding.etUserQuestion.text.clear()

                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(view?.windowToken, 0)
            }
        }

        resetChatHistory("Welcome to **SportRental AI Coach**! Ask me anything about venue guidelines, court rules, safety precautions, or latest news in $selectedSport.")

        return binding.root
    }

    private fun resetChatHistory(welcomeMessage: String) {
        binding.chatContainer.removeAllViews()
        conversationHistory.clear()

        val systemPrompt = "You are SportRental AI's Sports Coach and Venue Advisor for $selectedSport. Give concise, highly accurate, and friendly answers regarding court rules, safety precautions, warm-up tips, and sports news updates. Remember the context of our ongoing conversation. Use Markdown bolding for emphasis."

        conversationHistory.add(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        addMessageToChat("AI Coach", welcomeMessage)
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, sportTopics)
        adapter.setDropDownViewResource(R.layout.spinner_item)

        binding.spinnerSportTopic.adapter = adapter

        binding.spinnerSportTopic.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSport = sportTopics[position]
                resetChatHistory("Switched category to **$selectedSport**. How can I help with venue rules, coaching tips, or sports news?")
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun askAIForeAdvice(question: String) {
        addMessageToChat("You", question)

        // Add user question to conversation history
        val userMsgJson = JSONObject().apply {
            put("role", "user")
            put("content", question)
        }
        conversationHistory.add(userMsgJson)

        val apiKey = getString(R.string.openai_api_key)
        val client = OkHttpClient()

        // Build messages array containing full conversation context
        val messagesArray = JSONArray()
        for (msg in conversationHistory) {
            messagesArray.put(msg)
        }

        val jsonPayload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", messagesArray)
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
                    addMessageToChat("Error", "Network error: ${e.message}")
                    if (conversationHistory.isNotEmpty() && conversationHistory.last() == userMsgJson) {
                        conversationHistory.removeAt(conversationHistory.size - 1)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()

                activity?.runOnUiThread {
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val content = JSONObject(responseBody).getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").getString("content")
                            
                            val assistantContent = content.trim()

                            // Store AI response into conversation history to remember context
                            conversationHistory.add(JSONObject().apply {
                                put("role", "assistant")
                                put("content", assistantContent)
                            })

                            addMessageToChat("AI Coach", assistantContent)
                        } catch (e: Exception) {
                            addMessageToChat("System", "Failed to parse response.")
                            Log.e("AI_ERROR", "Parsing error", e)
                        }
                    } else {
                        addMessageToChat("System Error", "API request failed with code: ${response.code}")
                    }
                }
            }
        })
    }

    private fun cleanMarkdown(text: String): String {
        return text
            .replace(Regex("##+\\s*"), "")           // Remove ## or ### headers
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // Remove ** bold
            .replace(Regex("\\*(.*?)\\*"), "$1")       // Remove * italic
            .trim()
    }

    private fun addMessageToChat(sender: String, message: String) {
        if (_binding == null) return

        val cleanMsg = cleanMarkdown(message).replace("\n", "<br>")
        val finalHtml = "<b>$sender:</b><br><br>$cleanMsg"

        val messageView = TextView(context).apply {
            text = HtmlCompat.fromHtml(finalHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
            textSize = 15f
            setPadding(24, 24, 24, 24)

            if (sender == "You") {
                setBackgroundColor(android.graphics.Color.parseColor("#E3F2FD"))
                setTextColor(android.graphics.Color.parseColor("#0D47A1"))
            } else {
                setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
                setTextColor(android.graphics.Color.parseColor("#212121"))
            }

            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 12, 0, 12)
            }
        }

        binding.chatContainer.addView(messageView)

        binding.chatScrollView.post {
            binding.chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

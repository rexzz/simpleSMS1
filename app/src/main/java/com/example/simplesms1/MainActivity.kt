package com.example.simplesms1

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.speech.tts.TextToSpeech.LANG_AVAILABLE
import android.speech.tts.TextToSpeech.LANG_MISSING_DATA

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val receivedMessages = MutableStateFlow<List<String>>(emptyList())  // Use list to store messages
    private lateinit var textToSpeech: TextToSpeech
    var isTtsEnabled by mutableStateOf(false)
    var isVoiceSendingEnabled by mutableStateOf(false)
    private var currentVoiceState = VoiceState.IDLE
    private var currentRecipient: String? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false
    private var lastErrorTime = 0L
    private val ERROR_RETRY_DELAY = 1000L // 1 second delay between error retries
    private val MAX_CONSECUTIVE_ERRORS = 3
    private var consecutiveErrors = 0
    private var isDestroyed = false
    private val LISTENING_RESTART_DELAY = 2000L // 2 seconds delay between listening sessions
    private val MIN_SPEECH_LENGTH = 2000 // 2 seconds minimum speech length
    private val SILENCE_LENGTH = 4000 // 4 seconds silence before considering speech complete
    private val MESSAGE_SILENCE_LENGTH = 6000 // 6 seconds silence when waiting for message

    private enum class VoiceState {
        IDLE,
        WAITING_FOR_RECIPIENT,
        WAITING_FOR_MESSAGE
    }

    // Register activity result before onResume
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.all { it.value }
            if (granted) {
                // Permission granted, proceed with sending SMS
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
                //sendSms("6462835775","run")
            } else {
                // Handle the case when permission is denied
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            // Permission granted, you can now use the microphone for voice input
            //startVoiceInput()
            Toast.makeText(this, "Audio Permission granted", Toast.LENGTH_SHORT).show()
        } else {
            // Permission denied, show a message or handle the scenario
            Toast.makeText(this, "Audio Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_TOGGLE_TTS = "com.example.simplesms1.ACTION_TOGGLE_TTS"
        const val ACTION_TOGGLE_VOICE_SEND = "com.example.simplesms1.ACTION_TOGGLE_VOICE_SEND"
        const val EXTRA_TTS_STATE = "tts_state"
        const val EXTRA_VOICE_SEND_STATE = "voice_send_state"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle incoming intents
        handleIntent(intent)

        /*if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        */

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestReceiveSmsPermission()
        } else {
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
        }

        requestPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS
            )
        )

        textToSpeech = TextToSpeech(this, OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = textToSpeech.setLanguage(Locale.getDefault())
                if (langResult == LANG_MISSING_DATA || langResult == LANG_AVAILABLE) {
                    Toast.makeText(this, "Text-to-Speech Initialized", Toast.LENGTH_SHORT).show()
                }
                
                // Get available voices
                val voices = textToSpeech.voices
                if (voices != null) {
                    // Log available voices for debugging
                    voices.forEach { voice ->
                        android.util.Log.d("TTS", "Available voice: ${voice.name}")
                    }
                } else {
                    android.util.Log.e("TTS", "No voices available")
                }
            } else {
                Toast.makeText(this, "Text-to-Speech Initialization Failed", Toast.LENGTH_SHORT).show()
            }
        })

        // Initialize speech recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setContent {
            SmsApp()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_TOGGLE_TTS -> {
                val newState = intent.getBooleanExtra(EXTRA_TTS_STATE, !isTtsEnabled)
                isTtsEnabled = newState
                if (isTtsEnabled) {
                    Toast.makeText(this, "Text-to-Speech Activated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Text-to-Speech Deactivated", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_TOGGLE_VOICE_SEND -> {
                val newState = intent.getBooleanExtra(EXTRA_VOICE_SEND_STATE, !isVoiceSendingEnabled)
                isVoiceSendingEnabled = newState
                if (isVoiceSendingEnabled) {
                    Toast.makeText(this, "Voice Sending Activated", Toast.LENGTH_SHORT).show()
                    startContinuousListening()
                } else {
                    Toast.makeText(this, "Voice Sending Deactivated", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun normalizePhoneNumber(phoneNumber: String): String {
        // Remove all non-digit characters
        return phoneNumber.replace(Regex("[^0-9]"), "")
    }

    private fun getPhoneNumberFromContact(contactName: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$contactName%")

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return cursor.getString(numberIndex)
            }
        }
        return null
    }

    private fun getContactNameFromNumber(phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val normalizedInput = normalizePhoneNumber(phoneNumber)
        
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val contactNumber = cursor.getString(numberIndex)
                val normalizedContactNumber = normalizePhoneNumber(contactNumber)
                
                // Check if the normalized numbers match
                if (normalizedContactNumber.endsWith(normalizedInput) || 
                    normalizedInput.endsWith(normalizedContactNumber)) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        return null
    }

    private fun speakWithVoice(text: String, voiceId: String) {
        try {
            val params = Bundle()
            params.putString("voiceId", voiceId)
            textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, params, null)
        } catch (e: Exception) {
            // Fallback to default voice if there's an error
            textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, null)
            android.util.Log.e("TTS", "Error using voice $voiceId: ${e.message}")
        }
    }

    @Composable
    fun SmsApp() {
        val context = LocalContext.current
        var phoneNumber by remember { mutableStateOf(TextFieldValue()) }
        var messageText by remember { mutableStateOf(TextFieldValue()) }
        var isProcessingContact by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            // You can initialize TextToSpeech here if needed.
        }

        LaunchedEffect(isVoiceSendingEnabled) {
            if (isVoiceSendingEnabled) {
                startContinuousListening()
            } else {
                currentVoiceState = VoiceState.IDLE
                currentRecipient = null
            }
        }

        val receivedMessageList by receivedMessages.collectAsState(emptyList())

        val requestAudioPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    startVoiceInput(context) { spokenText -> messageText = TextFieldValue(spokenText) }
                } else {
                    Toast.makeText(context, "Permission Denied", Toast.LENGTH_SHORT).show()
                }
            }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { 
                    phoneNumber = it
                    // If the input looks like a name (not a number), try to look up the contact
                    if (!it.text.all { char -> char.isDigit() || char == '+' || char == '-' || char == '(' || char == ')' || char == ' ' }) {
                        isProcessingContact = true
                        val contactNumber = getPhoneNumberFromContact(it.text)
                        if (contactNumber != null) {
                            phoneNumber = TextFieldValue(contactNumber)
                            Toast.makeText(context, "Found contact: ${it.text}", Toast.LENGTH_SHORT).show()
                        }
                        isProcessingContact = false
                    }
                },
                label = { Text("Enter phone number or contact name") },
                modifier = Modifier.fillMaxWidth()
            )

            if (isProcessingContact) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceInput(context) { spokenText -> 
                                // Try to get phone number from contact name
                                val contactNumber = getPhoneNumberFromContact(spokenText)
                                if (contactNumber != null) {
                                    phoneNumber = TextFieldValue(contactNumber)
                                    Toast.makeText(context, "Found contact: $spokenText", Toast.LENGTH_SHORT).show()
                                } else {
                                    // If no contact found, use the spoken text as is
                                    phoneNumber = TextFieldValue(spokenText)
                                    Toast.makeText(context, "No contact found for: $spokenText", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Voice Phone")
                }
            }

            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                label = { Text("Enter message") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { sendSms(phoneNumber.text, messageText.text) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Send SMS")
                }

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceInput(context) { spokenText -> messageText = TextFieldValue(spokenText) }
                        } else {
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Voice Message")
                }

                Button(
                    onClick = {
                        isTtsEnabled = !isTtsEnabled
                        if (isTtsEnabled) {
                            Toast.makeText(context, "Text-to-Speech Activated", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Text-to-Speech Deactivated", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("TTS ${if (isTtsEnabled) "On" else "Off"}")
                }

                Button(
                    onClick = {
                        isVoiceSendingEnabled = !isVoiceSendingEnabled
                        if (isVoiceSendingEnabled) {
                            Toast.makeText(context, "Voice Sending Activated", Toast.LENGTH_SHORT).show()
                            startContinuousListening()
                        } else {
                            Toast.makeText(context, "Voice Sending Deactivated", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isVoiceSendingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Voice Send ${if (isVoiceSendingEnabled) "On" else "Off"}")
                }
            }

            // Enhanced voice sending status indicator
            if (isVoiceSendingEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isListening) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (isListening) {
                            when (currentVoiceState) {
                                VoiceState.IDLE -> {
                                    Text(
                                        "Say 'Send Text to'",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Then say the contact name or number",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                VoiceState.WAITING_FOR_MESSAGE -> {
                                    Text(
                                        "Speak your message now",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Take your time to speak clearly",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                else -> {
                                    Text(
                                        "Listening...",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Wait a moment to send text",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "System is preparing to listen",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            Text("Received Messages:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            receivedMessageList.forEach { message ->
                Text(message, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun sendSms(phone: String, message: String) {
        if (phone.isNotEmpty() && message.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phone, null, message, null, null)
                Toast.makeText(this, "SMS Sent!", Toast.LENGTH_SHORT).show()
            } else {
                requestSmsPermission()
            }
        } else {
            Toast.makeText(this, "Enter phone number and message", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestSmsPermission() {
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied!", Toast.LENGTH_SHORT).show()
            }
        }
        requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
    }

    private fun requestReceiveSmsPermission() {
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
                Toast.makeText(this, "Receive SMS permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Receive SMS permission denied!", Toast.LENGTH_SHORT).show()
            }
        }
        requestPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bundle = intent?.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                pdus?.forEach { pdu ->
                    val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = smsMessage.originatingAddress
                    val messageBody = smsMessage.messageBody

                    if (messageBody.isNotEmpty()) {
                        lifecycleScope.launch {
                            val displayMessage = "From: $sender\n$messageBody\n\n"
                            receivedMessages.emit(receivedMessages.value + displayMessage)
                            
                            if (isTtsEnabled) {
                                // Get contact name if available
                                val senderName = sender?.let { getContactNameFromNumber(it) }
                                val senderText = if (senderName != null) {
                                    "Message from $senderName"
                                } else {
                                    "Message from $sender"
                                }

                                // Speak sender first
                                textToSpeech.speak(senderText, TextToSpeech.QUEUE_ADD, null, null)
                                
                                // Add a small delay
                                kotlinx.coroutines.delay(500)
                                
                                // Then speak message
                                textToSpeech.speak(messageBody, TextToSpeech.QUEUE_ADD, null, null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startVoiceInput(context: Context, onResult: (String) -> Unit) {
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                onResult(spokenText)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    private fun startContinuousListening() {
        if (!isVoiceSendingEnabled || isListening || isDestroyed) return

        try {
            isListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Use longer silence length when waiting for message
                val silenceLength = if (currentVoiceState == VoiceState.WAITING_FOR_MESSAGE) {
                    MESSAGE_SILENCE_LENGTH
                } else {
                    SILENCE_LENGTH
                }
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceLength.toLong())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_LENGTH.toLong())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceLength.toLong())
            }

            if (!::speechRecognizer.isInitialized) {
                android.util.Log.e("Speech", "SpeechRecognizer not initialized")
                return
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (isDestroyed) return
                    
                    try {
                        isListening = false
                        consecutiveErrors = 0
                        
                        val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                        android.util.Log.d("Speech", "Received results: $spokenText in state: $currentVoiceState")
                        
                        if (spokenText.isNotEmpty()) {
                            handleVoiceCommand(spokenText)
                        }
                        
                        lifecycleScope.launch {
                            try {
                                delay(LISTENING_RESTART_DELAY)
                                if (isVoiceSendingEnabled && !isDestroyed) {
                                    startContinuousListening()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Speech", "Error in onResults coroutine: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Speech", "Error in onResults: ${e.message}")
                        isListening = false
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (isDestroyed) return
                    
                    try {
                        val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
                        if (!partialText.isNullOrEmpty() && partialText.length > 3) {
                            android.util.Log.d("Speech", "Partial result: $partialText in state: $currentVoiceState")
                            handlePartialResult(partialText)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Speech", "Error in onPartialResults: ${e.message}")
                    }
                }

                override fun onError(error: Int) {
                    if (isDestroyed) return
                    
                    try {
                        isListening = false
                        val currentTime = System.currentTimeMillis()
                        
                        android.util.Log.e("Speech", "Error occurred: $error in state: $currentVoiceState")
                        
                        when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> {
                                android.util.Log.e("Speech", "Audio recording error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_CLIENT -> {
                                android.util.Log.e("Speech", "Client side error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                android.util.Log.e("Speech", "Insufficient permissions")
                                isVoiceSendingEnabled = false
                                return
                            }
                            SpeechRecognizer.ERROR_NETWORK -> {
                                android.util.Log.e("Speech", "Network error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                                android.util.Log.e("Speech", "Network timeout")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_NO_MATCH -> {
                                android.util.Log.d("Speech", "No match found")
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                android.util.Log.e("Speech", "RecognitionService busy")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_SERVER -> {
                                android.util.Log.e("Speech", "Server error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                android.util.Log.d("Speech", "No speech input")
                            }
                        }

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            android.util.Log.e("Speech", "Too many consecutive errors, stopping voice recognition")
                            isVoiceSendingEnabled = false
                            lifecycleScope.launch {
                                try {
                                    textToSpeech.speak("Voice recognition stopped due to errors", TextToSpeech.QUEUE_FLUSH, null, null)
                                } catch (e: Exception) {
                                    android.util.Log.e("Speech", "Error speaking error message: ${e.message}")
                                }
                            }
                            return
                        }

                        if (currentTime - lastErrorTime > ERROR_RETRY_DELAY) {
                            lastErrorTime = currentTime
                            lifecycleScope.launch {
                                try {
                                    delay(ERROR_RETRY_DELAY)
                                    if (isVoiceSendingEnabled && !isDestroyed) {
                                        startContinuousListening()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("Speech", "Error in onError coroutine: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Speech", "Error in onError: ${e.message}")
                        isListening = false
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    if (isDestroyed) return
                    isListening = true
                    android.util.Log.d("Speech", "Ready for speech in state: $currentVoiceState")
                }

                override fun onBeginningOfSpeech() {
                    if (isDestroyed) return
                    android.util.Log.d("Speech", "Beginning of speech in state: $currentVoiceState")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (isDestroyed) return
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    if (isDestroyed) return
                }

                override fun onEndOfSpeech() {
                    if (isDestroyed) return
                    isListening = false
                    android.util.Log.d("Speech", "End of speech in state: $currentVoiceState")
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    if (isDestroyed) return
                }
            })

            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            android.util.Log.e("Speech", "Error starting speech recognition: ${e.message}")
            isListening = false
            isVoiceSendingEnabled = false
        }
    }

    private fun handlePartialResult(partialText: String) {
        if (isDestroyed) return
        
        try {
            // Only process partial results in IDLE state
            if (currentVoiceState == VoiceState.IDLE && 
                partialText.lowercase().contains("send text to")) {
                // Don't stop listening or process the command yet
                // Just log that we detected the command
                android.util.Log.d("Speech", "Detected command in partial results: $partialText")
            }
        } catch (e: Exception) {
            android.util.Log.e("Speech", "Error in handlePartialResult: ${e.message}")
        }
    }

    private fun handleVoiceCommand(spokenText: String) {
        android.util.Log.d("Speech", "Handling command: $spokenText in state: $currentVoiceState")
        
        when (currentVoiceState) {
            VoiceState.IDLE -> {
                if (spokenText.lowercase().contains("send text to")) {
                    val recipient = spokenText.lowercase().replace("send text to", "").trim()
                    currentVoiceState = VoiceState.WAITING_FOR_RECIPIENT
                    processRecipient(recipient)
                }
            }
            VoiceState.WAITING_FOR_RECIPIENT -> {
                processRecipient(spokenText)
            }
            VoiceState.WAITING_FOR_MESSAGE -> {
                processMessage(spokenText)
            }
        }
    }

    private fun processRecipient(recipient: String) {
        android.util.Log.d("Speech", "Processing recipient: $recipient")
        
        // Try to get phone number from contact name
        val phoneNumber = getPhoneNumberFromContact(recipient)
        if (phoneNumber != null) {
            currentRecipient = phoneNumber
            currentVoiceState = VoiceState.WAITING_FOR_MESSAGE
            textToSpeech.speak("What is the message to send?", TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            // If it's not a contact name, assume it's a phone number
            currentRecipient = recipient
            currentVoiceState = VoiceState.WAITING_FOR_MESSAGE
            textToSpeech.speak("What is the message to send?", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun processMessage(message: String) {
        android.util.Log.d("Speech", "Processing message: $message for recipient: $currentRecipient")
        
        currentRecipient?.let { recipient ->
            sendSms(recipient, message)
            textToSpeech.speak("Message sent to $recipient", TextToSpeech.QUEUE_FLUSH, null, null)
        }
        // Reset state
        currentVoiceState = VoiceState.IDLE
        currentRecipient = null
    }

    override fun onDestroy() {
        isDestroyed = true
        super.onDestroy()
        try {
            if (::textToSpeech.isInitialized) {
                textToSpeech.stop()
                textToSpeech.shutdown()
            }
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.destroy()
            }
            unregisterReceiver(smsReceiver)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error in onDestroy: ${e.message}")
        }
    }
}


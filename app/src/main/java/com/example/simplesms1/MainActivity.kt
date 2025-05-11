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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import android.media.AudioManager
import android.provider.Settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val receivedMessages = MutableStateFlow<List<String>>(emptyList())  // Use list to store messages
    private lateinit var textToSpeech: TextToSpeech
    var isTtsEnabled by mutableStateOf(true)  // Set to true by default
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
    private val MESSAGE_SILENCE_LENGTH = 2500 // 2.5 seconds silence when waiting for message
    private lateinit var audioManager: AudioManager
    private var previousVolume: Int = 0

    private enum class VoiceState {
        IDLE,
        WAITING_FOR_RECIPIENT,
        WAITING_FOR_MESSAGE
    }

    // Add state holder
    private val uiState = MutableStateFlow(UiState())

    data class UiState(
        val phoneNumber: TextFieldValue = TextFieldValue(),
        val messageText: TextFieldValue = TextFieldValue(),
        val isVoicePhoneActive: Boolean = false,
        val isVoiceMessageActive: Boolean = false,
        val isReadyForSpeech: Boolean = false
    )

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
        android.util.Log.d("TEST", "App started - onCreate")

        // Initialize AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Handle incoming intents
        handleIntent(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestReceiveSmsPermission()
        } else {
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
        }

        // Check and request permissions with delay
        lifecycleScope.launch {
            delay(2000)
            checkAndRequestPermissions()
        }

        textToSpeech = TextToSpeech(this, OnInitListener { status ->
            android.util.Log.d("TEST", "TTS init callback received with status: $status")
            if (status == TextToSpeech.SUCCESS) {
                val langResult = textToSpeech.setLanguage(Locale.getDefault())
                android.util.Log.d("TEST", "TTS language set with result: $langResult")
                if (langResult == LANG_MISSING_DATA || langResult == LANG_AVAILABLE) {
                    Toast.makeText(this, "Text-to-Speech Initialized", Toast.LENGTH_SHORT).show()
                }
                
                // Get available voices
                val voices = textToSpeech.voices
                if (voices != null) {
                    // Log available voices for debugging
                    voices.forEach { voice ->
                        android.util.Log.d("TEST", "Available voice: ${voice.name}")
                    }
                } else {
                    android.util.Log.e("TEST", "No voices available")
                }

                // Set up utterance progress listener
                textToSpeech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d("TEST", "TTS started speaking utterance: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d("TEST", "TTS finished speaking utterance: $utteranceId")
                    }

                    override fun onError(utteranceId: String?) {
                        android.util.Log.e("TEST", "TTS error for utterance: $utteranceId")
                    }
                })

                // Test TTS is working
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TEST_UTTERANCE")
                val testResult = textToSpeech.speak("Text to speech initialized", TextToSpeech.QUEUE_FLUSH, params, "TEST_UTTERANCE")
                android.util.Log.d("TEST", "TTS initialization test message sent with result: $testResult")
            } else {
                android.util.Log.e("TEST", "TTS initialization failed with status: $status")
                Toast.makeText(this, "Text-to-Speech Initialization Failed", Toast.LENGTH_SHORT).show()
            }
        })

        // Initialize speech recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setContent {
            SmsApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
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
                    // Ensure TTS is enabled for voice prompts
                    isTtsEnabled = true
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

    private fun openTTSSettings() {
        try {
            // First try to open general settings
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
            
            // Show a more detailed toast with instructions
            Toast.makeText(
                this,
                "Please navigate to:\n" +
                "1. Accessibility\n" +
                "2. Vision Enhancements\n" +
                "3. Text-to-speech output\n" +
                "4. Select Google Text-to-speech\n" +
                "5. Install voice data if needed",
                Toast.LENGTH_LONG
            ).show()
            
        } catch (e: Exception) {
            android.util.Log.e("TEST", "Error opening settings: ${e.message}")
            Toast.makeText(
                this,
                "Could not open settings. Please check Vision Enhancements in Accessibility settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Composable
    fun SmsApp() {
        val context = LocalContext.current
        val currentUiState by uiState.collectAsState()
        var isProcessingContact by remember { mutableStateOf(false) }
        var showTTSSettingsDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            // Initialize other things if needed
        }

        LaunchedEffect(isVoiceSendingEnabled) {
            if (isVoiceSendingEnabled) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startContinuousListening()
                } else {
                    isVoiceSendingEnabled = false
                    // Don't show toast here, let the button handle it
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            } else {
                currentVoiceState = VoiceState.IDLE
                currentRecipient = null
                isListening = false
            }
        }

        val receivedMessageList by receivedMessages.collectAsState(emptyList())

        val requestAudioPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    // Only show toast if we're not in the middle of another operation
                    if (!isVoiceSendingEnabled && !currentUiState.isVoicePhoneActive && !currentUiState.isVoiceMessageActive) {
                        Toast.makeText(context, "Audio permission granted", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Only show denial toast if we're not in the middle of another operation
                    if (!isVoiceSendingEnabled && !currentUiState.isVoicePhoneActive && !currentUiState.isVoiceMessageActive) {
                        Toast.makeText(context, "Audio permission required for voice features", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        if (showTTSSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showTTSSettingsDialog = false },
                title = { Text("TTS Setup Instructions") },
                text = {
                    Text(
                        "To enable Text-to-Speech:\n\n" +
                        "1. Go to Accessibility\n" +
                        "2. Select Vision Enhancements\n" +
                        "3. Find Text-to-speech output\n" +
                        "4. Select Google Text-to-speech\n" +
                        "5. Install voice data if needed\n\n" +
                        "After setup, return to the app and try again."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showTTSSettingsDialog = false }) {
                        Text("OK")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // TTS Settings Button at the top
            Button(
                onClick = { 
                    openTTSSettings()
                    showTTSSettingsDialog = true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open TTS Settings")
            }

            // Voice Phone Button at the top
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        uiState.value = uiState.value.copy(isVoicePhoneActive = true)
                        startVoiceInput(context) { spokenText -> 
                            // Try to get phone number from contact name
                            val contactNumber = getPhoneNumberFromContact(spokenText)
                            if (contactNumber != null) {
                                uiState.value = uiState.value.copy(
                                    phoneNumber = TextFieldValue(contactNumber),
                                    isVoicePhoneActive = false
                                )
                                Toast.makeText(context, "Found contact: $spokenText", Toast.LENGTH_SHORT).show()
                            } else {
                                // If no contact found, use the spoken text as is
                                uiState.value = uiState.value.copy(
                                    phoneNumber = TextFieldValue(spokenText),
                                    isVoicePhoneActive = false
                                )
                                Toast.makeText(context, "No contact found for: $spokenText", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // Show a more informative message before requesting permission
                        Toast.makeText(context, "Audio permission needed for voice input", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            delay(1000) // Wait for the first toast to be read
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentUiState.isVoicePhoneActive) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Voice Phone")
            }

            // Phone Number Input
            OutlinedTextField(
                value = currentUiState.phoneNumber,
                onValueChange = { 
                    uiState.value = uiState.value.copy(phoneNumber = it)
                    // If the input looks like a name (not a number), try to look up the contact
                    if (!it.text.all { char -> char.isDigit() || char == '+' || char == '-' || char == '(' || char == ')' || char == ' ' }) {
                        isProcessingContact = true
                        val contactNumber = getPhoneNumberFromContact(it.text)
                        if (contactNumber != null) {
                            uiState.value = uiState.value.copy(phoneNumber = TextFieldValue(contactNumber))
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

            // Voice Message Button
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        uiState.value = uiState.value.copy(isVoiceMessageActive = true)
                        startVoiceInput(context) { spokenText -> 
                            uiState.value = uiState.value.copy(
                                messageText = TextFieldValue(spokenText),
                                isVoiceMessageActive = false
                            )
                        }
                    } else {
                        // Show a more informative message before requesting permission
                        Toast.makeText(context, "Audio permission needed for voice input", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            delay(1000) // Wait for the first toast to be read
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentUiState.isVoiceMessageActive) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Voice Message")
            }

            // Message Input
            OutlinedTextField(
                value = currentUiState.messageText,
                onValueChange = { 
                    uiState.value = uiState.value.copy(messageText = it)
                },
                label = { Text("Enter message") },
                modifier = Modifier.fillMaxWidth()
            )

            // Control Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { sendSms(currentUiState.phoneNumber.text, currentUiState.messageText.text) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Send SMS")
                }

                Button(
                    onClick = { toggleTTS() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTtsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("TTS ${if (isTtsEnabled) "On" else "Off"}")
                }

                Button(
                    onClick = { 
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            isVoiceSendingEnabled = !isVoiceSendingEnabled
                            if (isVoiceSendingEnabled) {
                                startContinuousListening()
                            } else {
                                stopContinuousListening()
                            }
                        } else {
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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

                        // Speech readiness indicator
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (currentUiState.isReadyForSpeech) "Ready for speech" else "Not ready for speech",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentUiState.isReadyForSpeech) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Received Messages in a scrollable container
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Text("Received Messages:", style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(receivedMessageList) { message ->
                        Text(message, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Debug button to check permissions
            Button(
                onClick = { 
                    Toast.makeText(context, "Checking permissions...", Toast.LENGTH_SHORT).show()
                    checkAndRequestPermissions() 
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Check Permissions")
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            android.util.Log.d("Permissions", "Missing permissions: ${missingPermissions.joinToString()}")
            // Show which permissions are missing
            val missingList = missingPermissions.joinToString("\n") { 
                when(it) {
                    Manifest.permission.SEND_SMS -> "Send SMS"
                    Manifest.permission.READ_SMS -> "Read SMS"
                    Manifest.permission.READ_CONTACTS -> "Read Contacts"
                    Manifest.permission.RECORD_AUDIO -> "Record Audio"
                    else -> it
                }
            }
            Toast.makeText(this, "Missing permissions:\n$missingList", Toast.LENGTH_LONG).show()
            requestPermissionsLauncher.launch(permissions)
        } else {
            // Show which permissions are granted
            val grantedList = permissions.joinToString("\n") { 
                when(it) {
                    Manifest.permission.SEND_SMS -> "Send SMS"
                    Manifest.permission.READ_SMS -> "Read SMS"
                    Manifest.permission.READ_CONTACTS -> "Read Contacts"
                    Manifest.permission.RECORD_AUDIO -> "Record Audio"
                    else -> it
                }
            }
            Toast.makeText(this, "All permissions granted:\n$grantedList", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendSms(phone: String, message: String) {
        if (phone.isNotEmpty() && message.isNotEmpty()) {
            try {
                android.util.Log.d("SMS", "Attempting to send message to $phone: $message")
                android.util.Log.d("SMS", "SMS permission status: ${ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)}")
                
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(phone, null, message, null, null)
                    android.util.Log.d("SMS", "Message sent successfully to $phone")
                    Toast.makeText(this, "SMS Sent!", Toast.LENGTH_SHORT).show()
                    
                    // Clear both fields after a delay
                    lifecycleScope.launch {
                        delay(3000)
                        uiState.value = uiState.value.copy(
                            phoneNumber = TextFieldValue(""),
                            messageText = TextFieldValue("")
                        )
                    }
                } else {
                    android.util.Log.e("SMS", "SMS permission not granted")
                    Toast.makeText(this, "SMS permission not granted", Toast.LENGTH_SHORT).show()
                    checkAndRequestPermissions()
                }
            } catch (e: Exception) {
                android.util.Log.e("SMS", "Error sending message: ${e.message}")
                android.util.Log.e("SMS", "Error stack trace: ${e.stackTraceToString()}")
                Toast.makeText(this, "Error sending message: ${e.message}", Toast.LENGTH_SHORT).show()
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
                // Reset button states
                uiState.value = uiState.value.copy(
                    isVoicePhoneActive = false,
                    isVoiceMessageActive = false
                )
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // Reset button states on error
                uiState.value = uiState.value.copy(
                    isVoicePhoneActive = false,
                    isVoiceMessageActive = false
                )
            }
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
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }

            if (!::speechRecognizer.isInitialized) {
                android.util.Log.e("TEST", "SpeechRecognizer not initialized")
                return
            }

            speechRecognizer.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (isDestroyed) return
                    
                    try {
                        isListening = false
                        consecutiveErrors = 0
                        
                        val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                        android.util.Log.d("TEST", "=== Speech Recognizer onResults ===")
                        android.util.Log.d("TEST", "Received results: $spokenText in state: $currentVoiceState")
                        android.util.Log.d("TEST", "About to call handleVoiceCommand")
                        
                        if (spokenText.isNotEmpty()) {
                            handleVoiceCommand(spokenText)
                        }
                        
                        android.util.Log.d("TEST", "=== Finished Speech Recognizer onResults ===")
                        
                        lifecycleScope.launch {
                            try {
                                delay(LISTENING_RESTART_DELAY)
                                if (isVoiceSendingEnabled && !isDestroyed) {
                                    startContinuousListening()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("TEST", "Error in onResults coroutine: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TEST", "Error in onResults: ${e.message}")
                        isListening = false
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (isDestroyed) return
                    
                    try {
                        val partialText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
                        if (!partialText.isNullOrEmpty() && partialText.length > 3) {
                            android.util.Log.d("TEST", "Partial result: $partialText in state: $currentVoiceState")
                            handlePartialResult(partialText)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TEST", "Error in onPartialResults: ${e.message}")
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    if (isDestroyed) return
                    isListening = true
                    uiState.value = uiState.value.copy(isReadyForSpeech = true)
                    android.util.Log.d("TEST", "Ready for speech in state: $currentVoiceState")
                }

                override fun onEndOfSpeech() {
                    if (isDestroyed) return
                    isListening = false
                    uiState.value = uiState.value.copy(isReadyForSpeech = false)
                    android.util.Log.d("TEST", "End of speech in state: $currentVoiceState")
                }

                override fun onBeginningOfSpeech() {
                    if (isDestroyed) return
                    android.util.Log.d("TEST", "Beginning of speech in state: $currentVoiceState")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (isDestroyed) return
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    if (isDestroyed) return
                }

                override fun onError(error: Int) {
                    if (isDestroyed) return
                    
                    try {
                        isListening = false
                        val currentTime = System.currentTimeMillis()
                        
                        android.util.Log.e("TEST", "Error occurred: $error in state: $currentVoiceState")
                        
                        when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> {
                                android.util.Log.e("TEST", "Audio recording error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_CLIENT -> {
                                android.util.Log.e("TEST", "Client side error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                android.util.Log.e("TEST", "Insufficient permissions")
                                isVoiceSendingEnabled = false
                                return
                            }
                            SpeechRecognizer.ERROR_NETWORK -> {
                                android.util.Log.e("TEST", "Network error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                                android.util.Log.e("TEST", "Network timeout")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_NO_MATCH -> {
                                android.util.Log.d("TEST", "No match found")
                            }
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                                android.util.Log.e("TEST", "RecognitionService busy")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_SERVER -> {
                                android.util.Log.e("TEST", "Server error")
                                consecutiveErrors++
                            }
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                android.util.Log.d("TEST", "No speech input")
                            }
                        }

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            android.util.Log.e("TEST", "Too many consecutive errors, stopping voice recognition")
                            isVoiceSendingEnabled = false
                            lifecycleScope.launch {
                                try {
                                    textToSpeech.speak("Voice recognition stopped due to errors", TextToSpeech.QUEUE_FLUSH, null, null)
                                } catch (e: Exception) {
                                    android.util.Log.e("TEST", "Error speaking error message: ${e.message}")
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
                                    android.util.Log.e("TEST", "Error in onError coroutine: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TEST", "Error in onError: ${e.message}")
                        isListening = false
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    if (isDestroyed) return
                }
            })

            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            android.util.Log.e("TEST", "Error starting speech recognition: ${e.message}")
            isListening = false
            isVoiceSendingEnabled = false
            uiState.value = uiState.value.copy(isReadyForSpeech = false)
        }
    }

    private fun stopContinuousListening() {
        try {
            if (::speechRecognizer.isInitialized) {
                speechRecognizer.stopListening()
                speechRecognizer.destroy()
            }
            isListening = false
            currentVoiceState = VoiceState.IDLE
            currentRecipient = null
            uiState.value = uiState.value.copy(isReadyForSpeech = false)
        } catch (e: Exception) {
            android.util.Log.e("Speech", "Error stopping speech recognition: ${e.message}")
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
        android.util.Log.d("TEST", "=== Starting handleVoiceCommand ===")
        android.util.Log.d("TEST", "Handling command: $spokenText in state: $currentVoiceState")
        android.util.Log.d("TEST", "TTS status - Initialized: ${::textToSpeech.isInitialized}, Enabled: $isTtsEnabled")
        
        when (currentVoiceState) {
            VoiceState.IDLE -> {
                if (spokenText.lowercase().contains("send text to")) {
                    android.util.Log.d("TEST", "Detected 'send text to' command")
                    val recipient = spokenText.lowercase().replace("send text to", "").trim()
                    android.util.Log.d("TEST", "Extracted recipient: $recipient")
                    android.util.Log.d("TEST", "Changing state from IDLE to WAITING_FOR_RECIPIENT")
                    currentVoiceState = VoiceState.WAITING_FOR_RECIPIENT
                    processRecipient(recipient)
                }
            }
            VoiceState.WAITING_FOR_RECIPIENT -> {
                android.util.Log.d("TEST", "Processing recipient in WAITING_FOR_RECIPIENT state")
                processRecipient(spokenText)
            }
            VoiceState.WAITING_FOR_MESSAGE -> {
                android.util.Log.d("TEST", "Processing message in WAITING_FOR_MESSAGE state")
                processMessage(spokenText)
            }
        }
        android.util.Log.d("TEST", "=== Finished handleVoiceCommand ===")
    }

    private fun processRecipient(recipient: String) {
        android.util.Log.d("TEST", "=== Starting processRecipient ===")
        android.util.Log.d("TEST", "Processing recipient: $recipient")
        android.util.Log.d("TEST", "Current state: $currentVoiceState")
        android.util.Log.d("TEST", "TTS status - Initialized: ${::textToSpeech.isInitialized}, Enabled: $isTtsEnabled")
        
        // Try to get phone number from contact name
        val phoneNumber = getPhoneNumberFromContact(recipient)
        if (phoneNumber != null) {
            android.util.Log.d("TEST", "Found phone number: $phoneNumber for recipient: $recipient")
            currentRecipient = phoneNumber
            // Update the phone number field in the UI
            uiState.value = uiState.value.copy(phoneNumber = TextFieldValue(phoneNumber))
            currentVoiceState = VoiceState.WAITING_FOR_MESSAGE
            
            // Speak prompt
            speakPrompt("What is the message to send?")
        } else {
            android.util.Log.d("TEST", "No contact found, using as phone number: $recipient")
            // If it's not a contact name, assume it's a phone number
            currentRecipient = recipient
            // Update the phone number field in the UI
            uiState.value = uiState.value.copy(phoneNumber = TextFieldValue(recipient))
            currentVoiceState = VoiceState.WAITING_FOR_MESSAGE
            
            // Speak prompt
            speakPrompt("What is the message to send?")
        }
        android.util.Log.d("TEST", "=== Finished processRecipient ===")
    }

    private fun processMessage(message: String) {
        android.util.Log.d("Speech", "Processing message: $message for recipient: $currentRecipient")
        
        // Update the message field in the UI
        uiState.value = uiState.value.copy(messageText = TextFieldValue(message))
        
        currentRecipient?.let { recipient ->
            try {
                android.util.Log.d("SMS", "Attempting to send message to $recipient: $message")
                android.util.Log.d("SMS", "SMS permission status: ${ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)}")
                
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(recipient, null, message, null, null)
                    android.util.Log.d("SMS", "Message sent successfully to $recipient")
                    textToSpeech.speak("Message sent to $recipient", TextToSpeech.QUEUE_FLUSH, null, null)
                    
                    // Clear both fields after a delay
                    lifecycleScope.launch {
                        delay(3000)
                        uiState.value = uiState.value.copy(
                            phoneNumber = TextFieldValue(""),
                            messageText = TextFieldValue("")
                        )
                    }
                } else {
                    android.util.Log.e("SMS", "SMS permission not granted")
                    textToSpeech.speak("SMS permission not granted", TextToSpeech.QUEUE_FLUSH, null, null)
                    checkAndRequestPermissions()
                }
            } catch (e: Exception) {
                android.util.Log.e("SMS", "Error sending message: ${e.message}")
                android.util.Log.e("SMS", "Error stack trace: ${e.stackTraceToString()}")
                textToSpeech.speak("Error sending message: ${e.message}", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
        
        // Reset state
        currentVoiceState = VoiceState.IDLE
        currentRecipient = null
        isListening = false
        
        // Restart listening if voice sending is still enabled
        if (isVoiceSendingEnabled) {
            lifecycleScope.launch {
                delay(1000)
                startContinuousListening()
            }
        }
    }

    private fun toggleTTS() {
        isTtsEnabled = !isTtsEnabled
        if (isTtsEnabled) {
            Toast.makeText(this, "Text-to-Speech Activated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Text-to-Speech Deactivated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVoiceSend() {
        isVoiceSendingEnabled = !isVoiceSendingEnabled
        if (isVoiceSendingEnabled) {
            // Ensure TTS is enabled for voice prompts
            isTtsEnabled = true
            Toast.makeText(this, "Voice Sending Activated", Toast.LENGTH_SHORT).show()
            startContinuousListening()
        } else {
            Toast.makeText(this, "Voice Sending Deactivated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speakPrompt(text: String) {
        android.util.Log.d("TEST", "Attempting to speak: $text")
        if (!::textToSpeech.isInitialized) {
            android.util.Log.e("TEST", "TTS not initialized")
            return
        }
        if (!isTtsEnabled) {
            android.util.Log.d("TEST", "TTS is disabled")
            return
        }
        try {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "PROMPT_UTTERANCE")
            val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "PROMPT_UTTERANCE")
            android.util.Log.d("TEST", "TTS speak result for '$text': $result")
            if (result == TextToSpeech.ERROR) {
                android.util.Log.e("TEST", "TTS speak failed")
                // Try to reinitialize TTS
                textToSpeech.shutdown()
                textToSpeech = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        textToSpeech.setLanguage(Locale.getDefault())
                        val retryResult = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "PROMPT_UTTERANCE_RETRY")
                        android.util.Log.d("TEST", "TTS retry speak result: $retryResult")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TEST", "Error speaking prompt: ${e.message}")
            e.printStackTrace()
        }
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


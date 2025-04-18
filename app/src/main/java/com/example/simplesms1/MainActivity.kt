package com.example.simplesms1

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
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

class MainActivity : ComponentActivity() {

    private val receivedMessages = MutableStateFlow<List<String>>(emptyList())  // Use list to store messages
    private lateinit var textToSpeech: TextToSpeech
    var isTtsEnabled by mutableStateOf(false)


    //private val receivedMessages = MutableStateFlow("")
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)
        )

        textToSpeech = TextToSpeech(this, OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = textToSpeech.setLanguage(Locale.getDefault())
                if (langResult == LANG_MISSING_DATA || langResult == LANG_AVAILABLE) {
                    Toast.makeText(this, "Text-to-Speech Initialized", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Text-to-Speech Initialization Failed", Toast.LENGTH_SHORT).show()
            }
        })

        setContent {
            SmsApp()
        }


    }

    @Composable
    fun SmsApp() {
        val context = LocalContext.current
        var phoneNumber by remember { mutableStateOf(TextFieldValue()) }
        var messageText by remember { mutableStateOf(TextFieldValue()) }

        LaunchedEffect(Unit) {
            // You can initialize TextToSpeech here if needed.
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
                onValueChange = { phoneNumber = it },
                label = { Text("Enter phone number") },
                modifier = Modifier.fillMaxWidth()
            )

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
                    onClick = {// Check permission and request it if needed
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startVoiceInput(context) { spokenText -> messageText = TextFieldValue(spokenText) }
                        } else {
                            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }

                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Voice Input")
                }

                Button(
                    onClick = {
                        isTtsEnabled = !isTtsEnabled  // Toggle TTS state
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

            }

            Text("Received Messages:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            receivedMessageList.forEach { message ->
                Text(message, modifier = Modifier.fillMaxWidth())
            }
        //Text(receivedMessage, modifier = Modifier.fillMaxWidth())
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

    /*private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bundle = intent?.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                pdus?.forEach { pdu ->
                    val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = smsMessage.originatingAddress
                    val messageBody = smsMessage.messageBody
                    lifecycleScope.launch {
                        receivedMessages.value = "From: $sender\n$messageBody\n\n" + receivedMessages.value
                    }
                }
            }
        }
    } */
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
                        if (messageBody.isNotEmpty()) {
                            // Append the received message to the UI
                            lifecycleScope.launch {
                                val displayMessage = "From: $sender\n$messageBody\n\n"
                                receivedMessages.emit(receivedMessages.value + displayMessage)
                                if (isTtsEnabled) {
                                    textToSpeech?.speak(messageBody, TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            }
                        }
                    }
/*
                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                        //val newMessage = "From: $sender\n$messageBody\n\n" + receivedMessages.value
                        //receivedMessages.emit(newMessage)  // Force UI update
                        receivedMessages.emit(receivedMessages.value + "From: $sender\n$messageBody\n\n")  // Append to list
                    }
                    */
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

    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.stop()
        textToSpeech.shutdown()
        unregisterReceiver(smsReceiver)
    }
}


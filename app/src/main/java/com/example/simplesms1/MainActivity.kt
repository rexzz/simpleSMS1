package com.example.simplesms1


import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val receivedMessages = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmsApp()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestReceiveSmsPermission()
        } else {
            registerReceiver(smsReceiver, IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
        }
    }

    @Composable
    fun SmsApp() {
        var phoneNumber by remember { mutableStateOf(TextFieldValue()) }
        var messageText by remember { mutableStateOf(TextFieldValue()) }
        val receivedMessage by receivedMessages.asStateFlow().collectAsState("")

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

            Button(
                onClick = { sendSms(phoneNumber.text, messageText.text) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send SMS")
            }

            Text("Received Messages:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Text(receivedMessage, modifier = Modifier.fillMaxWidth())
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
                    lifecycleScope.launch {
                        receivedMessages.value = "From: $sender\n$messageBody\n\n" + receivedMessages.value
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
    }
}

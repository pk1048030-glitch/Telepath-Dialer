package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallManager {
    private const val TAG = "CallManager"

    // Unified states
    private val _callState = MutableStateFlow(CallState.NONE)
    val callState = _callState.asStateFlow()

    private val _phoneNumber = MutableStateFlow("")
    val phoneNumber = _phoneNumber.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(false)
    val isSpeakerphoneOn = _isSpeakerphoneOn.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private val _isSimulationMode = MutableStateFlow(true) // Default to true for sandbox environments
    val isSimulationMode = _isSimulationMode.asStateFlow()

    private val _callDuration = MutableStateFlow(0L) // in seconds
    val callDuration = _callDuration.asStateFlow()

    // Debug logs representation of API calls made
    private val _audioRoutingDebugLog = MutableStateFlow<List<String>>(emptyList())
    val audioRoutingDebugLog = _audioRoutingDebugLog.asStateFlow()

    // Coroutine Scope for background ticking
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var durationJob: Job? = null
    private var simulationJob: Job? = null

    // Native Telecom Call reference
    private var activeTelecomCall: Call? = null

    fun addDebugLog(message: String) {
        val currentLogs = _audioRoutingDebugLog.value.toMutableList()
        if (currentLogs.size > 8) {
            currentLogs.removeAt(0)
        }
        currentLogs.add("${android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())}: $message")
        _audioRoutingDebugLog.value = currentLogs
        Log.d(TAG, "Audio Log: $message")
    }

    fun setSimulationMode(enabled: Boolean) {
        _isSimulationMode.value = enabled
        addDebugLog("Switched call mode to: ${if (enabled) "SIMULATOR" else "NATIVE TELECOM"}")
    }

    fun dial(context: Context, number: String) {
        _phoneNumber.value = number
        addDebugLog("Dialing: $number")

        if (_isSimulationMode.value) {
            startSimulationCall(context)
        } else {
            // Native calling via Telecom Manager
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                _callState.value = CallState.DIALING
                addDebugLog("Native call intent dispatched.")
            } catch (e: SecurityException) {
                addDebugLog("ERROR: CALL_PHONE permission not granted!")
                _callState.value = CallState.NONE
            } catch (e: Exception) {
                addDebugLog("ERROR: Native call failure: ${e.message}")
                _callState.value = CallState.NONE
            }
        }
    }

    fun hangUp(context: Context) {
        addDebugLog("Hanging up...")
        if (_isSimulationMode.value) {
            stopSimulationCall(context)
        } else {
            val call = activeTelecomCall
            if (call != null) {
                if (call.state == Call.STATE_RINGING) {
                    call.reject(false, null)
                } else {
                    call.disconnect()
                }
                addDebugLog("Native disconnect request sent.")
            } else {
                addDebugLog("No active native call to hang up.")
                _callState.value = CallState.NONE
            }
        }
        resetAudio(context)
    }

    fun toggleSpeakerphone(context: Context) {
        val nextState = !_isSpeakerphoneOn.value
        _isSpeakerphoneOn.value = nextState
        setSpeakerphone(context, nextState)
    }

    fun toggleMute(context: Context) {
        val nextState = !_isMuted.value
        _isMuted.value = nextState

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioManager.isMicrophoneMute = nextState
            _isMuted.value = audioManager.isMicrophoneMute
            addDebugLog("Microphone mute set to: $nextState")
        } catch (e: Exception) {
            addDebugLog("ERROR: Mute toggle failed: ${e.message}")
        }
    }

    // Handles loudspeaker override and fixes routing bugs
    fun setSpeakerphone(context: Context, enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        addDebugLog("APPLYING SPEAKERPHONE: $enabled")

        try {
            if (enabled) {
                // Set telephone-quality audio routing mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                addDebugLog("AudioManager.mode = MODE_IN_COMMUNICATION")

                // Android 12 (API 31/S) and higher - Modern communication device routing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        val result = audioManager.setCommunicationDevice(speakerDevice)
                        addDebugLog("setCommunicationDevice(SPEAKER) -> result: $result")
                    } else {
                        addDebugLog("WARNING: TYPE_BUILTIN_SPEAKER device not found in available list!")
                    }
                }

                // Bulletproof fallback & older Android version support
                audioManager.isSpeakerphoneOn = true
                addDebugLog("isSpeakerphoneOn = true (direct property override)")

            } else {
                // Clear modern device communication routing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                    addDebugLog("clearCommunicationDevice() invoked")
                }

                // Standard speakerphone disabling
                audioManager.isSpeakerphoneOn = false
                addDebugLog("isSpeakerphoneOn = false")

                // Keep communication mode but restore normal when ending/clearing, or fall back to normal
                audioManager.mode = AudioManager.MODE_NORMAL
                addDebugLog("AudioManager.mode = MODE_NORMAL")
            }
        } catch (e: Exception) {
            addDebugLog("ERROR configuring Loudspeaker: ${e.message}")
        }
    }

    fun resetAudio(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = false
            audioManager.isMicrophoneMute = false
            audioManager.mode = AudioManager.MODE_NORMAL
            _isSpeakerphoneOn.value = false
            _isMuted.value = false
            addDebugLog("Audio status completely reset to NORMAL profiles")
        } catch (e: Exception) {
            Log.e(TAG, "Audio reset failed", e)
        }
    }

    // --- Native Telecom Callback Handlers ---
    fun onCallAdded(call: Call) {
        activeTelecomCall = call
        _isSimulationMode.value = false // Automatically switch to native display if a real call starts
        _phoneNumber.value = call.details.handle?.schemeSpecificPart ?: "Unknown Caller"
        translateTelecomState(call.state)
        addDebugLog("Native call connected to AppInCallService.")

        // Listen for real state changes (Dialing -> Active -> Disconnected)
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                translateTelecomState(state)
            }
        })

        startDurationTracker()
    }

    fun onCallRemoved(call: Call) {
        if (activeTelecomCall == call) {
            activeTelecomCall = null
            _callState.value = CallState.DISCONNECTED
            addDebugLog("Native Call disconnected.")
            stopDurationTracker()
            
            // Clean up back to DIALER screen after 1.5 seconds
            managerScope.launch {
                delay(1500)
                if (activeTelecomCall == null && _callState.value == CallState.DISCONNECTED) {
                    _callState.value = CallState.NONE
                    _phoneNumber.value = ""
                }
            }
        }
    }

    private fun translateTelecomState(telecomState: Int) {
        val state = when (telecomState) {
            Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> CallState.DIALING
            Call.STATE_DIALING -> CallState.DIALING
            Call.STATE_RINGING -> CallState.RINGING
            Call.STATE_ACTIVE -> CallState.ACTIVE
            Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
            else -> CallState.ACTIVE
        }
        _callState.value = state
        addDebugLog("Native status state updated: $state")

        if (state == CallState.ACTIVE) {
            startDurationTracker()
        }
    }

    // --- Simulated Call Lifecycle ---
    private fun startSimulationCall(context: Context) {
        simulationJob?.cancel()
        _callState.value = CallState.DIALING
        addDebugLog("Simulated call: Dialing...")

        simulationJob = managerScope.launch {
            // Simulate ringing tone delay
            delay(2000)
            _callState.value = CallState.ACTIVE
            addDebugLog("Simulated call: Switched to ACTIVE.")
            startDurationTracker()
            
            // Apply current speaker mode upon starting call to force immediate override
            setSpeakerphone(context, _isSpeakerphoneOn.value)
        }
    }

    private fun stopSimulationCall(context: Context) {
        simulationJob?.cancel()
        stopDurationTracker()
        _callState.value = CallState.DISCONNECTED
        addDebugLog("Simulated call: Call Disconnected.")

        managerScope.launch {
            delay(1500)
            if (_callState.value == CallState.DISCONNECTED) {
                _callState.value = CallState.NONE
                _phoneNumber.value = ""
            }
        }
    }

    private fun startDurationTracker() {
        durationJob?.cancel()
        _callDuration.value = 0L
        durationJob = managerScope.launch {
            while (true) {
                delay(1000)
                _callDuration.value += 1
            }
        }
    }

    private fun stopDurationTracker() {
        durationJob?.cancel()
        durationJob = null
    }
}

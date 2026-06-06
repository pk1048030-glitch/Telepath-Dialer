package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val REQUEST_CODE_SET_DEFAULT_DIALER = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) { // Force elegant premium dark theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F111A) // Deep cosmic dark background
                ) {
                    DialerApp()
                }
            }
        }
    }

    // Requests the user to set this app as their Default Dialer
    fun requestDefaultDialer(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val selfPackage = context.packageName
        if (telecomManager.defaultDialerPackage != selfPackage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_DIALER)
                }
            } else {
                @Suppress("DEPRECATION")
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, selfPackage)
                startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_DIALER)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DialerApp() {
    val context = LocalContext.current
    val currentCallState by CallManager.callState.collectAsStateWithLifecycle()
    val isSimulated by CallManager.isSimulationMode.collectAsStateWithLifecycle()

    // Real call permissions state
    val callPermissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
    )

    // Monitor default dialer status dynamically
    var isDefaultDialer by remember { mutableStateOf(false) }

    fun checkDefaultDialer() {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        isDefaultDialer = tm.defaultDialerPackage == context.packageName
    }

    // Run initial checks and reset on entry
    LaunchedEffect(Unit) {
        checkDefaultDialer()
        CallManager.resetAudio(context)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Screen swapping based on call status
            AnimatedContent(
                targetState = currentCallState,
                transitionSpec = {
                    if (targetState != CallState.NONE) {
                        (slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) { it } + fadeIn())
                            .togetherWith(slideOutVertically { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn())
                            .togetherWith(slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "ScreenTransition"
            ) { state ->
                if (state == CallState.NONE) {
                    // DIALPAD SCREEN
                    DialpadScreen(
                        isSimulated = isSimulated,
                        isDefaultDialer = isDefaultDialer,
                        hasPermissions = callPermissionsState.allPermissionsGranted,
                        onDialClick = { number ->
                            if (isSimulated) {
                                CallManager.dial(context, number)
                            } else {
                                if (callPermissionsState.allPermissionsGranted) {
                                    CallManager.dial(context, number)
                                } else {
                                    callPermissionsState.launchMultiplePermissionRequest()
                                }
                            }
                        },
                        onToggleSimulation = { CallManager.setSimulationMode(it) },
                        onRequestDefaultDialer = {
                            (context as? MainActivity)?.requestDefaultDialer(context)
                            checkDefaultDialer()
                        }
                    )
                } else {
                    // CUSTOM IN-CALL SCREEN
                    InCallScreen(
                        state = state,
                        isSimulated = isSimulated,
                        onHangUp = { CallManager.hangUp(context) }
                    )
                }
            }
        }
    }
}

@Composable
fun DialpadScreen(
    isSimulated: Boolean,
    isDefaultDialer: Boolean,
    hasPermissions: Boolean,
    onDialClick: (String) -> Unit,
    onToggleSimulation: (Boolean) -> Unit,
    onRequestDefaultDialer: () -> Unit
) {
    var dialInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        AppHeader(
            isSimulated = isSimulated,
            onToggleSimulation = onToggleSimulation
        )

        // Default dialer and permission checklist warning row
        if (!isSimulated && (!isDefaultDialer || !hasPermissions)) {
            DefaultDialerBanner(
                isDefault = isDefaultDialer,
                hasPermissions = hasPermissions,
                onActivateClick = onRequestDefaultDialer
            )
        } else if (isSimulated) {
            SimulationBadge()
        }

        Spacer(modifier = Modifier.weight(1f))

        // Large high-contrast dial number display
        DialDisplay(
            number = dialInput,
            onBackspace = {
                if (dialInput.isNotEmpty()) {
                    dialInput = dialInput.substring(0, dialInput.length - 1)
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Standard circular tactile dial grid
        DialpadGrid(
            onDigitClicked = { digit ->
                if (dialInput.length < 18) {
                    dialInput += digit
                }
            },
            onAddPlus = {
                if (dialInput.length < 18) {
                    dialInput += "+"
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Green Call Button
        Button(
            onClick = {
                if (dialInput.isNotEmpty()) {
                    onDialClick(dialInput)
                }
            },
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .testTag("dial_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (dialInput.isNotEmpty()) Color(0xFF00E676) else Color(0xFF1E293B),
                contentColor = Color.Black
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
            contentPadding = PaddingValues(0.dp),
            enabled = dialInput.isNotEmpty()
        ) {
            Icon(
                imageVector = Icons.Filled.Call,
                contentDescription = "Place Phone Call",
                modifier = Modifier.size(36.dp),
                tint = if (dialInput.isNotEmpty()) Color.Black else Color(0xFF64748B)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AppHeader(
    isSimulated: Boolean,
    onToggleSimulation: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Telepath",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            )
            Text(
                text = "Audio bug bypass active",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Medium
                )
            )
        }

        // Beautiful cosmic toggler
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF181B2A))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Simulate",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (isSimulated) Color(0xFF00E676) else Color(0xFF64748B),
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(end = 8.dp)
            )
            Switch(
                checked = isSimulated,
                onCheckedChange = onToggleSimulation,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color(0xFF00E676),
                    uncheckedThumbColor = Color(0xFF64748B),
                    uncheckedTrackColor = Color(0xFF1E293B)
                ),
                thumbContent = {
                    Icon(
                        imageVector = if (isSimulated) Icons.Filled.SettingsBluetooth else Icons.Filled.CellTower,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = Color.Unspecified
                    )
                },
                modifier = Modifier.scale(0.85f)
            )
        }
    }
}

@Composable
fun DefaultDialerBanner(
    isDefault: Boolean,
    hasPermissions: Boolean,
    onActivateClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x11FF5252)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(Color(0xFFFF5252), Color(0xFFFF7A00)))
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Carrier Mode Required Setup",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = buildString {
                        if (!hasPermissions) append("• Grant phone/carrier permissions\n")
                        if (!isDefault) append("• Register Telepath as Default Dialer")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFFA0A5C0),
                        lineHeight = 16.sp
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Button(
                onClick = onActivateClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                modifier = Modifier.padding(start = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text("Setup", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SimulationBadge() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x0A00E676)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00B0FF)))
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.WifiTethering,
                contentDescription = null,
                tint = Color(0xFF00E676),
                modifier = Modifier.padding(end = 12.dp)
            )
            Column {
                Text(
                    text = "Simulation Workspace Active",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "Perfect for instant sandboxed speaker/audio testing locally.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFFA0A5C0)
                    )
                )
            }
        }
    }
}

@Composable
fun DialDisplay(
    number: String,
    onBackspace: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = number.ifEmpty { "Enter Number" },
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                color = if (number.isEmpty()) Color(0xFF333850) else Color.White,
                fontSize = if (number.length > 12) 28.sp else 38.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("dial_output")
        )

        if (number.isNotEmpty()) {
            IconButton(
                onClick = onBackspace,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("backspace_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = Color(0xAAFF5252)
                )
            }
        }
    }
}

@Composable
fun DialpadGrid(
    onDigitClicked: (String) -> Unit,
    onAddPlus: () -> Unit
) {
    val keys = listOf(
        DialKey("1", ""), DialKey("2", "A B C"), DialKey("3", "D E F"),
        DialKey("4", "G H I"), DialKey("5", "J K L"), DialKey("6", "M N O"),
        DialKey("7", "P Q R S"), DialKey("8", "T U V"), DialKey("9", "W X Y Z"),
        DialKey("*", ""), DialKey("0", "+"), DialKey("#", "")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for (row in 0 until 4) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (col in 0 until 3) {
                    val key = keys[row * 3 + col]
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        DialButton(
                            key = key,
                            onClick = {
                                onDigitClicked(key.digit)
                            },
                            onLongClick = {
                                if (key.digit == "0") {
                                    onAddPlus()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

data class DialKey(val digit: String, val letters: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialButton(
    key: DialKey,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color(0xFF141724))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color(0xFF00E676)),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("dial_key_${key.digit}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = key.digit,
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF5E6582),
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

@Composable
fun InCallScreen(
    state: CallState,
    isSimulated: Boolean,
    onHangUp: () -> Unit
) {
    val context = LocalContext.current
    val phoneNumber by CallManager.phoneNumber.collectAsStateWithLifecycle()
    val isSpeakerOn by CallManager.isSpeakerphoneOn.collectAsStateWithLifecycle()
    val isMuted by CallManager.isMuted.collectAsStateWithLifecycle()
    val durationSeconds by CallManager.callDuration.collectAsStateWithLifecycle()
    val logs by CallManager.audioRoutingDebugLog.collectAsStateWithLifecycle()

    // Formatted duration time MM:SS
    val durationText = remember(durationSeconds) {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        String.format("%02d:%02d", minutes, seconds)
    }

    // Custom animation for glowing background rings during call
    val infiniteTransition = rememberInfiniteTransition(label = "Radar glower")
    val radarScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Radar"
    )
    val radarAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090A10))
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Large status name/number and duration
        Text(
            text = if (phoneNumber.isEmpty()) "Unknown" else phoneNumber,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 32.sp,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier.testTag("incall_caller_number")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            CallState.DIALING -> Color(0xFF00B0FF)
                            CallState.RINGING -> Color(0xFFFFAB40)
                            CallState.ACTIVE -> Color(0xFF00E676)
                            CallState.DISCONNECTED -> Color(0xFFFF5252)
                            else -> Color.Gray
                        }
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when (state) {
                    CallState.DIALING -> "Dialing out..."
                    CallState.RINGING -> "Ringing..."
                    CallState.ACTIVE -> durationText
                    CallState.DISCONNECTED -> "Call Ended"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            )
        }

        Spacer(modifier = Modifier.weight(0.4f))

        // Visual sound radar aura
        Box(
            modifier = Modifier
                .size(160.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            if (state == CallState.ACTIVE) {
                // Radar glower rings representing active call bypassing audio bugs
                Box(
                    modifier = Modifier
                        .size((160 * radarScale).dp)
                        .clip(CircleShape)
                        .background(
                            Color(if (isSpeakerOn) 0x1100E676 else 0x1100B0FF)
                        )
                )
            }

            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF1C1E30), Color(0xFF0F111E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (state) {
                        CallState.DIALING, CallState.RINGING -> Icons.Filled.PhoneInTalk
                        CallState.ACTIVE -> if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.Hearing
                        CallState.DISCONNECTED -> Icons.Filled.PhoneDisabled
                        else -> Icons.Filled.Person
                    },
                    contentDescription = null,
                    tint = when (state) {
                        CallState.ACTIVE -> if (isSpeakerOn) Color(0xFF00E676) else Color(0xFF00B0FF)
                        CallState.DISCONNECTED -> Color(0xFFFF5252)
                        else -> Color(0xFFA0A5C0)
                    },
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.4f))

        // Dynamic System Audio Log Viewer
        // Extremely polished custom tech box that feeds back exactly what properties are set in real time
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111322)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛰️ NATIVE AUDIO ROUTER LOGS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = "BYPASS ON",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Divider(color = Color(0xFF1E2235), thickness = 1.dp)
                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    reverseLayout = true
                ) {
                    items(logs.reversed()) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (log.contains("ERROR")) Color(0xFFFF5252)
                                        else if (log.contains("SPEAKER")) Color(0xFF00E676)
                                        else Color(0xFF8A93BA),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }

                    if (logs.isEmpty()) {
                        item {
                            Text(
                                text = "Waiting for audio transitions...",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFF475569),
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))

        // Control Toggles Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Loudspeaker Toggle Button (Crucial Bug-Fix feature!)
            InCallIconButton(
                icon = if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Outlined.VolumeUp,
                label = "Speaker",
                isActive = isSpeakerOn,
                testTag = "loudspeaker_toggle",
                activeColor = Color(0xFF00E676),
                onClick = {
                    CallManager.toggleSpeakerphone(context)
                }
            )

            // Mute Toggle Button
            InCallIconButton(
                icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (isMuted) "Unmute" else "Mute",
                isActive = isMuted,
                testTag = "mute_toggle",
                activeColor = Color(0xFFFFAB40),
                onClick = {
                    CallManager.toggleMute(context)
                }
            )
        }

        Spacer(modifier = Modifier.weight(0.4f))

        // Red Hang Up Button
        Button(
            onClick = onHangUp,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .testTag("hang_up_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF5252),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Filled.CallEnd,
                contentDescription = "Hang Up",
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun InCallIconButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    testTag: String,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor.copy(alpha = 0.15f) else Color(0xFF141724)
                )
                .clickable { onClick() }
                .testTag(testTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else Color(0xFFA0A5C0),
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (isActive) Color.White else Color(0xFF5E6582),
                fontWeight = FontWeight.Medium
            )
        )
    }
}

// Simple modifier scale helper for nicer Switch design
fun Modifier.scale(scale: Float): Modifier = this.then(
    scale(scale)
)

package com.sasakulab.yure_android_client

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.sasakulab.yure_android_client.ui.theme.YureandroidclientTheme
import okhttp3.*
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private var BUFFER_SIZE: Int = 30
    private var HOST_NAME: String = "wss://unstable.kusaremkn.com/yure"

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var webSocket: WebSocket? = null
    private val dataBuffer = mutableListOf<AccelerationData>()
    private val gson = Gson()
    private lateinit var yureId: String
    private val bufferLock = Any()

    private var isSharing = mutableStateOf(false)
    private var statusText = mutableStateOf("準備完了")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get or Generate ゆれ識別子
        yureId = getYureId()

        // Initialize Sensor Manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Connect WebSocket
        connectWebSocket()

        enableEdgeToEdge()
        setContent {
            YureandroidclientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    YureScreen(
                        yureId = yureId,
                        isSharing = isSharing.value,
                        statusText = statusText.value,
                        onStartClick = { startSharing() },
                        onStopClick = { stopSharing() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    // Generate ゆれ識別子
    private fun generateYureId(): String {
        val chars = "YUREyure"
        return (1..11).map { chars[Random.nextInt(8)] }.joinToString("")
    }

    // Get or generate / save ゆれ識別子
    private fun getYureId(): String {
        val prefs = getSharedPreferences("yure_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("yureId", null)
        if (id == null) {
            id = generateYureId()
            prefs.edit().putString("yureId", id).apply()
        }
        return id
    }

    // Connect WebSocket
    private fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(HOST_NAME)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    statusText.value = "Connected."
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    statusText.value = "Connection Error: ${t.message}"
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    // Start to share accelerometer data
    private fun startSharing() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            isSharing.value = true
            statusText.value = "Sharing In Progress"
        } ?: run {
            statusText.value = "Error: Accelerometer not available"
        }
    }

    // Stop to share accelerometer data
    private fun stopSharing() {
        sensorManager.unregisterListener(this)
        isSharing.value = false
        statusText.value = "Sharing Stopped"

        // Send to buffer
        synchronized(bufferLock) {
            if (dataBuffer.isNotEmpty()) {
                sendDataToServer()
            }
        }
    }

    // Received to accelerometer data
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION || it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val data = AccelerationData(
                    yureId = yureId,
                    x = it.values[0].toDouble(),
                    y = it.values[1].toDouble(),
                    z = it.values[2].toDouble(),
                    t = System.currentTimeMillis()
                )

                synchronized(bufferLock) {
                    dataBuffer.add(data)
                    if (dataBuffer.size >= BUFFER_SIZE) {
                        sendDataToServer()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do Nothing
    }

    // Send data via Websocket
    private fun sendDataToServer() {
        val dataToSend: List<AccelerationData>
        synchronized(bufferLock) {
            if (dataBuffer.isEmpty()) return
            dataToSend = dataBuffer.toList()
            dataBuffer.clear()
        }

        val json = gson.toJson(dataToSend)
        val sent = webSocket?.send(json) ?: false

        runOnUiThread {
            if (sent) {
                statusText.value = "Sent: ${dataToSend.size}"
            } else {
                statusText.value = "Failed to send data"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSharing()
        webSocket?.close(1000, "Activity destroyed")
    }
}

// Acceleration Data Class
data class AccelerationData(
    val yureId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val t: Long
)

@Composable
fun YureScreen(
    yureId: String,
    isSharing: Boolean,
    statusText: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "yureId",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = yureId,
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartClick,
            enabled = !isSharing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onStopClick,
            enabled = isSharing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop")
        }
    }
}

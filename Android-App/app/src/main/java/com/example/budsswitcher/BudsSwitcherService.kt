package com.example.budsswitcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket

class BudsSwitcherService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var clientSocket: Socket? = null

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var budsDevice: BluetoothDevice? = null
    private var audioProfile: BluetoothProfile? = null
    private var currentAndroidState = "idle"

    @RequiresApi(Build.VERSION_CODES.S)
    private lateinit var telephonyCallback: MyTelephonyCallback

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "BudsSwitcherChannel"
        const val NOTIFICATION_ID = 1
        const val TAG = "BudsSwitcherService"
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ipAddress = intent?.getStringExtra("IP_ADDRESS")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        Log.i(TAG, "서비스가 시작되었습니다.")

        if (!ipAddress.isNullOrEmpty()) {
            serviceScope.launch {
                findAndPrepareBuds()
                startStateMonitoring()
                connectAndSendMessages(ipAddress)
            }
        }
        return START_STICKY
    }

    private suspend fun connectAndSendMessages(ip: String) {
        try {
            withContext(Dispatchers.IO) { clientSocket = Socket(ip, 65432) }
            Log.i(TAG, "PC에 성공적으로 연결됨. 메시지 전송 루프 시작.")

            val outputStream: OutputStream = clientSocket!!.getOutputStream()
            while (clientSocket != null && clientSocket!!.isConnected && !clientSocket!!.isClosed) {
                try {
                    val message =
                        """{"source": "android", "status": "$currentAndroidState"}""" + "\n"
                    outputStream.write(message.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                    Log.d(TAG, "Sent: $message")
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "메시지 전송 루프 오류", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "서비스 연결 오류", e)
        } finally {
            clientSocket?.close()
            Log.w(TAG, "서비스 연결 종료됨.")
            stopSelf()
        }
    }

    private fun startStateMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = MyTelephonyCallback()
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
            }
        }
        serviceScope.launch {
            while (isActive) {
                if (telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE) {
                    checkAudioState()
                }
                delay(1000)
            }
        }
    }

    private fun checkAudioState() {
        if (audioManager.isMusicActive) {
            if (currentAndroidState == "idle") {
                Log.i(TAG, "미디어 재생 감지! 버즈 자동 연결 시도.")
                connectToBuds()
            }
            currentAndroidState = "playing"
        } else {
            if (telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE) {
                currentAndroidState = "idle"
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    inner class MyTelephonyCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    Log.i(TAG, "전화 수신 감지! 버즈 자동 연결 시도.")
                    connectToBuds()
                    currentAndroidState = "ringing"
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> currentAndroidState = "in_call"
                TelephonyManager.CALL_STATE_IDLE -> checkAudioState()
            }
        }
    }

    private fun findAndPrepareBuds() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        budsDevice =
            bluetoothAdapter.bondedDevices?.find { it.name.contains("Buds", ignoreCase = true) }
        if (budsDevice == null) {
            Log.e(TAG, "페어링된 버즈 없음.")
            return
        }
        Log.i(TAG, "버즈 장치 찾음: ${budsDevice!!.name}")
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    audioProfile = proxy
                    Log.i(TAG, "A2DP 프로필 준비 완료.")
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) audioProfile = null
            }
        }, BluetoothProfile.A2DP)
    }

    private fun connectToBuds() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        if (budsDevice == null || audioProfile == null) {
            Log.w(TAG, "버즈 장치/프로필 미준비 상태. 재검색 시도.")
            findAndPrepareBuds()
            return
        }
        try {
            val connectMethod =
                audioProfile!!::class.java.getMethod("connect", BluetoothDevice::class.java)
            connectMethod.invoke(audioProfile, budsDevice)
            Log.i(TAG, "${budsDevice!!.name}에 연결 명령 전송.")
        } catch (e: Exception) {
            Log.e(TAG, "버즈 연결 실패", e)
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Buds Switcher Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlag)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Buds Switcher")
            .setContentText("PC와 연결하여 작동 중입니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        clientSocket?.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ::telephonyCallback.isInitialized) {
            telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        }
        if (audioProfile != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, audioProfile)
        }
        Log.i(TAG, "서비스가 종료되었습니다.")
    }

    // 오류 해결을 위해 onBind 함수 추가
    override fun onBind(intent: Intent?): IBinder? {
        return null // 바인딩을 사용하지 않으므로 null 반환
    }
}
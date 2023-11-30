package tech.ula.library

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ftdi.j2xx.D2xxManager
import com.ftdi.j2xx.D2xxManager.*
import com.ftdi.j2xx.FT_Device
import com.google.gson.Gson
import com.google.mlkit.md.LiveBarcodeScanningActivity
import com.iiordanov.bVNC.RemoteCanvasActivity
import com.termux.app.TermuxActivity
import kotlinx.coroutines.*
import tech.ula.customlibrary.BuildConfig
import tech.ula.library.model.entities.App
import tech.ula.library.model.entities.ServiceType
import tech.ula.library.model.entities.Session
import tech.ula.library.model.repositories.UlaDatabase
import tech.ula.library.utils.*
import java.io.*
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext


class ServerService : Service(), CoroutineScope {

    var ftd2xx: D2xxManager? = null
    var serverSockets = Array<ServerSocket?>(4) {null}
    var tcpSockets = Array<Socket?>(4) {null}
    var socketThreads = Array<Thread?>(4) {null}
    var readThreads = Array<Thread?>(4) {null}
    var writeThreads = Array<Thread?>(4) {null}
    var scheduleTaskExecutor: ScheduledExecutorService? = null


    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    companion object {
        const val SERVER_SERVICE_RESULT: String = "tech.ula.library.ServerService.RESULT"
        private val TAG = ServerService::class.java.simpleName
    }

    private val activeSessions: MutableMap<Long, Session> = mutableMapOf()

    private lateinit var lastSession: Session

    private lateinit var broadcaster: LocalBroadcastManager

    private val notificationManager: NotificationConstructor by lazy {
        NotificationConstructor(this)
    }

    private val busyboxExecutor by lazy {
        val ulaFiles = UlaFiles(this, this.applicationInfo.nativeLibraryDir)
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, ulaFiles)
        BusyboxExecutor(ulaFiles, prootDebugLogger)
    }

    private val localServerManager by lazy {
        LocalServerManager(this.filesDir.path, busyboxExecutor, this.defaultSharedPreferences)
    }

    private fun setConfig(ftDev: FT_Device, baud: Int, dataBits: Byte, stopBits: Byte, parity: Byte, flowControl: Short) {
        ftDev.setBitMode(0.toByte(), D2xxManager.FT_BITMODE_RESET)
        ftDev.setBaudRate(baud)
        ftDev.setDataCharacteristics(dataBits, stopBits, parity)
        ftDev.setFlowControl(flowControl, 0x00.toByte(), 0x00.toByte())
    }

    fun readThread (channel: Int, ftDev: FT_Device, outputStream: DataOutputStream) {
        var availBytes = 0
        try {
            while (true) {
                synchronized(ftDev) {
                    availBytes = ftDev.getQueueStatus()
                    if (availBytes > 0) {
                        var readData = ByteArray(availBytes)
                        ftDev.read(readData, availBytes)
                        outputStream.write(readData)
                    }
                }
                if (availBytes == 0) {
                    sleep(50)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            outputStream.close()
        }
    }

    fun writeThread(channel: Int, ftDev: FT_Device, inputStream: DataInputStream) {
        var availBytes = 0
        var running = true
        var readData = ByteArray(8096)
        try {
            while (running) {
                availBytes = inputStream.read(readData)
                if (availBytes > 0) {
                    synchronized(ftDev) {
                        ftDev.write(readData, availBytes)
                    }
                } else {
                    Log.i(TAG, "Client disconnected.")
                    running = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            readThreads[channel]?.interrupt() //kill associated read channel
            tcpSockets[channel]?.close() //close things from our end
            tcpSockets[channel] = null
            inputStream.close()
        }
    }

    fun socketThread(channel: Int, port: String, baudRate: String, dataBits: String, stopBits: String, parity: String, flow: String) {
        var newSocket: Socket? = null

        val ftDev = ftd2xx!!.openByIndex(this, channel)
        setConfig(ftDev, baudRate.toInt(), dataBits.toByte(), stopBits.toByte(), parity.toByte(), flow.toShort())

        try {
            serverSockets[channel]?.close()
            serverSockets[channel] = ServerSocket(port.toInt())
            while (true) {
                if (serverSockets[channel] != null) {
                    newSocket = serverSockets[channel]!!.accept()
                    val outputStream = DataOutputStream(newSocket.getOutputStream())
                    val inputStream = DataInputStream(newSocket.getInputStream())
                    if (tcpSockets[channel] != null) {
                        Log.i(TAG, "Client already running.")
                        outputStream.writeByte(1)
                    } else {
                        outputStream.writeByte(0)
                        tcpSockets[channel] = newSocket
                        Log.i(TAG, "New client: ${tcpSockets[channel]}")
                        readThreads[channel] = Thread { readThread(channel, ftDev, outputStream) }
                        writeThreads[channel] = Thread { writeThread(channel, ftDev, inputStream) }
                        readThreads[channel]?.start()
                        writeThreads[channel]?.start()
                    }
                } else {
                    Log.e(TAG, "Couldn't create ServerSocket!")
                }
            }
            serverSockets[channel]?.close()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                tcpSockets[channel]?.close()
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
            ftDev!!.close()
        }
    }

    fun startFTDI() {
        if (this.defaultSharedPreferences.getBoolean("pref_rs232_enabled", false)) {
            var devCount = 0
            devCount = ftd2xx!!.createDeviceInfoList(this)
            Log.i("Ftdi", "Device number = " + Integer.toString(devCount))

            if (devCount > 0) {
                val deviceList = arrayOfNulls<FtDeviceInfoListNode>(devCount)
                ftd2xx!!.getDeviceInfoList(devCount, deviceList)
                if (devCount > 4)
                    devCount = 4 //we only support 4 rs232 ports right now

                for (i in 1..devCount) {
                    if (this.defaultSharedPreferences.getBoolean("pref_rs232_port${i-1}_enabled", false)) {
                        Log.i("Ftdi", "Device description =  ${deviceList[0]!!.description}")
                        socketThreads[i - 1]?.interrupt()
                        socketThreads[i - 1] = Thread {
                            socketThread(
                                i - 1,
                                this.defaultSharedPreferences.getString(
                                    "pref_rs232_port${i-1}_port",
                                    "${9876+i-1}"
                                )!!,
                                this.defaultSharedPreferences.getString(
                                    "pref_rs232_port${i-1}_baud_rate",
                                    "9600"
                                )!!,
                                this.defaultSharedPreferences.getString(
                                    "pref_rs232_port${i-1}_data_bits",
                                    FT_DATA_BITS_8.toString()
                                )!!,
                                this.defaultSharedPreferences.getString(
                                    "pref_rs232_port${i-1}_stop_bits",
                                    FT_STOP_BITS_1.toString()
                                )!!,
                                this.defaultSharedPreferences.getString(
                                    "pref_rs232_port${i-1}_parity",
                                    FT_PARITY_NONE.toString()
                                )!!,
                                this.defaultSharedPreferences.getString(
                                    "pref_rs232_port${i-1}_flow",
                                    FT_FLOW_NONE.toString()
                                )!!
                            )
                        }
                        socketThreads[i - 1]?.start()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        broadcaster = LocalBroadcastManager.getInstance(this)
        if (BuildConfig.POLL_FOR_INTENTS) {
            try {
                ftd2xx = D2xxManager.getInstance(this)
            } catch (ex: D2xxException) {
                ex.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.getStringExtra("type")) {
            "start" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                this.launch { startSession(session) }
            }
            "stopApp" -> {
                val app: App = intent.getParcelableExtra("app")!!
                stopApp(app)
            }
            "restartRunningSession" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                startClient(session)
            }
            "kill" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                killSession(session)
            }
            "filesystemIsBeingDeleted" -> {
                val filesystemId: Long = intent.getLongExtra("filesystemId", -1)
                cleanUpFilesystem(filesystemId)
            }
            "stopAll" -> {
                activeSessions.forEach { (_, session) ->
                    killSession(session)
                }
            }
        }

        return START_STICKY
    }

    // Used in conjunction with manifest attribute `android:stopWithTask="true"`
    // to clean up when app is swiped away.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (i in 0..3) {
            readThreads[i]?.interrupt()
            writeThreads[i]?.interrupt()
            socketThreads[i]?.interrupt()
            tcpSockets[i]?.close()
            serverSockets[i]?.close()
        }
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
    }

    private fun removeSession(session: Session) {
        activeSessions.remove(session.pid)
        if (activeSessions.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateSession(session: Session) = CoroutineScope(Dispatchers.Default).launch {
        UlaDatabase.getInstance(this@ServerService).sessionDao().updateSession(session)
    }

    private fun killSession(session: Session) {
        scheduleTaskExecutor!!.shutdownNow()
        localServerManager.stopService(session)
        removeSession(session)
        session.active = false
        updateSession(session)
    }

    private suspend fun startSession(session: Session) {
        startForeground(NotificationConstructor.serviceNotificationId, notificationManager.buildPersistentServiceNotification())
        session.pid = localServerManager.startServer(session)

        if (BuildConfig.POLL_FOR_INTENTS) {
            scheduleTaskExecutor= Executors.newScheduledThreadPool(1)
            scheduleTaskExecutor!!.scheduleAtFixedRate(java.lang.Runnable { intentRequest() }, 1000, 100, TimeUnit.MILLISECONDS)
            startFTDI()
        }

        while (!localServerManager.isServerRunning(session)) {
            delay(500)
        }

        session.active = true
        updateSession(session)
        startClient(session)
        activeSessions[session.pid] = session
        lastSession = session
    }

    private fun stopApp(app: App) {
        val appSessions = activeSessions.filter { (_, session) ->
            session.name == app.name
        }
        appSessions.forEach { (_, session) ->
            killSession(session)
        }
    }

    private fun startClient(session: Session) {
        when (session.serviceType) {
            ServiceType.Ssh -> startSshClient(session)
            ServiceType.Vnc -> startVncClient(session, "com.iiordanov.freebVNC")
            ServiceType.Xsdl -> startXsdlClient("x.org.server")
            else -> sendDialogBroadcast("unhandledSessionServiceType")
        }
        sendSessionActivatedBroadcast()
    }

    private fun startSshClient(session: Session) {
        val sshIntent = Intent(this, TermuxActivity::class.java)
        sshIntent.action = Intent.ACTION_VIEW
        sshIntent.data = Uri.parse("ssh://${session.username}@localhost:2022/#userland/${session.password}")
        sshIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        startActivity(sshIntent)
    }

    private fun startVncClient(session: Session, packageName: String) {
        val bVncIntent = Intent(this, RemoteCanvasActivity::class.java)
        bVncIntent.data = Uri.parse("vnc://127.0.0.1:59${BuildConfig.VNC_DISPLAY}/?VncUsername=${session.username}&VncPassword=${session.vncPassword}")
        bVncIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val ulaFiles = UlaFiles(this, this.applicationInfo.nativeLibraryDir)
        bVncIntent.putExtra("command_dir", ulaFiles.intentsDir.absolutePath);
        bVncIntent.putExtra("hide_toolbar", this.defaultSharedPreferences.getBoolean("pref_hide_vnc_toolbar", BuildConfig.DEFAULT_HIDE_VNC_TOOLBAR))  //seems to hide after a few seconds
        bVncIntent.putExtra("hide_extra_keys", this.defaultSharedPreferences.getBoolean("pref_hide_vnc_extra_keys", BuildConfig.DEFAULT_HIDE_VNC_EXTRA_KEYS))
        val inputMode = when(this.defaultSharedPreferences.getString("pref_default_vnc_input_mode", BuildConfig.DEFAULT_VNC_INPUT_MODE)) {
            getString(R.string.input_method_direct_swipe_pan) -> com.iiordanov.bVNC.input.InputHandlerDirectSwipePan.ID
            getString(R.string.input_method_direct_drag_pan) -> com.iiordanov.bVNC.input.InputHandlerDirectDragPan.ID
            getString(R.string.input_method_touchpad) -> com.iiordanov.bVNC.input.InputHandlerTouchpad.ID
            getString(R.string.input_method_single_handed) -> com.iiordanov.bVNC.input.InputHandlerSingleHanded.ID
            else -> com.iiordanov.bVNC.input.InputHandlerDirectSwipePan.ID
        }
        bVncIntent.putExtra("input_mode", inputMode)  //works perfectly

        if (clientIsPresent(bVncIntent)) {
            this.startActivity(bVncIntent)
        } else {
            getClient(packageName)
        }
    }

    private fun startXsdlClient(packageName: String) {
        val xsdlIntent = Intent()
        xsdlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        xsdlIntent.data = Uri.parse("x11://give.me.display:4721")

        if (clientIsPresent(xsdlIntent)) {
            startActivity(xsdlIntent)
        } else {
            getClient(packageName)
        }
    }

    private fun clientIsPresent(intent: Intent): Boolean {
        val activities = packageManager.queryIntentActivities(intent, 0)
        return (activities.size > 0)
    }

    private fun getClient(packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            this.startActivity(intent)
        } catch (err: ActivityNotFoundException) {
            sendDialogBroadcast("playStoreMissingForClient")
        }
    }

    private fun cleanUpFilesystem(filesystemId: Long) {
        activeSessions.values.filter { it.filesystemId == filesystemId }
                .forEach { killSession(it) }
    }

    private fun sendSessionActivatedBroadcast() {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "sessionActivated")
        broadcaster.sendBroadcast(intent)
    }

    private fun sendDialogBroadcast(type: String) {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "dialog")
                .putExtra("dialogType", type)
        broadcaster.sendBroadcast(intent)
    }

    private fun intentRequest() {
        val ulaFiles = UlaFiles(this, this.applicationInfo.nativeLibraryDir)
        val cameraRequest = File(ulaFiles.intentsDir, "cameraRequest.txt")
        if (cameraRequest.exists()) {
            val cameraRequestText = cameraRequest.readText(Charsets.UTF_8).trim()
            cameraRequest.delete()
            if (cameraRequestText.endsWith("barcode.txt")) {
                val barcodeIntent = Intent(this, LiveBarcodeScanningActivity::class.java)
                barcodeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                this.startActivity(barcodeIntent)
            } else if (cameraRequestText.endsWith("tone.txt")) {
                val toneFile = File(ulaFiles.intentsDir, "tone.txt")
                val toneInfo = toneFile.readText(Charsets.UTF_8).trim()
                val (
                        tone,
                        volume,
                        duration
                ) = toneInfo.toLowerCase(Locale.ENGLISH).split(",")
                toneFile.delete()
                val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, volume.toInt())
                toneGenerator.startTone(tone.toInt(), duration.toInt())
            } else if (cameraRequestText.endsWith("record_speech.txt")) {
                val recodeSpeechIntent = Intent(this, RecordSpeechActivity::class.java)
                recodeSpeechIntent.type = "record_speech"
                recodeSpeechIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                this.startActivity(recodeSpeechIntent)
            } else if (cameraRequestText.endsWith("kill_sessions")) {
                activeSessions.forEach { (_, session) ->
                    killSession(session)
                }
            } else if (cameraRequestText.endsWith("restart_app")) {
                activeSessions.forEach { (_, session) ->
                    killSession(session)
                }
                val mainIntent = Intent(this, MainActivity::class.java)
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                this.startActivity(mainIntent)
            } else if (cameraRequestText.endsWith("restart_app_no_auto_start")) {
                activeSessions.forEach { (_, session) ->
                    killSession(session)
                }
                val mainIntent = Intent(this, MainActivity::class.java)
                mainIntent.type = "no_auto_start"
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                this.startActivity(mainIntent)
            } else {
                val cameraIntent = Intent(this, CameraActivity::class.java)
                cameraIntent.type = "take_picture"
                cameraIntent.putExtra("cameraRequest", cameraRequestText)
                cameraIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                this.startActivity(cameraIntent)
            }
        }
    }
}
package tech.ula.library

import android.R.attr.port
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
import com.ftdi.j2xx.D2xxManager.D2xxException
import com.ftdi.j2xx.D2xxManager.FtDeviceInfoListNode
import com.ftdi.j2xx.FT_Device
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
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext


class ServerService : Service(), CoroutineScope {

    var ftd2xx: D2xxManager? = null

    var ft_device_0: FT_Device? = null
    var ft_device_1: FT_Device? = null
    var ft_device_2: FT_Device? = null
    var ft_device_3: FT_Device? = null

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    companion object {
        const val SERVER_SERVICE_RESULT: String = "tech.ula.library.ServerService.RESULT"
        private val TAG = ServerService::class.java.simpleName
        private const val PORT = 9876
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

    private var serverSocket: ServerSocket? = null
    private val working = AtomicBoolean(true)
    private val runnable = Runnable {
        var socket: Socket? = null
        try {
            var devCount = 0

            while (devCount == 0) {
                devCount = ftd2xx!!.createDeviceInfoList(this)
                if (devCount == 0)
                    Thread.sleep(1000L)
            }
            Log.i("Ftdi", "Device number = " + Integer.toString(devCount))

            val deviceList = arrayOfNulls<FtDeviceInfoListNode>(devCount)
            ftd2xx!!.getDeviceInfoList(devCount, deviceList)
            Log.i("Ftdi","Device description =  ${deviceList[0]!!.description}")

            val ft_device_0 = ftd2xx!!.openByIndex(this, 0)
            val ft_device_1 = ftd2xx!!.openByIndex(this, 1)
            val ft_device_2 = ftd2xx!!.openByIndex(this, 2)
            val ft_device_3 = ftd2xx!!.openByIndex(this, 3)
            serverSocket = ServerSocket(PORT)
            while (working.get()) {
                if (serverSocket != null) {
                    socket = serverSocket!!.accept()
                    Log.i(TAG, "New client: $socket")
                    val outputStream = DataOutputStream(socket.getOutputStream())
                    val inputStream = DataInputStream(socket.getInputStream())
                    // Use threads for each client to communicate with them simultaneously
                    val t: Thread = TcpClientHandler(inputStream, outputStream, ft_device_0, ft_device_1, ft_device_2, ft_device_3)
                    t.start()
                } else {
                    Log.e(TAG, "Couldn't create ServerSocket!")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            try {
                socket?.close()
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
            ft_device_0!!.close()
            ft_device_1!!.close()
            ft_device_2!!.close()
            ft_device_3!!.close()
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
        working.set(false)
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
        localServerManager.stopService(session)
        removeSession(session)
        session.active = false
        updateSession(session)
    }

    private suspend fun startSession(session: Session) {
        startForeground(NotificationConstructor.serviceNotificationId, notificationManager.buildPersistentServiceNotification())
        session.pid = localServerManager.startServer(session)

        if (BuildConfig.POLL_FOR_INTENTS) {
            val scheduleTaskExecutor= Executors.newScheduledThreadPool(1)
            scheduleTaskExecutor.scheduleAtFixedRate(java.lang.Runnable { intentRequest() }, 1000, 100, TimeUnit.MILLISECONDS)
            Thread(runnable).start()
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

class TcpClientHandler(private val inputStream: DataInputStream, private val outputStream: DataOutputStream, private val ft_device_0: FT_Device, private val ft_device_1: FT_Device, private val ft_device_2: FT_Device, private val ft_device_3: FT_Device) : Thread() {

    val SOCK_CMD_GET_UART_DEV_CNT: Byte = 1
    val SOCK_CMD_SET_UART_CONFIG: Byte = 2
    val SOCK_CMD_WR_UART_BYTES: Byte = 3
    val SOCK_CMD_RD_UART_BYTES: Byte = 4
    val SOCK_RSP_OK: Byte = 0
    val SOCK_RSP_ERR: Byte = 1

    var uart_configured_0 = false
    var uart_configured_1 = false
    var uart_configured_2 = false
    var uart_configured_3 = false

    fun setConfig(index: Byte, baud: Int, dataBits: Byte, stopBits: Byte, parity: Byte, flowControl: Short): Boolean {
        var ftDev: FT_Device
        ftDev = when (index.toInt()) {
            0 -> ft_device_0
            1 -> ft_device_1
            2 -> ft_device_2
            3 -> ft_device_3
            else -> ft_device_0
        }
        if (ftDev.isOpen == false) {
            Log.e(TAG, "SetConfig: ftDev not open!!!!!!  index:$index")
        } else {
            Log.i(TAG, "SetConfig: ftDev open, index:$index")
        }
        // configure our port
        // reset to UART mode for 232 devices
        ftDev.setBitMode(0.toByte(), D2xxManager.FT_BITMODE_RESET)

        // set 230400 baud rate
        // ftdid2xx.setBaudRate(9600 );
        ftDev.setBaudRate(baud)
        ftDev.setDataCharacteristics(dataBits, stopBits, parity)
        ftDev.setFlowControl(flowControl, 0x00.toByte(), 0x00.toByte())
        when (index.toInt()) {
            0 -> {
                uart_configured_0 = true
            }
            1 -> {
                uart_configured_1 = true
            }
            2 -> {
                uart_configured_2 = true
            }
            3 -> {
                uart_configured_3 = true
            }
        }
        return true
    }

    fun sendMessage(index: Byte, writeData: ByteArray): Int {
        var ftDev: FT_Device
        ftDev = when (index.toInt()) {
            0 -> ft_device_0
            1 -> ft_device_1
            2 -> ft_device_2
            3 -> ft_device_3
            else -> ft_device_0
        }
        ftDev.latencyTimer = 16.toByte()

        // ftDev.Purge(true, true);
        val len = ftDev.write(writeData, writeData.size)
        if (len == 0)
            Log.e(TAG, "sendMessage wrote 0 bytes")
        else
            Log.i(TAG, "sendMessage wrote $writeData")
        return len
    }

    fun receiveMessage(index: Byte, readData: ByteArray): Int {
        var ftDev: FT_Device
        ftDev = when (index.toInt()) {
            0 -> ft_device_0
            1 -> ft_device_1
            2 -> ft_device_2
            3 -> ft_device_3
            else -> ft_device_0
        }
        var iavailable = ftDev.queueStatus
        if (iavailable > 0) {
            var len = ftDev.read(readData, iavailable)
            if (len == 0)
                Log.e(TAG, "receiveMessage read 0 bytes")
            else
                Log.i(TAG, "receiveMessage read $readData")
            return len
        } else {
            Log.e(TAG, "receiveMessage queue empty")
            return 0
        }
    }

    override fun run() {
        while (true) {
            try {
                if (inputStream.available() > 0) {
                    val commandByte = inputStream.readByte()
                    Log.i(TAG, "Received command: $commandByte")
                    if (commandByte == SOCK_CMD_SET_UART_CONFIG) {
                        val index = inputStream.readByte()
                        val baud = inputStream.readInt()
                        val dataBits = inputStream.readByte()
                        val stopBits = inputStream.readByte()
                        val parity = inputStream.readByte()
                        val flowControl = inputStream.readShort()
                        setConfig(index, baud, dataBits, stopBits, parity, flowControl)
                        outputStream.writeByte(SOCK_RSP_OK.toInt())
                    } else if (commandByte == SOCK_CMD_WR_UART_BYTES) {
                        val index = inputStream.readByte()
                        val length = inputStream.readInt()
                        val writeData = ByteArray(length)
                        inputStream.readFully(writeData)
                        sendMessage(index, writeData)
                        outputStream.writeByte(SOCK_RSP_OK.toInt())
                    } else if (commandByte == SOCK_CMD_RD_UART_BYTES) {
                        val index = inputStream.readByte()
                        val length = inputStream.readInt()
                        val readData = ByteArray(length)
                        receiveMessage(index, readData)
                        outputStream.writeByte(SOCK_RSP_OK.toInt())
                        outputStream.write(readData)
                    } else {
                        outputStream.writeByte(SOCK_RSP_ERR.toInt())
                    }
                    /*
                    setConfig(2, 9600, 8, 1, 0, 0)
                    setConfig(3, 9600, 8, 1, 0, 0)
                    val writeData: ByteArray = byteArrayOf(commandByte)
                    sendMessage(2, writeData)
                    sleep(2000L)
                    val readData = ByteArray(1)
                    receiveMessage(3, readData)
                    outputStream.write(readData[0].toInt())
                     */
                    sleep(2000L)
                }
            } catch (e: IOException) {
                e.printStackTrace()
                try {
                    inputStream.close()
                    outputStream.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
                try {
                    inputStream.close()
                    outputStream.close()
                } catch (ex: IOException) {
                    ex.printStackTrace()
                }
            }
        }
    }

    companion object {
        private val TAG = TcpClientHandler::class.java.simpleName
    }

}
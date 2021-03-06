package com.glodanif.bluetoothchat.data.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.support.v7.app.AppCompatActivity
import com.glodanif.bluetoothchat.ChatApplication
import com.glodanif.bluetoothchat.R
import com.glodanif.bluetoothchat.data.database.ChatDatabase
import com.glodanif.bluetoothchat.data.database.Storage
import com.glodanif.bluetoothchat.data.entity.ChatMessage
import com.glodanif.bluetoothchat.data.entity.Conversation
import com.glodanif.bluetoothchat.data.model.*
import com.glodanif.bluetoothchat.ui.view.NotificationView
import com.glodanif.bluetoothchat.ui.view.NotificationViewImpl
import com.glodanif.bluetoothchat.ui.widget.ShortcutManager
import com.glodanif.bluetoothchat.ui.widget.ShortcutManagerImpl
import com.glodanif.bluetoothchat.utils.Size
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.io.IOException
import java.util.*

class BluetoothConnectionService : Service() {

    private val binder = ConnectionBinder()
    private val uiContext = UI
    private val bgContext = CommonPool
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val APP_NAME = "BluetoothChat"
    private val APP_UUID = UUID.fromString("220da3b2-41f5-11e7-a919-92ebcb67fe33")

    enum class ConnectionState { CONNECTED, CONNECTING, NOT_CONNECTED, REJECTED, PENDING, LISTENING }
    enum class ConnectionType { INCOMING, OUTCOMING }

    private var connectionListener: OnConnectionListener? = null
    private var messageListener: OnMessageListener? = null
    private var fileListener: OnFileListener? = null

    private var acceptThread: AcceptJob? = null
    private var connectThread: ConnectJob? = null
    private var dataTransferThread: DataTransferThread? = null

    @Volatile
    private var connectionState: ConnectionState = ConnectionState.NOT_CONNECTED
    @Volatile
    private var connectionType: ConnectionType? = null

    private var currentSocket: BluetoothSocket? = null
    private var currentConversation: Conversation? = null
    private var contract = Contract()

    private lateinit var db: ChatDatabase
    private lateinit var preferences: UserPreferences
    private lateinit var settings: SettingsManager

    private lateinit var application: ChatApplication
    private lateinit var notificationView: NotificationView
    private lateinit var shortcutManager: ShortcutManager

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    inner class ConnectionBinder : Binder() {

        fun getService(): BluetoothConnectionService {
            return this@BluetoothConnectionService
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = getApplication() as ChatApplication
        db = Storage.getInstance(this).db
        settings = SettingsManagerImpl(this)
        preferences = UserPreferencesImpl(this)
        notificationView = NotificationViewImpl(this)
        shortcutManager = ShortcutManagerImpl(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP) {

            isRunning = false

            connectionState = ConnectionState.NOT_CONNECTED
            cancelConnections()
            acceptThread?.cancel()
            acceptThread = null

            connectionListener?.onConnectionDestroyed()

            stopSelf()
            return START_NOT_STICKY
        }

        prepareForAccept()
        showNotification(getString(R.string.notification__ready_to_connect))
        return Service.START_STICKY
    }

    fun getCurrentConversation() = currentConversation

    fun getCurrentContract() = contract

    private fun showNotification(message: String) {
        val notification = notificationView.getForegroundNotification(message)
        startForeground(FOREGROUND_SERVICE, notification)
    }

    @Synchronized
    fun disconnect() {
        dataTransferThread?.cancel(true)
        dataTransferThread = null
        prepareForAccept()
    }

    @Synchronized
    private fun prepareForAccept() {
        cancelConnections()

        if (isRunning) {
            acceptThread = AcceptJob()
            acceptThread?.start()
            showNotification(getString(R.string.notification__ready_to_connect))
        }
    }

    @Synchronized
    fun connect(device: BluetoothDevice) {

        if (connectionState == ConnectionState.CONNECTING) {
            connectThread?.cancel()
            connectThread = null
        }

        dataTransferThread?.cancel(true)
        acceptThread?.cancel()
        dataTransferThread = null
        acceptThread = null
        currentSocket = null
        currentConversation = null
        contract.reset()
        connectionType = null

        connectThread = ConnectJob(device)
        connectThread?.start()

        launch(uiContext) { connectionListener?.onConnecting() }
    }

    @Synchronized
    fun connected(socket: BluetoothSocket, type: ConnectionType) {

        cancelConnections()

        connectionType = type
        currentSocket = socket

        acceptThread?.cancel()
        acceptThread = null

        val transferEventsListener = object : DataTransferThread.TransferEventsListener {

            override fun onMessageReceived(message: String) {
                launch(uiContext) { this@BluetoothConnectionService.onMessageReceived(message) }
            }

            override fun onMessageSent(message: String) {
                launch(uiContext) { this@BluetoothConnectionService.onMessageSent(message) }
            }

            override fun onConnectionPrepared(type: ConnectionType) {

                showNotification(getString(R.string.notification__connected_to, socket.remoteDevice.name ?: "?"))
                connectionState = ConnectionState.PENDING

                if (type == ConnectionType.OUTCOMING) {
                    contract.createConnectMessage(settings.getUserName(), settings.getUserColor()).let { message ->
                        dataTransferThread?.write(message.getDecodedMessage())
                    }
                }
            }

            override fun onConnectionCanceled() {
                currentSocket = null
                currentConversation = null
                contract.reset()
            }

            override fun onConnectionLost() {
                connectionLost()
            }
        }

        val fileEventsListener = object : DataTransferThread.OnFileListener {

            override fun onFileSendingStarted(file: TransferringFile) {

                fileListener?.onFileSendingStarted(file.name, file.size)

                currentConversation?.let {

                    val silently = application.currentChat != null && currentSocket != null &&
                            application.currentChat.equals(currentSocket?.remoteDevice?.address)

                    notificationView.showFileTransferNotification(it.displayName, it.deviceName,
                            it.deviceAddress, file, 0, silently)
                }
            }

            override fun onFileSendingProgress(file: TransferringFile, sentBytes: Long) {

                launch(uiContext) {

                    fileListener?.onFileSendingProgress(sentBytes, file.size)

                    if (currentConversation != null) {
                        notificationView.updateFileTransferNotification(sentBytes, file.size)
                    }
                }
            }

            override fun onFileSendingFinished(uid: Long, path: String) {

                contract.createFileEndMessage().let { message ->
                    dataTransferThread?.write(message.getDecodedMessage())
                }

                currentSocket?.let { socket ->

                    val message = ChatMessage(socket.remoteDevice.address, Date(), true, "").apply {
                        this.uid = uid
                        seenHere = true
                        messageType = PayloadType.IMAGE
                        filePath = path
                    }

                    launch(bgContext) {

                        val size = getImageSize(path)
                        message.fileInfo = "${size.width}x${size.height}"
                        message.fileExists = true

                        db.messagesDao().insert(message)

                        launch(uiContext) {

                            fileListener?.onFileSendingFinished()
                            messageListener?.onMessageSent(message)

                            notificationView.dismissFileTransferNotification()
                            currentConversation?.let {
                                shortcutManager.addConversationShortcut(message.deviceAddress, it.displayName, it.color)
                            }
                        }
                    }
                }
            }

            override fun onFileSendingFailed() {

                launch(uiContext) {
                    fileListener?.onFileSendingFailed()
                    notificationView.dismissFileTransferNotification()
                }
            }

            override fun onFileReceivingStarted(file: TransferringFile) {

                launch(uiContext) {

                    fileListener?.onFileReceivingStarted(file.size)

                    currentConversation?.let {

                        val silently = application.currentChat != null && currentSocket != null &&
                                application.currentChat.equals(currentSocket?.remoteDevice?.address)

                        notificationView.showFileTransferNotification(it.displayName, it.deviceName,
                                it.deviceAddress, file, 0, silently)
                    }
                }
            }

            override fun onFileReceivingProgress(file: TransferringFile, receivedBytes: Long) {

                launch(uiContext) {

                    fileListener?.onFileReceivingProgress(receivedBytes, file.size)

                    if (currentConversation != null) {
                        notificationView.updateFileTransferNotification(receivedBytes, file.size)
                    }
                }
            }

            override fun onFileReceivingFinished(uid: Long, path: String) {

                currentSocket?.remoteDevice?.let { device ->

                    val address = device.address
                    val message = ChatMessage(address, Date(), false, "").apply {
                        this.uid = uid
                        messageType = PayloadType.IMAGE
                        filePath = path
                    }

                    if (messageListener == null || application.currentChat == null || !application.currentChat.equals(address)) {
                        notificationView.dismissMessageNotification()
                        notificationView.showNewMessageNotification(getString(R.string.chat__image_message, "\uD83D\uDCCE"), currentConversation?.displayName,
                                device.name, address, preferences.isSoundEnabled())
                    } else {
                        message.seenHere = true
                    }

                    launch(bgContext) {

                        val size = getImageSize(path)
                        message.fileInfo = "${size.width}x${size.height}"
                        message.fileExists = true

                        db.messagesDao().insert(message)

                        launch(uiContext) {
                            fileListener?.onFileReceivingFinished()
                            messageListener?.onMessageReceived(message)

                            notificationView.dismissFileTransferNotification()
                            currentConversation?.let {
                                shortcutManager.addConversationShortcut(address, it.displayName, it.color)
                            }
                        }
                    }
                }
            }

            override fun onFileReceivingFailed() {
                launch(uiContext) {
                    fileListener?.onFileReceivingFailed()
                    notificationView.dismissFileTransferNotification()
                }
            }

            override fun onFileTransferCanceled(byPartner: Boolean) {
                launch(uiContext) {
                    fileListener?.onFileTransferCanceled(byPartner)
                    notificationView.dismissFileTransferNotification()
                }
            }
        }

        val eventsStrategy = TransferEventStrategy()
        val filesDirectory = File(Environment.getExternalStorageDirectory(), getString(R.string.app_name))

        dataTransferThread =
                object : DataTransferThread(socket, type, transferEventsListener, filesDirectory, fileEventsListener, eventsStrategy) {
                    override fun shouldRun(): Boolean {
                        return isConnectedOrPending()
                    }
                }
        dataTransferThread?.prepare()
        dataTransferThread?.start()

        launch(uiContext) { connectionListener?.onConnected(socket.remoteDevice) }
    }

    private fun getImageSize(path: String): Size {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        return Size(options.outWidth, options.outHeight)
    }

    @Synchronized
    fun stop() {
        cancelConnections()
        acceptThread?.cancel()
        acceptThread = null
        connectionState = ConnectionState.NOT_CONNECTED
    }

    private fun cancelConnections() {
        connectThread?.cancel()
        connectThread = null
        dataTransferThread?.cancel()
        dataTransferThread = null
        currentSocket = null
        currentConversation = null
        contract.reset()
        connectionType = null
    }

    fun sendMessage(message: Message) {

        if (isConnectedOrPending()) {

            val disconnect = message.type == Contract.MessageType.CONNECTION_REQUEST && !message.flag

            dataTransferThread?.write(message.getDecodedMessage(), disconnect)

            if (disconnect) {
                dataTransferThread?.cancel(disconnect)
                dataTransferThread = null
                prepareForAccept()
            }
        }

        if (message.type == Contract.MessageType.CONNECTION_RESPONSE) {
            if (message.flag) {
                connectionState = ConnectionState.CONNECTED
            } else {
                disconnect()
            }
            notificationView.dismissConnectionNotification()
        }
    }

    fun sendFile(file: File, type: PayloadType) {

        if (isConnected()) {
            contract.createFileStartMessage(file, type).let { message ->
                dataTransferThread?.write(message.getDecodedMessage())
                dataTransferThread?.writeFile(message.uid, file)
            }
        }
    }

    fun getTransferringFile(): TransferringFile? {
        return dataTransferThread?.getTransferringFile()
    }

    fun cancelFileTransfer() {
        dataTransferThread?.cancelFileTransfer()
        notificationView.dismissFileTransferNotification()
    }

    private fun onMessageSent(messageBody: String) = currentSocket?.let { socket ->

        val message = Message(messageBody)
        val sentMessage = ChatMessage(socket.remoteDevice.address, Date(), true, message.body)

        if (message.type == Contract.MessageType.MESSAGE) {
            sentMessage.seenHere = true
            launch(bgContext) {
                db.messagesDao().insert(sentMessage)
                launch(uiContext) { messageListener?.onMessageSent(sentMessage) }
                currentConversation?.let {
                    shortcutManager.addConversationShortcut(sentMessage.deviceAddress, it.displayName, it.color)
                }
            }
        }
    }

    private fun onMessageReceived(messageBody: String) {

        val message = Message(messageBody)

        if (message.type == Contract.MessageType.MESSAGE && currentSocket != null) {

            handleReceivedMessage(message.uid, message.body)

        } else if (message.type == Contract.MessageType.DELIVERY) {

            if (message.flag) {
                messageListener?.onMessageDelivered(message.uid)
            } else {
                messageListener?.onMessageNotDelivered(message.uid)
            }
        } else if (message.type == Contract.MessageType.SEEING) {

            if (message.flag) {
                messageListener?.onMessageSeen(message.uid)
            }
        } else if (message.type == Contract.MessageType.CONNECTION_RESPONSE) {

            if (message.flag) {
                handleConnectionApproval(message)
            } else {
                connectionState = ConnectionState.REJECTED
                prepareForAccept()
                connectionListener?.onConnectionRejected()
            }
        } else if (message.type == Contract.MessageType.CONNECTION_REQUEST && currentSocket != null) {

            if (message.flag) {
                handleConnectionRequest(message)
            } else {
                disconnect()
                connectionListener?.onDisconnected()
            }
        } else if (message.type == Contract.MessageType.FILE_CANCELED) {
            dataTransferThread?.cancelFileTransfer()
        }
    }

    private fun handleReceivedMessage(uid: Long, text: String) = currentSocket?.let { socket ->

        val device: BluetoothDevice = socket.remoteDevice

        val receivedMessage = ChatMessage(device.address, Date(), false, text)
        receivedMessage.uid = uid

        if (messageListener == null || application.currentChat == null || !application.currentChat.equals(device.address)) {
            notificationView.showNewMessageNotification(text, currentConversation?.displayName,
                    device.name, device.address, preferences.isSoundEnabled())
        } else {
            receivedMessage.seenHere = true
        }

        launch(bgContext) {
            db.messagesDao().insert(receivedMessage)
            launch(uiContext) { messageListener?.onMessageReceived(receivedMessage) }
            currentConversation?.let {
                shortcutManager.addConversationShortcut(device.address, it.displayName, it.color)
            }
        }
    }

    private fun handleConnectionRequest(message: Message) = currentSocket?.let { socket ->

        val device: BluetoothDevice = socket.remoteDevice

        val parts = message.body.split("#")
        val conversation = Conversation(device.address, device.name?: "?", parts[0], parts[1].toInt())

        launch(bgContext) { db.conversationsDao().insert(conversation) }

        currentConversation = conversation
        contract setupWith if (parts.size >= 3) parts[2].trim().toInt() else 0

        connectionListener?.onConnectedIn(conversation)

        if (!application.isConversationsOpened && !(application.currentChat != null && application.currentChat.equals(device.address))) {
            notificationView.showConnectionRequestNotification(
                    "${conversation.displayName} (${conversation.deviceName})", preferences.isSoundEnabled())
        }
    }

    private fun handleConnectionApproval(message: Message) = currentSocket?.let { socket ->

        val device: BluetoothDevice = socket.remoteDevice

        val parts = message.body.split("#")
        val conversation = Conversation(device.address, device.name ?: "?", parts[0], parts[1].toInt())

        launch(bgContext) { db.conversationsDao().insert(conversation) }

        currentConversation = conversation
        contract setupWith if (parts.size >= 3) parts[2].trim().toInt() else 0

        connectionState = ConnectionState.CONNECTED
        connectionListener?.onConnectionAccepted()
        connectionListener?.onConnectedOut(conversation)
    }

    private fun connectionFailed() {
        currentSocket = null
        currentConversation = null
        contract.reset()
        launch(uiContext) { connectionListener?.onConnectionFailed() }
        connectionState = ConnectionState.NOT_CONNECTED
        prepareForAccept()
    }

    private fun connectionLost() {

        currentSocket = null
        currentConversation = null
        contract.reset()
        if (isConnectedOrPending()) {
            launch(uiContext) {
                if (isPending() && connectionType == ConnectionType.INCOMING) {
                    connectionState = ConnectionState.NOT_CONNECTED
                    connectionListener?.onConnectionWithdrawn()
                } else {
                    connectionState = ConnectionState.NOT_CONNECTED
                    connectionListener?.onConnectionLost()
                }
                prepareForAccept()
            }
        } else {
            prepareForAccept()
        }
    }

    fun isConnected() = connectionState == ConnectionState.CONNECTED

    fun isPending() = connectionState == ConnectionState.PENDING

    fun isConnectedOrPending() = isConnected() || isPending()

    fun setConnectionListener(listener: OnConnectionListener?) {
        this.connectionListener = listener
    }

    fun setMessageListener(listener: OnMessageListener?) {
        this.messageListener = listener
    }

    fun setFileListener(listener: OnFileListener?) {
        this.fileListener = listener
    }

    private inner class AcceptJob {

        private var serverSocket: BluetoothServerSocket? = null

        init {
            try {
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
            } catch (e: IOException) {
                e.printStackTrace()
            }

            connectionState = ConnectionState.LISTENING
        }

        fun start() = launch(bgContext) {

            while (!isConnectedOrPending()) {

                try {
                    serverSocket?.accept()?.let { socket ->
                        when (connectionState) {
                            ConnectionState.LISTENING, ConnectionState.CONNECTING -> {
                                connected(socket, ConnectionType.INCOMING)
                            }
                            ConnectionState.NOT_CONNECTED, ConnectionState.CONNECTED, ConnectionState.PENDING, ConnectionState.REJECTED -> try {
                                socket.close()
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    break
                }
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private inner class ConnectJob(private val bluetoothDevice: BluetoothDevice) {

        private var socket: BluetoothSocket? = null

        fun start() = launch(bgContext) {

            try {
                socket = bluetoothDevice.createRfcommSocketToServiceRecord(APP_UUID)
            } catch (e: IOException) {
                e.printStackTrace()
            }
            connectionState = ConnectionState.CONNECTING

            try {
                socket?.connect()
            } catch (connectException: IOException) {
                connectException.printStackTrace()
                try {
                    socket?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                connectionFailed()
                return@launch
            }

            synchronized(this@BluetoothConnectionService) {
                connectThread = null
            }

            socket?.let {
                connected(it, ConnectionType.OUTCOMING)
            }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cancelConnections()
        acceptThread?.cancel()
        acceptThread = null
    }

    companion object {

        var isRunning = false

        private const val FOREGROUND_SERVICE = 101
        const val ACTION_STOP = "action.stop"

        fun start(context: Context) {
            val intent = Intent(context, BluetoothConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun bind(context: Context, connection: ServiceConnection) {
            val intent = Intent(context, BluetoothConnectionService::class.java)
            context.bindService(intent, connection, AppCompatActivity.BIND_ABOVE_CLIENT)
        }
    }
}

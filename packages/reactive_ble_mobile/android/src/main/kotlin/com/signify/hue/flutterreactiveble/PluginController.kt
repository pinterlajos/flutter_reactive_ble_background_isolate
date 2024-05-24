package com.signify.hue.flutterreactiveble

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.signify.hue.flutterreactiveble.ble.RequestConnectionPriorityFailed
import com.signify.hue.flutterreactiveble.channelhandlers.BleStatusHandler
import com.signify.hue.flutterreactiveble.channelhandlers.CharNotificationHandler
import com.signify.hue.flutterreactiveble.channelhandlers.DeviceConnectionHandler
import com.signify.hue.flutterreactiveble.channelhandlers.ScanDevicesHandler
import com.signify.hue.flutterreactiveble.converters.ProtobufMessageConverter
import com.signify.hue.flutterreactiveble.converters.UuidConverter
import com.signify.hue.flutterreactiveble.model.ClearGattCacheErrorType
import com.signify.hue.flutterreactiveble.utils.discard
import com.signify.hue.flutterreactiveble.utils.toConnectionPriority
import com.signify.hue.flutterreactiveble.utils.toPhyMask
import com.signify.hue.flutterreactiveble.EventForwarder
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import java.util.UUID
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import com.signify.hue.flutterreactiveble.ProtobufModel as pb

@Suppress("TooManyFunctions")
class PluginController : ActivityResultListener {
    private val pluginMethods =
        mapOf<String, (call: MethodCall, result: Result) -> Unit>(
            "initialize" to this::initializeClient,
            "deinitialize" to this::deinitializeClient,
            "scanForDevices" to this::scanForDevices,
            "connectToDevice" to this::connectToDevice,
            "clearGattCache" to this::clearGattCache,
            "disconnectFromDevice" to this::disconnectFromDevice,
            "readCharacteristic" to this::readCharacteristic,
            "writeCharacteristicWithResponse" to this::writeCharacteristicWithResponse,
            "writeCharacteristicWithoutResponse" to this::writeCharacteristicWithoutResponse,
            "readNotifications" to this::readNotifications,
            "stopNotifications" to this::stopNotifications,
            "negotiateMtuSize" to this::negotiateMtuSize,
            "requestConnectionPriority" to this::requestConnectionPriority,
            "discoverServices" to this::discoverServices,
            "getDiscoveredServices" to this::discoverServices,
            "readRssi" to this::readRssi,
            "requestEnableBluetooth" to this::requestEnableBluetooth,
            "setPreferredPhy" to this::setPreferredPhy,
            "_startEventLoop" to this::prepareReadEvents,
            "_readEvents" to this::readEvents,
            "_onListen" to this::onListen,
            "_onCancel" to this::onCancel,
            "getOsApiVersion" to this::getOsApiVersion,
    )

    private lateinit var bleClient: com.signify.hue.flutterreactiveble.ble.BleClient

    private lateinit var scanDevicesHandler: ScanDevicesHandler
    private lateinit var deviceConnectionHandler: DeviceConnectionHandler
    private lateinit var charNotificationHandler: CharNotificationHandler
    private lateinit var bleStatusHandler: BleStatusHandler
    var activity:Activity? = null

    private val uuidConverter = UuidConverter()
    private val protoConverter = ProtobufMessageConverter()

    private val eventQueue = ArrayList<EventTypeAndContents>()
    private val mainLooper = Handler(Looper.getMainLooper())
    private val eventQueueLock = ReentrantLock()
    private var pendingResult: Result? = null
    private var pendingEnableResult: Result? = null

    private val REQUEST_ENABLE_BLUETOOTH = 1771
    private val REQUEST_CODE_SCAN_ACTIVITY = 2777

    internal fun initialize(
        messenger: BinaryMessenger,
        context: Context,
    ) {
        bleClient = com.signify.hue.flutterreactiveble.ble.ReactiveBleClient(context)

        scanDevicesHandler = ScanDevicesHandler(bleClient)
        deviceConnectionHandler = DeviceConnectionHandler(bleClient)
        charNotificationHandler = CharNotificationHandler(bleClient)
        bleStatusHandler = BleStatusHandler(bleClient)
    }

    internal fun deinitialize() {
        scanDevicesHandler.stopDeviceScan()
        deviceConnectionHandler.disconnectAll()
        pendingResult = null;
        pendingEnableResult = null;
    }

    internal fun execute(
        call: MethodCall,
        result: Result,
    ) {
        pluginMethods[call.method]?.invoke(call, result) ?: result.notImplemented()
    }

    private fun initializeClient(
        call: MethodCall,
        result: Result,
    ) {
        bleClient.initializeClient()
        result.success(null)
    }

    private fun deinitializeClient(
        call: MethodCall,
        result: Result,
    ) {
        deinitialize()
        result.success(null)
    }

    private fun scanForDevices(
        call: MethodCall,
        result: Result,
    ) {
        scanDevicesHandler.prepareScan(pb.ScanForDevicesRequest.parseFrom(call.arguments as ByteArray))
        result.success(null)
    }

    private fun connectToDevice(
        call: MethodCall,
        result: Result,
    ) {
        result.success(null)
        val connectDeviceMessage = pb.ConnectToDeviceRequest.parseFrom(call.arguments as ByteArray)
        deviceConnectionHandler.connectToDevice(connectDeviceMessage)
    }

    private fun clearGattCache(
        call: MethodCall,
        result: Result,
    ) {
        val args = pb.ClearGattCacheRequest.parseFrom(call.arguments as ByteArray)
        bleClient.clearGattCache(args.deviceId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    val info = pb.ClearGattCacheInfo.getDefaultInstance()
                    result.success(info.toByteArray())
                },
                {
                    val info =
                        protoConverter.convertClearGattCacheError(
                            ClearGattCacheErrorType.UNKNOWN,
                            it.message,
                        )
                    result.success(info.toByteArray())
                },
            )
            .discard()
    }

    private fun disconnectFromDevice(
        call: MethodCall,
        result: Result,
    ) {
        result.success(null)
        val connectDeviceMessage = pb.DisconnectFromDeviceRequest.parseFrom(call.arguments as ByteArray)
        deviceConnectionHandler.disconnectDevice(connectDeviceMessage.deviceId)
    }

    private fun readCharacteristic(
        call: MethodCall,
        result: Result,
    ) {
        result.success(null)

        val readCharMessage = pb.ReadCharacteristicRequest.parseFrom(call.arguments as ByteArray)
        val deviceId = readCharMessage.characteristic.deviceId
        val characteristic = uuidConverter.uuidFromByteArray(readCharMessage.characteristic.characteristicUuid.data.toByteArray())
        val characteristicInstance = readCharMessage.characteristic.characteristicInstanceId.toInt()

        bleClient.readCharacteristic(
            deviceId,
            characteristic,
            characteristicInstance,
        )
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { charResult ->
                    when (charResult) {
                        is com.signify.hue.flutterreactiveble.ble.CharOperationSuccessful -> {
                            val charInfo =
                                protoConverter.convertCharacteristicInfo(
                                    readCharMessage.characteristic,
                                    charResult.value.toByteArray(),
                                )
                            charNotificationHandler.addSingleReadToStream(charInfo)
                        }
                        is com.signify.hue.flutterreactiveble.ble.CharOperationFailed -> {
                            protoConverter.convertCharacteristicError(
                                readCharMessage.characteristic,
                                "Failed to connect",
                            )
                            charNotificationHandler.addSingleErrorToStream(
                                readCharMessage.characteristic,
                                charResult.errorMessage,
                            )
                        }
                    }
                },
                { throwable ->
                    protoConverter.convertCharacteristicError(
                        readCharMessage.characteristic,
                        throwable.message,
                    )
                    charNotificationHandler.addSingleErrorToStream(
                        readCharMessage.characteristic,
                        throwable?.message ?: "Failure",
                    )
                },
            )
            .discard()
    }

    private fun writeCharacteristicWithResponse(
        call: MethodCall,
        result: Result,
    ) {
        executeWriteAndPropagateResultToChannel(
            call,
            result,
            com.signify.hue.flutterreactiveble.ble.BleClient::writeCharacteristicWithResponse,
        )
    }

    private fun writeCharacteristicWithoutResponse(
        call: MethodCall,
        result: Result,
    ) {
        executeWriteAndPropagateResultToChannel(
            call,
            result,
            com.signify.hue.flutterreactiveble.ble.BleClient::writeCharacteristicWithoutResponse,
        )
    }

    private fun executeWriteAndPropagateResultToChannel(
        call: MethodCall,
        result: Result,
        writeOperation: com.signify.hue.flutterreactiveble.ble.BleClient.(
            deviceId: String,
            characteristic: UUID,
            characteristicInstanceId: Int,
            value: ByteArray,
        ) -> Single<com.signify.hue.flutterreactiveble.ble.CharOperationResult>,
    ) {
        val writeCharMessage = pb.WriteCharacteristicRequest.parseFrom(call.arguments as ByteArray)
        bleClient.writeOperation(
            writeCharMessage.characteristic.deviceId,
            uuidConverter.uuidFromByteArray(writeCharMessage.characteristic.characteristicUuid.data.toByteArray()),
            writeCharMessage.characteristic.characteristicInstanceId.toInt(),
            writeCharMessage.value.toByteArray(),
        )
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { operationResult ->
                    when (operationResult) {
                        is com.signify.hue.flutterreactiveble.ble.CharOperationSuccessful -> {
                            result.success(
                                protoConverter.convertWriteCharacteristicInfo(
                                    writeCharMessage,
                                    null,
                                ).toByteArray(),
                            )
                        }
                        is com.signify.hue.flutterreactiveble.ble.CharOperationFailed -> {
                            result.success(
                                protoConverter.convertWriteCharacteristicInfo(
                                    writeCharMessage,
                                    operationResult.errorMessage,
                                ).toByteArray(),
                            )
                        }
                    }
                },
                { throwable ->
                    result.success(
                        protoConverter.convertWriteCharacteristicInfo(
                            writeCharMessage,
                            throwable.message,
                        ).toByteArray(),
                    )
                },
            )
            .discard()
    }

    private fun readNotifications(
        call: MethodCall,
        result: Result,
    ) {
        val request = pb.NotifyCharacteristicRequest.parseFrom(call.arguments as ByteArray)
        charNotificationHandler.subscribeToNotifications(request)
        result.success(null)
    }

    private fun stopNotifications(
        call: MethodCall,
        result: Result,
    ) {
        val request = pb.NotifyNoMoreCharacteristicRequest.parseFrom(call.arguments as ByteArray)
        charNotificationHandler.unsubscribeFromNotifications(request)
        result.success(null)
    }

    private fun negotiateMtuSize(
        call: MethodCall,
        result: Result,
    ) {
        val request = pb.NegotiateMtuRequest.parseFrom(call.arguments as ByteArray)
        bleClient.negotiateMtuSize(request.deviceId, request.mtuSize)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { mtuResult ->
                    result.success(protoConverter.convertNegotiateMtuInfo(mtuResult).toByteArray())
                },
                { throwable ->
                    result.success(
                        protoConverter.convertNegotiateMtuInfo(
                            com.signify.hue.flutterreactiveble.ble.MtuNegotiateFailed(
                                request.deviceId,
                                throwable.message ?: "",
                            ),
                        ).toByteArray(),
                    )
                },
            )
            .discard()
    }

    private fun requestConnectionPriority(
        call: MethodCall,
        result: Result,
    ) {
        val request = pb.ChangeConnectionPriorityRequest.parseFrom(call.arguments as ByteArray)

        bleClient.requestConnectionPriority(request.deviceId, request.priority.toConnectionPriority())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { requestResult ->
                    result.success(
                        protoConverter
                            .convertRequestConnectionPriorityInfo(requestResult).toByteArray(),
                    )
                },
                { throwable ->
                    result.success(
                        protoConverter.convertRequestConnectionPriorityInfo(
                            RequestConnectionPriorityFailed(
                                request.deviceId,
                                throwable?.message
                                    ?: "Unknown error",
                            ),
                        ).toByteArray(),
                    )
                },
            )
            .discard()
    }

    private fun discoverServices(
        call: MethodCall,
        result: Result,
    ) {
        val request = pb.DiscoverServicesRequest.parseFrom(call.arguments as ByteArray)

        bleClient.discoverServices(request.deviceId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ discoverResult ->
                result.success(protoConverter.convertDiscoverServicesInfo(request.deviceId, discoverResult).toByteArray())
            }, {
                    throwable ->
                result.error("service_discovery_failure", throwable.toString(), throwable.stackTrace.toList().toString())
            })
            .discard()
    }

    private fun readRssi(
        call: MethodCall,
        result: Result,
    ) {
        val args = pb.ReadRssiRequest.parseFrom(call.arguments as ByteArray)

        bleClient.readRssi(args.deviceId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ rssi ->
                val info = protoConverter.convertReadRssiResult(rssi)
                result.success(info.toByteArray())
            }, { error ->
                result.error("read_rssi_error", error.message, null)
            })
            .discard()
    }

    private fun setPreferredPhy(
        call: MethodCall,
        result: Result,
    ) {
        val args = pb.SetPreferredPhyRequest.parseFrom(call.arguments as ByteArray)

        bleClient.setPreferredPhy(args.deviceId, args.txPhy.toPhyMask(), args.rxPhy.toPhyMask())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ setPhyResult ->
                val info = protoConverter.convertSetPreferredPhyResult(setPhyResult)
                result.success(info.toByteArray())
            }, { error ->
                result.error("set_preferred_phy_error", error.message, null)
            })
            .discard()
    }

    private fun prepareReadEvents(call: MethodCall, result: Result) {
        eventQueueLock.lock();
        try {
            pendingResult = null
            result.success(true)
        } finally {
            eventQueueLock.unlock()
        }
    }

    private fun readEvents(call: MethodCall, result: Result) {
        eventQueueLock.lock();
        try {
            // TODO: how to listen for hot restart?
            assert(pendingResult == null)
            if (eventQueue.isNotEmpty()) {
                val merged = mergePendingEvents();
                result.success(merged)
            } else {
                assert(pendingResult == null)
                pendingResult = result
            }
        } finally {
            eventQueueLock.unlock()
        }
    }

    private fun onListen(call: MethodCall, result: Result) {
        when (val channelId = call.arguments as Int) {
            1 -> deviceConnectionHandler.onListen(null, EventForwarder(1, this))
            2 -> charNotificationHandler.onListen(null, EventForwarder(2, this))
            3 -> scanDevicesHandler.onListen(call, EventForwarder(3, this))
            4 -> bleStatusHandler.onListen(null, EventForwarder(4, this))
            else -> throw IllegalArgumentException("Unknown channel ID: $channelId")
        }
        result.success(true);
    }

    private fun onCancel(call: MethodCall, result: Result) {
        when (val channelId = call.arguments as Int) {
            1 -> deviceConnectionHandler.onCancel(null)
            2 -> charNotificationHandler.onCancel(null)
            3 -> scanDevicesHandler.onCancel(null)
            4 -> bleStatusHandler.onCancel(null)
            else -> throw IllegalArgumentException("Unknown channel ID: $channelId")
        }
        result.success(true);
    }

    private fun getOsApiVersion(call: MethodCall, result: Result) {
        val osVersion = android.os.Build.VERSION.SDK_INT
        result.success(osVersion)
    }

    private fun requestEnableBluetooth(call: MethodCall, result: Result) {
        val activity = this.activity
        if (activity != null) {
            assert(pendingEnableResult == null)
            pendingEnableResult = result
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
        } else {
            result.success(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val result = pendingEnableResult
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (result == null) {
                // Spurious result callback without a prior request (maybe the app was hot restarted?)
            } else {
                try {
                    if (resultCode == Activity.RESULT_OK) {
                        mainLooper.post {
                            result.success(true)
                        }
                    } else {
                        mainLooper.post {
                            result.success(false)
                        }
                    }
                } finally {
                    pendingEnableResult = null
                }
            }
        }
        return false
    }

    public fun forwardEvent(channelId: Int, data: ByteArray) {
        eventQueueLock.lock()
        try {
            eventQueue.add(EventTypeAndContents(channelId, data))
            // Check if we have a pending Result, that is, the Flutter side is waiting for an event
            val result = pendingResult;
            pendingResult = null;
            if (result != null) {
                val merged = mergePendingEvents()
                mainLooper.post {
                    result.success(merged)
                }
            } else {
                // There is no Result that we can use, meaning the Flutter side is processing
                // the last batch of events. Just put the event in the queue and eventually a
                // request will be made to read the events.
            }
        } finally {
            eventQueueLock.unlock()
        }
    }

    private fun mergePendingEvents() : ByteArray {
        // Merge all events into a single byte array
        val mergedLength = eventQueue.sumOf { it.data.size + 4 }
        val merged = ByteArray(mergedLength)
        var offset = 0
        for (item in eventQueue) {
            val eventCode = item.channelId
            val eventLength = item.data.size
            merged[offset++] = (eventCode shr 8).toByte()
            merged[offset++] = (eventCode shr 0).toByte()
            merged[offset++] = (eventLength shr 8).toByte()
            merged[offset++] = (eventLength shr 0).toByte()
            System.arraycopy(item.data, 0, merged, offset, item.data.size)
            offset += item.data.size
        }
        eventQueue.clear()
        return merged
    }

    class EventTypeAndContents(public val channelId: Int, public val data: ByteArray) {
    }
}

package com.signify.hue.flutterreactiveble

import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel.EventSink

class EventForwarder(private val channelId: Int, private val controller: PluginController) : EventSink {
    private val emptyByteArray = ByteArray(0)

    override fun success(data: Any?) {
        if ((data != null) && (data is ByteArray)) {
            controller.forwardEvent(channelId, data)
        }
    }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        // Convert strings to byte arrays
        val byteArray1 = errorCode.toByteArray()
        val byteArray2 = errorMessage?.toByteArray() ?: emptyByteArray

        // Create a new ByteArray to hold the result
        val resultByteArray = ByteArray(byteArray1.size + 1 + byteArray2.size + 1)

        var index = 0
        // Copy the first string and append a zero byte
        System.arraycopy(byteArray1, 0, resultByteArray, index, byteArray1.size)
        index += byteArray1.size
        resultByteArray[index++] = 0

        // Copy the second string after the zero byte
        System.arraycopy(byteArray2, 0, resultByteArray, index, byteArray2.size)
        index += byteArray2.size
        resultByteArray[index++] = 0

        controller.forwardEvent(channelId or 0x8000, resultByteArray)
    }

    override fun endOfStream() {
        //
    }
}

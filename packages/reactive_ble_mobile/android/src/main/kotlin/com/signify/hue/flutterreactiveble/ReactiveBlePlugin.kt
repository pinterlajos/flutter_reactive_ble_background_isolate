package com.signify.hue.flutterreactiveble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result

class ReactiveBlePlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        initializePlugin(binding.binaryMessenger, binding.applicationContext, this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        deinitializePlugin()
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        activityBinding = activityPluginBinding
        pluginController.activity = activityBinding.getActivity()
        activityBinding.addActivityResultListener(pluginController)
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        onAttachedToActivity(activityPluginBinding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onDetachedFromActivity() {
        activityBinding.removeActivityResultListener(pluginController)
        pluginController.activity?.finish();
        pluginController.activity = null
    }

    companion object {
        lateinit var pluginController: PluginController
        lateinit var activityBinding: ActivityPluginBinding

        @JvmStatic
        private fun initializePlugin(
            messenger: BinaryMessenger,
            context: Context,
            plugin: ReactiveBlePlugin,
        ) {
            val channel = MethodChannel(messenger, "flutter_reactive_ble_method")
            channel.setMethodCallHandler(plugin)
            pluginController = PluginController()
            pluginController.initialize(messenger, context)
        }

        @JvmStatic
        private fun deinitializePlugin() {
            pluginController.deinitialize()
        }
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result,
    ) {
        pluginController.execute(call, result)
    }
}

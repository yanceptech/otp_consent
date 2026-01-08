package com.huynn109.otp_consent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.NonNull
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.huynn109.otp_consent.SMSBroadcastReceiver.Companion.SMS_CONSENT_REQUEST
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

import java.util.regex.Pattern

/** OtpConsentPlugin */
class OtpConsentPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener, SMSBroadcastReceiver.Listener {
    private lateinit var methodChannel: MethodChannel
    private lateinit var mActivity: Activity
    private lateinit var mContext: Context
    private var isListening: Boolean = false
    private val smsBroadcastReceiver by lazy { SMSBroadcastReceiver() }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel = MethodChannel(flutterPluginBinding.flutterEngine.dartExecutor, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)
        mContext = flutterPluginBinding.applicationContext
    }

    companion object {
        val TAG: String = OtpConsentPlugin::class.java.simpleName
        const val METHOD_CHANNEL: String = "otp_consent"
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            "startListening" -> startListening(call, result)
            "stopListening" -> stopListening(result)
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        unRegisterBroadcastListener()
    }

    override fun onDetachedFromActivity() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            SMS_CONSENT_REQUEST -> {
                when {
                    resultCode == Activity.RESULT_OK && data != null -> {
                        val message = data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
                        val messageParsed = parseOneTimeCode(message)
                        val arguments: HashMap<String, String?> = HashMap()
                        arguments["sms"] = message
                        arguments["smsParsed"] = messageParsed
                        methodChannel.invokeMethod("onSmsConsentReceived", arguments)
                    }
                    else -> {
                        // Consent denied. User can type OTC manually.
                        methodChannel.invokeMethod("onSmsConsentPermissionDenied", null)
                    }
                }
                unRegisterBroadcastListener()
            }
        }
        return true
    }

    override fun onShowPermissionDialog() {
        methodChannel.invokeMethod("onShowPermissionDialog", null)
    }

    override fun onTimeout() {
        methodChannel.invokeMethod("onTimeout", null)
        unRegisterBroadcastListener()
    }

    private fun startListening(call: MethodCall, result: Result) {
        synchronized(this) {
            val senderPhoneNumber = if (call.hasArgument("senderPhoneNumber")) call.argument<String>("senderPhoneNumber") else null
            startBroadcastReceiver(result, senderPhoneNumber)
        }
    }

    private fun startBroadcastReceiver(result: Result, senderPhoneNumber: String?) {
        SmsRetriever.getClient(mActivity).startSmsUserConsent(senderPhoneNumber)
        smsBroadcastReceiver.injectListener(mActivity, this)
        // https://support.google.com/faqs/answer/9267555?hl=en&sjid=11340788373093644458-EU
        mActivity.registerReceiver(smsBroadcastReceiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION), SmsRetriever.SEND_PERMISSION, null)
        result.success(true)
    }

    private fun stopListening(result: Result) {
        unRegisterBroadcastListener()
        result.success(null)
    }

    private fun unRegisterBroadcastListener() {
        try {
            mActivity.unregisterReceiver(smsBroadcastReceiver)
            isListening = false
        } catch (ex: Exception) {
        }
    }

    private fun parseOneTimeCode(message: String?): String? {
        val pattern = Pattern.compile("\\d{4,6}")
        if (message != null) {
            val matcher = pattern.matcher(message)
            if (!matcher.find()) return message
            return matcher.group(0)
        }
        return null
    }


}

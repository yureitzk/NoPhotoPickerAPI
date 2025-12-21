package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "NoPhotoPicker"
    }

    fun XC_LoadPackage.LoadPackageParam.isSystemFramework(): Boolean {
        return packageName == "android" || appInfo == null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.isSystemFramework() -> {
                hookSystemServices(lpparam)
            }
            lpparam.packageName != null -> {
                hookActivity(lpparam)
                hookActivityResult(lpparam)
            }
        }
    }

    private fun hookSystemServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Try to find which service class exists
        val classLoader = lpparam.classLoader
        val serviceClasses = listOf(
            "com.android.server.wm.ActivityTaskManagerService",
            "com.android.server.am.ActivityManagerService",
            "com.android.server.am.ActivityStarter"
        )

        for (className in serviceClasses) {
            val serviceClass = XposedHelpers.findClassIfExists(className, classLoader)
            if (serviceClass != null) {
                XposedBridge.hookAllMethods(
                    serviceClass,
                    "startActivity",
                    createIntentInterceptor("System:$className")
                )
                XposedBridge.log("$TAG: Hooked $className")
                return
            }
        }
    }

    private fun createIntentInterceptor(source: String): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args
                for (i in args.indices) {
                    if (args[i] is Intent) {
                        val intent = args[i] as Intent
                        if (isPhotoPickerIntent(intent)) {
                            logIntentDetails(intent, "$source.startActivity")
                            args[i] = buildDocumentPickerIntent(intent)
                            return
                        }
                    }
                }
            }
        }
    }

    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[0] as? Intent ?: return
                        if (isPhotoPickerIntent(intent)) {
                            logIntentDetails(intent, "App.Activity.startActivity")
                            param.args[0] = buildDocumentPickerIntent(intent)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[0] as? Intent ?: return
                        if (isPhotoPickerIntent(intent)) {
                            logIntentDetails(intent, "App.startActivityForResult")
                            param.args[0] = buildDocumentPickerIntent(intent)
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Successfully hooked Activity methods for ${lpparam.packageName}")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Activity: ${t.message}")
        }
    }

    private fun hookActivityResult(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onActivityResult",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val requestCode = param.args[0] as Int
                        val resultCode = param.args[1] as Int
                        val data = param.args[2] as? Intent

                        // Skip if canceled or no data
                        if (resultCode != Activity.RESULT_OK || data == null) return

                        val hasContent = when {
                            data.data != null -> true
                            data.clipData?.let { clipData ->
                                (0 until clipData.itemCount).any { clipData.getItemAt(it).uri != null }
                            } ?: false -> true
                            data.hasExtra(Intent.EXTRA_STREAM) -> true
                            data.hasExtra(Intent.EXTRA_CONTENT_ANNOTATIONS) -> true
                            else -> false
                        }

                        if (!hasContent) {
                            XposedBridge.log("$TAG: Empty result detected for request $requestCode")
                            param.args[1] = Activity.RESULT_CANCELED
                            param.args[2] = null
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook onActivityResult: ${t.message}")
        }
    }

    private fun getMaxItems(intent: Intent): Int {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
        ) {
            intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)
        } else {
            -1
        }
    }

    private fun isPhotoPickerIntent(intent: Intent): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                intent.action == MediaStore.ACTION_PICK_IMAGES
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2 ->
                intent.action == MediaStore.ACTION_PICK_IMAGES ||
                        intent.action == "androidx.activity.result.contract.action.PickVisualMedia"

            else -> false
        }
    }

    private fun logIntentDetails(intent: Intent, source: String) {
        XposedBridge.log("$TAG: [$source] Photo picker detected")
        XposedBridge.log("  Action: ${intent.action}")
    }

    private fun buildDocumentPickerIntent(original: Intent): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)

            // Handle MIME types
            val mimeTypes = original.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                ?: original.getStringArrayExtra("android.provider.extra.MIME_TYPES")
                ?: original.getStringArrayExtra("androidx.activity.result.contract.extra.PickVisualMedia.MimeType")
                ?: arrayOf(original.type ?: "image/*")

            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1 || mimeTypes[0] != type) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }

            // Handle multi-select with API compatibility
            val allowMultiple = original.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            val maxItems = getMaxItems(original)

            val shouldAllowMultiple = when {
                allowMultiple -> true
                maxItems > 1 -> true
                else -> false
            }

            if (shouldAllowMultiple) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                XposedBridge.log("$TAG: Multi-select enabled")
            }

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            XposedBridge.log("$TAG: Created document picker intent")
        }
    }
}

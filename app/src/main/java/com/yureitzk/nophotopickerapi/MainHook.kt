package com.yureitzk.nophotopickerapi

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "NoPhotoPicker"
        private var isSystemFramework = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                isSystemFramework = true
                XposedBridge.log("$TAG: Hooking system framework")

                // Try multiple system hooks for better compatibility
                hookSystemServices(lpparam)
                XposedBridge.log("$TAG: System framework hooks completed")
            }
            else -> {
                // App-specific hooks for compatibility
                if (!isSystemFramework) {
                    hookActivity(lpparam)
                    hookActivityResult(lpparam)
                }
            }
        }
    }

    private fun hookSystemServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Try multiple system service hooks for different Android versions

        // Hook ActivityTaskManagerService
        try {
            hookActivityTaskManager(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ActivityTaskManagerService: ${t.message}")
        }

        // Hook ActivityManagerService
        try {
            hookActivityManagerService(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ActivityManagerService: ${t.message}")
        }

        // Hook ActivityStarter
        try {
            hookActivityStarter(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ActivityStarter: ${t.message}")
        }

        // Hook PackageManagerService
        try {
            hookPackageManagerService(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook PackageManagerService: ${t.message}")
        }
    }

    private fun hookActivityTaskManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader

            // Try different class names for different Android versions
            val activityTaskManagerClass = XposedHelpers.findClassIfExists(
                "com.android.server.wm.ActivityTaskManagerService",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "android.app.ActivityTaskManager",
                classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                activityTaskManagerClass,
                "startActivity",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // Find Intent in arguments (position varies by API)
                            for (i in param.args.indices) {
                                if (param.args[i] is Intent) {
                                    val intent = param.args[i] as Intent
                                    if (isPhotoPickerIntent(intent)) {
                                        logIntentDetails(intent, "ActivityTaskManagerService.startActivity")
                                        param.args[i] = buildDocumentPickerIntent(intent)
                                        return
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in ActivityTaskManagerService hook: ${e.message}")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Successfully hooked ActivityTaskManagerService")
        } catch (t: Throwable) {
            throw t
        }
    }

    private fun hookActivityManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader
            val activityManagerClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService",
                classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                activityManagerClass,
                "startActivity",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val args = param.args
                            for (i in args.indices) {
                                if (args[i] is Intent) {
                                    val intent = args[i] as Intent
                                    if (isPhotoPickerIntent(intent)) {
                                        logIntentDetails(intent, "ActivityManagerService.startActivity")
                                        args[i] = buildDocumentPickerIntent(intent)
                                        return
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: Error in ActivityManagerService hook: ${e.message}")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Successfully hooked ActivityManagerService")
        } catch (t: Throwable) {
            throw t
        }
    }

    private fun hookActivityStarter(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader
            val activityStarterClass = XposedHelpers.findClassIfExists(
                "com.android.server.wm.ActivityStarter",
                classLoader
            ) ?: return

            XposedBridge.hookAllMethods(
                activityStarterClass,
                "execute",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // ActivityStarter holds intent in mRequest field
                            val intentField = XposedHelpers.getObjectField(param.thisObject, "mIntent")
                            if (intentField is Intent) {
                                if (isPhotoPickerIntent(intentField)) {
                                    logIntentDetails(intentField, "ActivityStarter.execute")
                                    XposedHelpers.setObjectField(
                                        param.thisObject,
                                        "mIntent",
                                        buildDocumentPickerIntent(intentField)
                                    )
                                }
                            }
                        } catch (e: Throwable) {
                            // Ignore - field might not exist or have different name
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Successfully hooked ActivityStarter")
        } catch (t: Throwable) {
            throw t
        }
    }

    private fun hookPackageManagerService(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val classLoader = lpparam.classLoader

            // Try different class names
            val pmsClassNames = arrayOf(
                "com.android.server.pm.PackageManagerService",
                "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
                "android.content.pm.IPackageManager"
            )

            var pmsClass: Class<*>? = null
            for (className in pmsClassNames) {
                pmsClass = XposedHelpers.findClassIfExists(className, classLoader)
                if (pmsClass != null) break
            }

            if (pmsClass != null) {
                XposedBridge.hookAllMethods(
                    pmsClass,
                    "resolveIntent",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val intent = param.args.getOrNull(0) as? Intent ?: return
                                if (isPhotoPickerIntent(intent)) {
                                    logIntentDetails(intent, "PackageManagerService.resolveIntent")
                                    param.args[0] = buildDocumentPickerIntent(intent)
                                }
                            } catch (e: Throwable) {
                                // Ignore
                            }
                        }
                    }
                )

                XposedBridge.log("$TAG: Successfully hooked PackageManagerService")
            }
        } catch (t: Throwable) {
            throw t
        }
    }

    private fun hookActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook Activity.startActivity
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

            // Hook Activity.startActivityForResult
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
                        val resultCode = param.args[1] as Int
                        val data = param.args[2] as? Intent

                        if (resultCode == Activity.RESULT_OK && data != null) {
                            val hasData = data.data != null
                            val hasClipData = data.clipData != null

                            if (!hasData && !hasClipData) {
                                XposedBridge.log("$TAG: Empty result detected, changing to RESULT_CANCELED")
                                param.args[1] = Activity.RESULT_CANCELED
                                param.args[2] = null
                            } else {
                                XposedBridge.log("$TAG: Valid result - hasData: $hasData, hasClipData: $hasClipData")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook onActivityResult: ${t.message}")
        }
    }

    private fun isPhotoPickerIntent(intent: Intent): Boolean {
        return intent.action == MediaStore.ACTION_PICK_IMAGES ||
                intent.action == "androidx.activity.result.contract.action.PickVisualMedia" ||
                intent.component?.className?.contains("PhotoPicker", true) == true ||
                intent.component?.className?.contains("MediaPicker", true) == true ||
                intent.hasExtra("android.provider.extra.PICK_IMAGES") ||
                intent.hasExtra("androidx.activity.result.contract.extra.PICK_VISUAL_MEDIA_ENABLE_PHOTO_PICKER")
    }

    private fun logIntentDetails(intent: Intent, source: String) {
        XposedBridge.log("$TAG: [$source] Photo picker detected")
        XposedBridge.log("  Action: ${intent.action}")
        XposedBridge.log("  Component: ${intent.component}")
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
            val maxItems = original.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)

            val shouldAllowMultiple = when {
                allowMultiple -> true
                maxItems > 1 -> true
                // API 33+ check without calling the method on older APIs
                Build.VERSION.SDK_INT >= 33 && maxItems >= 2 -> true
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

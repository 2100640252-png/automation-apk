package com.automation.work

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat

/**
 * 权限清单：全部由用户自愿在手机上逐项同意。
 * 安卓不允许应用“直接调用所有权限”，
 * 敏感权限必须运行时申请，无障碍 / 悬浮窗 / 安装未知应用必须去系统设置里手动开。
 * v1.1：MainActivity 启动时就会主动逐项索取，不再只是摆在代码里不用。
 */
object PermissionHelper {
    val runtimePermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
    }

    data class PermissionStatus(val name: String, val granted: Boolean, val key: String)

    fun allStatus(context: Context): List<PermissionStatus> {
        val list = mutableListOf<PermissionStatus>()
        for (p in runtimePermissions) {
            val label = when (p) {
                Manifest.permission.READ_MEDIA_VIDEO -> "读取视频"
                Manifest.permission.READ_MEDIA_IMAGES -> "读取图片"
                Manifest.permission.POST_NOTIFICATIONS -> "通知"
                Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储"
                Manifest.permission.CAMERA -> "相机"
                Manifest.permission.RECORD_AUDIO -> "录音"
                else -> p
            }
            list.add(
                PermissionStatus(
                    label,
                    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED,
                    "runtime:$p"
                )
            )
        }
        list.add(PermissionStatus("无障碍屏幕控制", isAccessibilityEnabled(context), "accessibility"))
        list.add(PermissionStatus("悬浮窗", Settings.canDrawOverlays(context), "overlay"))
        list.add(
            PermissionStatus(
                "安装未知应用（用于软件内更新）",
                context.packageManager.canRequestPackageInstalls(),
                "install"
            )
        )
        list.add(PermissionStatus("精确闹钟（定时发布）", canScheduleExactAlarm(context), "alarm"))
        return list
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AutomationService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    fun canScheduleExactAlarm(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun openOverlaySettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    fun openInstallUnknownSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    fun openAppSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    /** 根据 key 打开对应的系统设置页，MainActivity 的权限面板直接调用。 */
    fun openSettingsFor(context: Context, key: String) {
        when {
            key == "accessibility" -> openAccessibilitySettings(context)
            key == "overlay" -> openOverlaySettings(context)
            key == "install" -> openInstallUnknownSettings(context)
            key == "alarm" -> openExactAlarmSettings(context)
            else -> openAppSettings(context)
        }
    }
}

package tech.ula.utils

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import tech.ula.R

class PermissionHandler {
    companion object {
        private const val permissionRequestCode = 1234

        fun basePermissionsAreGranted(context: Context): Boolean {
            return (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            )
        }

        fun manageExternalPermissionsHaveBeenRequested(context: Context): Boolean {
            return (SDK_INT < Build.VERSION_CODES.R || context.defaultSharedPreferences.getBoolean("requested_manage_external_permission", false))
        }

        fun permissionsAreGranted(context: Context): Boolean {
            return (basePermissionsAreGranted(context) && manageExternalPermissionsHaveBeenRequested(context))
        }

        fun permissionsWereGranted(requestCode: Int, grantResults: IntArray): Boolean {
            return when (requestCode) {
                permissionRequestCode -> {
                    (grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                            grantResults[1] == PackageManager.PERMISSION_GRANTED)
                }
                else -> false
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        fun showPermissionsNecessaryDialog(activity: Activity) {
            val builder = AlertDialog.Builder(activity)
            if (basePermissionsAreGranted(activity)) {
                with(activity.defaultSharedPreferences.edit()) {
                    putBoolean("requested_manage_external_permission", true)
                    apply()
                }
                builder.setMessage(R.string.alert_manage_external_permissions_necessary_message)
                    .setTitle(R.string.alert_manage_external_permissions_necessary_title)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.addCategory("android.intent.category.DEFAULT")
                            intent.setData(
                                Uri.fromParts("package", activity.packageName, null)
                            )
                            activity.startActivityForResult(intent, 2296)
                        } catch (e: Exception) {
                            val intent = Intent()
                            intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            activity.startActivityForResult(intent, 2296)
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        dialog.dismiss()
                    }
            } else {
                builder.setMessage(R.string.alert_permissions_necessary_message)
                    .setTitle(R.string.alert_permissions_necessary_title)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        activity.requestPermissions(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ),
                            permissionRequestCode
                        )
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        dialog.dismiss()
                    }
            }
            builder.create().show()
        }
    }
}
package tech.ula.library.utils

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import tech.ula.library.R
import tech.ula.customlibrary.BuildConfig

class PermissionHandler {
    companion object {
        private const val permissionRequestCode = 1234

        fun permissionsAreGranted(context: Context): Boolean {
            return (
                        ContextCompat.checkSelfPermission(context,
                                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&

                        ContextCompat.checkSelfPermission(context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&

                        if (!BuildConfig.USES_CAMERA) true else ContextCompat.checkSelfPermission(context,
                                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && 

                        if (!BuildConfig.USES_MICROPHONE) true else ContextCompat.checkSelfPermission(context,
                                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    )
        }

        fun permissionsWereGranted(requestCode: Int, grantResults: IntArray): Boolean {
            return when (requestCode) {
                permissionRequestCode -> {
                    (grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                            grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                            if (!(BuildConfig.USES_CAMERA || BuildConfig.USES_MICROPHONE)) true else grantResults[2] == PackageManager.PERMISSION_GRANTED &&
                            if (!(BuildConfig.USES_CAMERA && BuildConfig.USES_MICROPHONE)) true else grantResults[3] == PackageManager.PERMISSION_GRANTED
                    ) 
                }
                else -> false
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        fun showPermissionsNecessaryDialog(activity: Activity) {
            val builder = AlertDialog.Builder(activity)
            builder.setMessage(activity.getString(R.string.alert_permissions_necessary_message, activity.getString(R.string.app_name)))
                    .setTitle(activity.getString(R.string.alert_permissions_necessary_title,activity.getString(R.string.app_name)))
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        var requestArray = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        if (BuildConfig.USES_CAMERA) requestArray += Manifest.permission.CAMERA
                        if (BuildConfig.USES_MICROPHONE) requestArray += Manifest.permission.RECORD_AUDIO
                        activity.requestPermissions(requestArray, permissionRequestCode)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        dialog.dismiss()
                    }
            builder.create().show()
        }
    }
}

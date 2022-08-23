package tech.ula.library

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import tech.ula.library.utils.defaultSharedPreferences
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

class CameraActivity : AppCompatActivity() {

    lateinit var currentPhotoPath: String
    lateinit var currentPhotoName: String

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if ((intent != null) && intent?.type.equals("take_picture"))
            dispatchTakePictureIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.camera_activity)

        if ((intent != null) && intent?.type.equals("take_picture"))
            dispatchTakePictureIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == REQUEST_IMAGE_CAPTURE) && (resultCode == RESULT_OK)) {
            sendResult(0)
        } else {
            sendResult(1)
        }
        finish()
    }

    //Camera related logic
    val REQUEST_IMAGE_CAPTURE = 1

    @Throws(IOException::class)
    fun createImageFile(name: String): File {
        // Create an image file name
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val photoFile = File(storageDir,name)
        photoFile.createNewFile()
        currentPhotoPath = photoFile.absolutePath
        currentPhotoName = name
        return photoFile
    }

    fun dispatchTakePictureIntent(intent: Intent) {
        val name = intent.getStringExtra("cameraRequest")
        if (name != null) {
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
                // Ensure that there's a camera activity to handle the intent
                takePictureIntent.resolveActivity(packageManager)?.also {
                    // Create the File where the photo should go
                    val photoFile: File? = try {
                        createImageFile(name)
                    } catch (ex: IOException) {
                        // Error occurred while creating the File
                        sendResult(-1)
                        null
                    }
                    // Continue only if the File was successfully created
                    photoFile?.also {
                        val photoURI: Uri = FileProvider.getUriForFile(
                                this,
                                "tech.ula.provider.fileprovider",
                                it
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                        startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
                        with(defaultSharedPreferences.edit()) {
                            putBoolean("photo_pending", true)
                            apply()
                        }
                    }
                }
            }
        } else {
            sendResult(-1)
        }
    }

    fun sendResult(code: Int) {
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val resultFile = File(storageDir, ".cameraResponse.txt")
        val finalResultFile = File(storageDir, "cameraResponse.txt")
        resultFile.writeText("$code")
        resultFile.renameTo(finalResultFile)
        with(defaultSharedPreferences.edit()) {
            putBoolean("photo_pending", false)
            apply()
        }
        finish()
    }
}

package tech.ula.library

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.IOException

class RecordSpeechActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if ((intent != null) && intent?.type.equals("record_speech"))
            dispatchRecordSpeechIntent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.record_speech_activity)

        Log.d("RecordSpeech", "onCreate")

        if ((intent != null) && intent?.type.equals("record_speech"))
            dispatchRecordSpeechIntent()
    }

    @Throws(IOException::class)
    fun createSpeechFile(voiceResult: String): File {
        // Create an image file name
        val storageDir: File? = File(getExternalFilesDir(null),"Intents")
        val speechFile = File(storageDir, "record_speech.txt")
        speechFile.createNewFile()
        speechFile.writeText("$voiceResult")
        return speechFile
    }

    fun dispatchRecordSpeechIntent() {
        Log.d("RecordSpeech", "dispatchRecordSpeechIntent")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this.packageName)

        val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this.applicationContext)
        val listener: RecognitionListener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                Log.d("RecordSpeech", "onResults")
                val voiceResults = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var voiceResult = ""
                if (voiceResults == null) {
                    sendResult(1)
                } else {
                    voiceResult = voiceResults.joinToString(separator = " ")
                    createSpeechFile(voiceResult)
                    sendResult(0)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onReadyForSpeech")
            }

            /**
             * ERROR_NETWORK_TIMEOUT = 1;
             * ERROR_NETWORK = 2;
             * ERROR_AUDIO = 3;
             * ERROR_SERVER = 4;
             * ERROR_CLIENT = 5;
             * ERROR_SPEECH_TIMEOUT = 6;
             * ERROR_NO_MATCH = 7;
             * ERROR_RECOGNIZER_BUSY = 8;
             * ERROR_INSUFFICIENT_PERMISSIONS = 9;
             *
             * @param error code is defined in SpeechRecognizer
             */
            override fun onError(error: Int) {
                Log.d("RecordSpeech", "onError")
                if ((error == 7) || (error == 6)) {
                    sendResult(1)
                } else {
                    sendResult(-1)
                }
            }

            override fun onBeginningOfSpeech() {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onBeginningOfSpeech")
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onBufferReceived")
            }

            override fun onEndOfSpeech() {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onEndOfSpeech")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onEvent")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onPartialResults")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // TODO Auto-generated method stub
                Log.d("RecordSpeech", "onRmsChanged")
            }
        }
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)
    }

    fun sendResult(code: Int) {
        val storageDir: File? = File(getExternalFilesDir(null),"Intents")
        val resultFile = File(storageDir, ".cameraResponse.txt")
        val finalResultFile = File(storageDir, "cameraResponse.txt")
        resultFile.writeText("$code")
        resultFile.renameTo(finalResultFile)
        finish()
    }
}

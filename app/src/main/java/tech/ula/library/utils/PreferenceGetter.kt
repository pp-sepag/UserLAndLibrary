package tech.ula.library.utils

import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class PreferenceGetter(
        val ulaFiles: UlaFiles,
        val sharedPreferences: SharedPreferences
) {

    private var xmlFetch = false

    fun parseXml(inputStream: InputStream) {
        var text = ""
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(inputStream, null)
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagname = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> text = ""
                    XmlPullParser.TEXT -> text = parser.text
                    XmlPullParser.END_TAG ->
                        if (tagname.equals("pref_custom_apps_enabled", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_custom_apps_enabled",text.toBoolean())
                                apply()
                            }
                        } else if (tagname.equals("pref_apps", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putString("pref_apps", text)
                                apply()
                            }
                        } else if (tagname.equals("pref_custom_filesystem_enabled", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_custom_filesystem_enabled",text.toBoolean())
                                apply()
                            }
                        } else if (tagname.equals("pref_filesystem", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putString("pref_filesystem", text)
                                apply()
                            }
                        } else if (tagname.equals("pref_custom_hostname_enabled", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_custom_hostname_enabled",text.toBoolean())
                                apply()
                            }
                        } else if (tagname.equals("pref_hostname", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putString("pref_hostname", text)
                                apply()
                            }
                        } else if (tagname.equals("pref_custom_dns_enabled", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_custom_dns_enabled",text.toBoolean())
                                apply()
                            }
                        } else if (tagname.equals("pref_dns", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putString("pref_dns",text)
                                apply()
                            }
                        } else if (tagname.equals("pref_custom_scaling_enabled", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_custom_scaling_enabled",text.toBoolean())
                                apply()
                            }
                        } else if (tagname.equals("pref_scaling", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putString("pref_scaling",text)
                                apply()
                            }
                        } else if (tagname.equals("pref_hide_settings", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_hide_settings",text.toBoolean())
                                apply()
                            }
                        } else if (tagname.startsWith("pref_rs232", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                if (tagname.endsWith("_enabled", true)) {
                                    putBoolean(tagname,text.toBoolean())
                                } else {
                                    putString(tagname,text)
                                }
                                apply()
                            }
                        } else -> { }
                }
                eventType = parser.next()
            }

        } catch (e: XmlPullParserException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun fetchXML() {
        //If you uncomment out the next two lines it will only check for the preferences file on the first run, which limits your ability to debug
        //if (xmlFetch == true)
            //return
        try {
            var xmlFile = File(ulaFiles.filesDir.absolutePath + "/preferences.xml")
            Log.d("PreferenceGetter","Checking if " + xmlFile.absolutePath + " exists")
            if (xmlFile.exists()) {
                Log.d("PreferenceGetter",xmlFile.absolutePath + " exists")
                parseXml(FileInputStream(xmlFile))
                Log.d("PreferenceGetter","XML parsing complete")
                xmlFetch = true
                return
            }
        } catch (e: Exception){
            Log.e("PreferenceGetter","XML file parsing failed with this exception: " + e.message)
        }
        try {
            var xmlFile = File(ulaFiles.emulatedScopedDir.absolutePath + "/preferences.xml")
            Log.d("PreferenceGetter","Checking if " + xmlFile.absolutePath + " exists")
            if (xmlFile.exists()) {
                Log.d("PreferenceGetter",xmlFile.absolutePath + " exists")
                parseXml(FileInputStream(xmlFile))
                Log.d("PreferenceGetter","XML parsing complete")
                xmlFetch = true
                return
            }
        } catch (e: Exception){
            Log.e("PreferenceGetter","XML file parsing failed with this exception: " + e.message)
        }
        try {
            var xmlFile = File(Environment.getExternalStorageDirectory(),"/Relag/preferences.xml")
            Log.d("PreferenceGetter","Checking if " + xmlFile.absolutePath + " exists")
            if (xmlFile.exists()) {
                Log.d("PreferenceGetter",xmlFile.absolutePath + " exists")
                parseXml(FileInputStream(xmlFile))
                Log.d("PreferenceGetter","XML parsing complete")
                xmlFetch = true
                return
            }
        } catch (e: Exception){
            Log.e("PreferenceGetter","XML file parsing failed with this exception: " + e.message)
        }
        try {
            var xmlFile = File("/sdcard/Relag/preferences.xml")
            Log.d("PreferenceGetter","Checking if " + xmlFile.absolutePath + " exists")
            if (xmlFile.exists()) {
                Log.d("PreferenceGetter",xmlFile.absolutePath + " exists")
                parseXml(FileInputStream(xmlFile))
                Log.d("PreferenceGetter","XML parsing complete")
                xmlFetch = true
                return
            }
        } catch (e: Exception){
            Log.e("PreferenceGetter","XML file parsing failed with this exception: " + e.message)
        }
        try {
            if (ulaFiles.documentsDir != null) {
                var xmlFile = File(ulaFiles.documentsDir.absolutePath + "/Relag/preferences.xml")
                Log.d("PreferenceGetter","Checking if " + xmlFile.absolutePath + " exists")
                if (xmlFile.exists()) {
                    Log.d("PreferenceGetter",xmlFile.absolutePath + " exists")
                    parseXml(FileInputStream(xmlFile))
                    Log.d("PreferenceGetter","XML parsing complete")
                    xmlFetch = true
                    return
                }
            }
        } catch (e: Exception){
            Log.e("PreferenceGetter","XML file parsing failed with this exception: " + e.message)
        }
        xmlFetch = true
    }

}

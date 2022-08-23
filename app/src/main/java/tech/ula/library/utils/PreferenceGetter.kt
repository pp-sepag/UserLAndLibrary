package tech.ula.library.utils

import android.content.SharedPreferences
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
                        } else if (tagname.equals("pref_hide_settings", ignoreCase = true)) {
                            with(sharedPreferences.edit()) {
                                putBoolean("pref_hide_settings",text.toBoolean())
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
        try {
            if (xmlFetch == true)
                return
            var xmlFile = File(ulaFiles.filesDir.absolutePath + "/preferences.xml")
            if (xmlFile.exists()) {
                parseXml(FileInputStream(xmlFile))
                xmlFetch = true
                return
            }
            xmlFile = File(ulaFiles.emulatedScopedDir.absolutePath + "/preferences.xml")
            if (xmlFile.exists()) {
                parseXml(FileInputStream(xmlFile))
                xmlFetch = true
                return
            }
            if (ulaFiles.documentsDir != null) {
                xmlFile = File(ulaFiles.documentsDir.absolutePath + "/Relag/preferences.xml")
                if (xmlFile.exists()) {
                    parseXml(FileInputStream(xmlFile))
                    xmlFetch = true
                    return
                }
            }
        } catch (e: Exception){
            xmlFetch = true
            return
        }
        xmlFetch = true
    }

}

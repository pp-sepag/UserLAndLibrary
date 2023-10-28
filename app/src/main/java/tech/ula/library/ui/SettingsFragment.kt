package tech.ula.library.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.preference.*
import tech.ula.customlibrary.BuildConfig
import tech.ula.library.utils.ProotDebugLogger
import tech.ula.library.utils.UlaFiles
import tech.ula.library.utils.defaultSharedPreferences
import tech.ula.library.R

class SettingsFragment : PreferenceFragmentCompat() {

    private val prootDebugLogger by lazy {
        val ulaFiles = UlaFiles(activity!!, activity!!.applicationInfo.nativeLibraryDir)
        ProotDebugLogger(activity!!.defaultSharedPreferences, ulaFiles)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)

        val deleteFilePreference: Preference = findPreference("pref_proot_delete_debug_file")!!
        deleteFilePreference.setOnPreferenceClickListener {
            prootDebugLogger.deleteLogs()
            true
        }

        val clearAutoStartPreference: Preference = findPreference("pref_clear_auto_start")!!
        clearAutoStartPreference.setOnPreferenceClickListener {
            val prefs = activity!!.getSharedPreferences("apps", Context.MODE_PRIVATE)
            with(prefs.edit()) {
                remove("AutoApp")
                apply()
                true
            }
        }

        val clearConnectTypePreference: Preference = findPreference("pref_clear_connect_type")!!
        clearConnectTypePreference.setOnPreferenceClickListener {
            val prefs = activity!!.getSharedPreferences("apps", Context.MODE_PRIVATE)
            with(prefs.edit()) {
                putBoolean("askConnectType", true)
                apply()
                true
            }
        }

        val hideSessionsFilesystemsPreference: CheckBoxPreference = findPreference("pref_hide_sessions_filesystems")!!
        hideSessionsFilesystemsPreference.setOnPreferenceChangeListener { preference, newValue ->
            if (newValue is Boolean) {
                val bottomNavView = activity!!.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    R.id.bottom_nav_view
                )
                if (newValue) {
                    bottomNavView.visibility = View.GONE
                } else {
                    bottomNavView.visibility = View.VISIBLE
                }
            }
            true
        }
        hidePrefs()
    }

    private fun hidePrefs() {
        if (BuildConfig.HIDE_USERLAND_PREFS) {
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_default_nav_location"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_clear_auto_start"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hide_sessions_filesystems"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hide_distributions"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_custom_hostname_enabled"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hostname"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_custom_apps_enabled"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_apps"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_custom_filesystem_enabled"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_filesystem"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hide_vnc_toolbar"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hide_vnc_extra_keys"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_default_vnc_input_mode"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hostname_from_http"))
            (findPreference("pref_app_category") as PreferenceGroup?)!!.removePreference(findPreference("pref_hostname_http_url"))
            (findPreference("pref_screen") as PreferenceScreen?)!!.removePreference(findPreference("pref_proot_category"))
        }
    }

    override fun setDivider(divider: Drawable?) {
        super.setDivider(ColorDrawable(Color.TRANSPARENT))
    }

    override fun setDividerHeight(height: Int) {
        super.setDividerHeight(0)
    }
}
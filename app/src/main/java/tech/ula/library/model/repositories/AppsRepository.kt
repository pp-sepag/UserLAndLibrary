package tech.ula.library.model.repositories

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.* // ktlint-disable no-wildcard-imports
import tech.ula.customlibrary.BuildConfig
import tech.ula.library.model.daos.AppsDao
import tech.ula.library.model.entities.App
import tech.ula.library.utils.BreadcrumbType
import tech.ula.library.utils.Logger
import tech.ula.library.utils.SentryLogger
import tech.ula.library.utils.UlaBreadcrumb
import tech.ula.library.model.remote.GithubAppsFetcher
import tech.ula.library.utils.*
import tech.ula.library.utils.preferences.AppsPreferences
import java.util.Locale

class AppsRepository(
    private val appsDao: AppsDao,
    private val remoteAppsSource: GithubAppsFetcher,
    private val appsPreferences: AppsPreferences,
    private val sharedPreferences: SharedPreferences,
    private val logger: Logger = SentryLogger()
) {
    private val className = "AppsRepository"

    private val refreshStatus = MutableLiveData<AppRefreshStatus>()

    fun getAllApps(): LiveData<List<App>> {
        return appsDao.getAllApps()
    }

    fun getActiveApps(): LiveData<List<App>> {
        return appsDao.getActiveApps()
    }

    fun getRefreshStatus(): LiveData<AppRefreshStatus> {
        return refreshStatus
    }

    suspend fun refreshData(scope: CoroutineScope) {
        val distributionsList = mutableSetOf<String>()
        refreshStatus.postValue(AppRefreshStatus(RefreshStatus.ACTIVE,""))
        val jobs = mutableListOf<Job>()

        var appsUrl = BuildConfig.DEFAULT_APPS_URL
        if (sharedPreferences.getBoolean("pref_custom_apps_enabled", BuildConfig.DEFAULT_CUSTOM_APPS_ENABLED))
            appsUrl = sharedPreferences.getString("pref_apps", BuildConfig.DEFAULT_APPS_URL)!!
        var flush = false
        if (sharedPreferences.contains("prev_pref_apps_repo")) {
            if (!appsUrl.equals(sharedPreferences.getString("prev_pref_apps_repo", BuildConfig.DEFAULT_APPS_URL))) {
                flush = true
            }
        }
        with(sharedPreferences.edit()) {
            putString("prev_pref_apps_repo", appsUrl)
            apply()
        }
        if (flush) {
            appsDao.deleteAllApps()
        }

        var failed = false
        var failMessage = "Not Found"

        try {
            remoteAppsSource.fetchAppsList().forEach { app ->
                jobs.add(scope.launch() {
                    if (app.category.toLowerCase(Locale.ENGLISH) == "distribution") distributionsList.add(app.name)
                    try {
                        remoteAppsSource.fetchAppIcon(app)
                        remoteAppsSource.fetchAppDescription(app)
                        remoteAppsSource.fetchAppScript(app)
                    } catch (err: Exception) {
                        logger.addExceptionBreadcrumb(err)
                        failed = true
                        failMessage = app.name
                    }
                    appsDao.insertApp(app) // Insert the db element last to force observer refresh
                })
            }
        } catch (err: Exception) {
            logger.addExceptionBreadcrumb(err)
            failed = true;
            failMessage = "App list"
        }

        jobs.joinAll()

        if (failed) {
            refreshStatus.postValue(AppRefreshStatus(RefreshStatus.FAILED,failMessage))
            val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.RuntimeError, failMessage)
            logger.addBreadcrumb(breadcrumb)
            logger.sendEvent("App Refresh Failed")
            return
        }

        refreshStatus.postValue(AppRefreshStatus(RefreshStatus.FINISHED,""))
        appsPreferences.setDistributionsList(distributionsList)
    }
}

enum class RefreshStatus {
    ACTIVE, FINISHED, FAILED, INACTIVE
}

class AppRefreshStatus(val refreshStatus: RefreshStatus, val message: String) {
} 

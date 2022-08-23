package tech.ula.library.model.remote

import android.content.res.AssetManager
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.ula.customlibrary.BuildConfig
import tech.ula.library.model.entities.App
import tech.ula.library.utils.Logger
import tech.ula.library.utils.SentryLogger
import tech.ula.library.utils.* // ktlint-disable no-wildcard-imports
import java.io.File
import java.io.IOException
import java.util.Locale

class GithubAppsFetcher(
    private val filesDirPath: String,
    private val assets: AssetManager,
    private val sharedPreferences: SharedPreferences,
    private val httpStream: HttpStream = HttpStream(),
    private val logger: Logger = SentryLogger()
) {

    // Allows destructing of the list of application elements
    private operator fun <T> List<T>.component6() = get(5)
    private operator fun <T> List<T>.component7() = get(6)

    private fun baseUrl(): String {
        var appsUrl = BuildConfig.DEFAULT_APPS_URL
        if (sharedPreferences.getBoolean("pref_custom_apps_enabled", BuildConfig.DEFAULT_CUSTOM_APPS_ENABLED))
            appsUrl = sharedPreferences.getString("pref_apps", BuildConfig.DEFAULT_APPS_URL)!!
        return appsUrl
    }

    @Throws(IOException::class)
    suspend fun fetchAppsList(): List<App> = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = "${baseUrl()}/apps.txt"
            val numLinesToSkip = 1 // Skip first line which defines schema
            var contents: List<String>
            if (BuildConfig.APPS_IN_ASSETS) {
                val tempContents = assets.open("apps/apps.txt").bufferedReader().use { it.readText() }
                contents = tempContents.trim().lines()
            } else {
                contents = httpStream.toLines(url)
            }
            contents.drop(numLinesToSkip).map { line ->
                // Destructure app fields
                val (
                        name,
                        category,
                        filesystemRequired,
                        supportsCli,
                        supportsGui,
                        isPaidApp,
                        version
                ) = line.toLowerCase(Locale.ENGLISH).split(", ")
                // Construct app
                App(
                        name,
                        category,
                        filesystemRequired,
                        supportsCli.toBoolean(),
                        supportsGui.toBoolean(),
                        isPaidApp.toBoolean(),
                        version.toLong()
                )
            }
        } catch (err: Exception) {
            val exception = IOException("Error getting apps list")
            logger.addExceptionBreadcrumb(exception)
            throw exception
        }
    }

    @Throws(IOException::class)
    suspend fun fetchAppIcon(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/${app.name}.png"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        try {
            if (BuildConfig.APPS_IN_ASSETS) {
                file.parentFile!!.mkdirs()
                assets.open("apps/$directoryAndFilename").use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output, 1024)
                    }
                }
            } else {
                val url = "${baseUrl()}/$directoryAndFilename"
                httpStream.toFile(url, file)
            }
        } catch (err: Exception) {
            throw err
        }
    }

    @Throws(IOException::class)
    suspend fun fetchAppDescription(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/${app.name}.txt"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        if (BuildConfig.APPS_IN_ASSETS) {
            file.parentFile!!.mkdirs()
            assets.open("apps/$directoryAndFilename").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, 1024)
                }
            }
        } else {
            val url = "${baseUrl()}/$directoryAndFilename"
            httpStream.toTextFile(url, file)
        }
    }

    @Throws(IOException::class)
    suspend fun fetchAppScript(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/${app.name}.sh"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        if (BuildConfig.APPS_IN_ASSETS) {
            file.parentFile!!.mkdirs()
            assets.open("apps/$directoryAndFilename").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, 1024)
                }
            }
        } else {
            val url = "${baseUrl()}/$directoryAndFilename"
            httpStream.toTextFile(url, file)
        }
    }
}

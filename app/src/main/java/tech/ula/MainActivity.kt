package tech.ula

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.speech.SpeechRecognizer.isRecognitionAvailable
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.NavigationUI.setupWithNavController
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.ula.model.entities.App
import tech.ula.model.entities.ServiceType
import tech.ula.model.entities.Session
import tech.ula.model.entities.toServiceType
import tech.ula.model.remote.GithubApiClient
import tech.ula.model.repositories.AssetRepository
import tech.ula.model.repositories.DownloadMetadata
import tech.ula.model.repositories.UlaDatabase
import tech.ula.model.state.*
import tech.ula.ui.AppsListFragment
import tech.ula.ui.FilesystemListFragment
import tech.ula.ui.SessionListFragment
import tech.ula.utils.*
import tech.ula.utils.preferences.*
import tech.ula.viewmodel.*
import java.lang.reflect.Method
import java.net.NetworkInterface
import java.util.*

class MainActivity : AppCompatActivity(), SessionListFragment.SessionSelection, AppsListFragment.AppSelection, FilesystemListFragment.FilesystemListProgress {

    val className = "MainActivity"

    private var progressBarIsVisible = false
    private var currentFragmentDisplaysProgressDialog = false
    private var autoStarted = false

    private var customDialog: AlertDialog? = null

    private val logger = SentryLogger()
    private val ulaFiles by lazy { UlaFiles(this, this.applicationInfo.nativeLibraryDir) }
    private val busyboxExecutor by lazy {
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, ulaFiles)
        BusyboxExecutor(ulaFiles, prootDebugLogger)
    }

    private val navController: NavController by lazy {
        findNavController(R.id.nav_host_fragment)
    }

    private val notificationManager by lazy {
        NotificationConstructor(this)
    }

    private val userFeedbackPrompter by lazy {
        UserFeedbackPrompter(this)
    }

    private val optInPrompter by lazy {
        CollectionOptInPrompter(this)
    }

    val billingManager by lazy {
        BillingManager(
            this,
            contributionPrompter.onEntitledSubPurchases,
            contributionPrompter.onEntitledInAppPurchases,
            contributionPrompter.onPurchase,
            contributionPrompter.onFlowComplete,
            contributionPrompter.onSubscriptionSupportedChecked
        )
    }

    private val contributionPrompter by lazy {
        ContributionPrompter(this)
    }

    private val downloadBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == -1L) return
            else viewModel.submitCompletedDownloadId(id)
        }
    }

    private val serverServiceBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("type")?.let { intentType ->
                val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.ReceivedIntent, intentType)
                logger.addBreadcrumb(breadcrumb)
                when (intentType) {
                    "sessionActivated" -> handleSessionHasBeenActivated()
                    "dialog" -> {
                        val type = intent.getStringExtra("dialogType") ?: ""
                        showDialog(type)
                    }
                }
            }
        }
    }

    private val stateObserver = Observer<State> {
        val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.ObservedState, "$it")
        logger.addBreadcrumb(breadcrumb)
        it?.let { state ->
            handleStateUpdate(state)
        }
    }

    private val viewModel: MainActivityViewModel by lazy {
        val ulaDatabase = UlaDatabase.getInstance(this)

        val assetPreferences = AssetPreferences(this)
        val githubApiClient = GithubApiClient(ulaFiles)
        val assetRepository = AssetRepository(
            filesDir.path,
            ulaFiles,
            assetPreferences,
            defaultSharedPreferences,
            githubApiClient
        )

        val filesystemManager = FilesystemManager(ulaFiles, busyboxExecutor)
        val storageCalculator = StorageCalculator(StatFs(filesDir.path))

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadManagerWrapper = DownloadManagerWrapper(downloadManager)
        val assetDownloader = AssetDownloader(assetPreferences, downloadManagerWrapper, ulaFiles)

        val appsStartupFsm = AppsStartupFsm(ulaDatabase, filesystemManager, ulaFiles)
        val sessionStartupFsm = SessionStartupFsm(
            ulaDatabase,
            assetRepository,
            filesystemManager,
            assetDownloader,
            storageCalculator
        )
        ViewModelProviders.of(this, MainActivityViewModelFactory(appsStartupFsm, sessionStartupFsm))
                .get(MainActivityViewModel::class.java)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.type.equals("settings"))
            navController.navigate(R.id.settings_fragment)
        else {
            if (intent != null) {
                checkForAppIntent(intent)
            }
            autoStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        notificationManager.createServiceNotificationChannel() // Android O requirement

        setNavStartDestination()
        setProgressDialogNavListeners()

        setupWithNavController(bottom_nav_view, navController)

        viewModel.getState().observe(this, stateObserver)

        if (intent?.type.equals("settings"))
            navController.navigate(R.id.settings_fragment)
        else {
            checkForAppIntent(intent)
            autoStart()
        }
    }

    private fun checkForAppIntent(intent: Intent) {
        val prefs = getSharedPreferences("apps", Context.MODE_PRIVATE)
        if (intent.extras != null) {
            if (intent.extras!!.getSerializable("env") != null) {
                val env = intent.extras!!.getSerializable("env") as HashMap<String, String>
                with(defaultSharedPreferences.edit()) {
                    val gson = Gson()
                    val json = gson.toJson(env)
                    putString("env", json)
                    apply()
                }
            }
            if (intent.extras!!.getParcelable<App>("app") != null) {
                val app = intent.extras!!.getParcelable<App>("app")!!
                with(prefs.edit()) {
                    val gson = Gson()
                    val json= gson.toJson(app)
                    putString("AutoApp", json)
                    apply()
                }
            }
        }
    }

    private fun setNavStartDestination() {
        val userPreference = defaultSharedPreferences.getString("pref_default_nav_location", "Apps")
        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.startDestination = when (userPreference) {
            getString(R.string.sessions) -> R.id.session_list_fragment
            else -> R.id.app_list_fragment
        }
        navController.graph = graph
        val bottomNavView = this.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottom_nav_view
        )
        if (defaultSharedPreferences.getBoolean(
                "pref_hide_sessions_filesystems",
                BuildConfig.DEFAULT_HIDE_SESSIONS_FILESYSTEMS
            )){
            bottomNavView.visibility = View.GONE
        } else {
            bottomNavView.visibility = View.VISIBLE
        }
    }

    private fun setProgressDialogNavListeners() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentFragmentDisplaysProgressDialog =
                    destination.label == getString(R.string.sessions) ||
                            destination.label == getString(R.string.apps) ||
                            destination.label == getString(R.string.filesystems)
            if (!currentFragmentDisplaysProgressDialog) killProgressBar()
            else if (progressBarIsVisible) displayProgressBar()
        }
    }

    private fun handleQWarning() {
        val handler = QWarningHandler(
            this.getSharedPreferences(
                QWarningHandler.prefsString,
                Context.MODE_PRIVATE
            ), ulaFiles
        )
        if (handler.messageShouldBeDisplayed()) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.q_warning_title)
                    .setMessage(R.string.q_warning_message)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setNeutralButton(R.string.wiki) { dialog, _ ->
                        dialog.dismiss()
                        sendWikiIntent()
                    }
                    .create().show()
            handler.messageHasBeenDisplayed()
        }
    }

    override fun onSupportNavigateUp() = navController.navigateUp()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_options, menu)
        return true
    }

    private fun getMacAddr(): String? {
        try {
            val all: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in all) {
                if (!nif.getName().equals("wlan0", true)) continue
                val macBytes: ByteArray = nif.getHardwareAddress() ?: return ""
                val res1 = StringBuilder()
                for (b in macBytes) {
                    res1.append(String.format("%02X:", b))
                }
                if (res1.length > 0) {
                    res1.deleteCharAt(res1.length - 1)
                }
                return res1.toString().replace(":", "")
            }
        } catch (ex: java.lang.Exception) {
        }
        return UUID.randomUUID().toString()
    }

    private fun getCameraInfo() {
        val recognitionServiceAvailable = isRecognitionAvailable(this)
        with(defaultSharedPreferences.edit()) {
            putInt(
                "camera_supported",
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) 1 else 0
            )
            putInt(
                "microphone_supported",
                if (recognitionServiceAvailable && packageManager.hasSystemFeature(
                        PackageManager.FEATURE_MICROPHONE
                    )
                ) 1 else 0
            )
            apply()
        }
    }

    private fun getNetInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val re1 = "^\\d+(\\.\\d+){3}$".toRegex()
            val re2 = "^[0-9a-f]+(:[0-9a-f]*)+:[0-9a-f]+$".toRegex()
            val SystemProperties = Class.forName("android.os.SystemProperties")
            val method: Method = SystemProperties.getMethod(
                "get",
                *arrayOf<Class<*>>(String::class.java)
            )
            val props = arrayOf("net.dns1", "net.dns2", "dhcp.wlan0.domain", "net.hostname")
            for (i in props.indices) {
                val v = method.invoke(null, props[i]) as String
                if (i < 2) {
                    if (v != null && (v.matches(re1) || v.matches(re2))) {
                        with(defaultSharedPreferences.edit()) {
                            putString("current_dns${i}", v)
                            apply()
                        }
                    }
                } else if ((i == 2) && (v != null && !v.trim().isEmpty())) {
                    with(defaultSharedPreferences.edit()) {
                        putString("search_domains", v.replace(",", " "))
                        apply()
                    }
                } else if (i == 3) {
                    if (!defaultSharedPreferences.contains("unique_id")) {
                        with(defaultSharedPreferences.edit()) {
                            if (v != null && !v.trim().isEmpty())
                                putString("unique_id", v)
                            else
                                putString("unique_id", "android-" + UUID.randomUUID().toString())
                            apply()
                        }
                    }
                }
            }
        } else {
            val connectivityManager = ContextCompat.getSystemService(
                this,
                ConnectivityManager::class.java
            )
            if (connectivityManager != null) {
                val currentNetwork = connectivityManager.getActiveNetwork()
                if (currentNetwork != null) {
                    val linkProperties = connectivityManager.getLinkProperties(currentNetwork)
                    if (linkProperties != null) {
                        val dnsServers = linkProperties.dnsServers
                        val searchDomains = linkProperties.domains
                        with(defaultSharedPreferences.edit()) {
                            if (dnsServers.size > 0)
                                putString("current_dns0", dnsServers[0].toString())
                            if (dnsServers.size > 1)
                                putString("current_dns1", dnsServers[1].toString())
                            if (searchDomains != null && !searchDomains.trim().isEmpty())
                                putString("search_domains", searchDomains.replace(",", " "))
                            apply()
                        }
                    }
                }
            }
            if (!defaultSharedPreferences.contains("unique_id")) {
                with(defaultSharedPreferences.edit()) {
                    putString("unique_id", "android-" + getMacAddr())
                    apply()
                }
            }
        }
    }

    private fun autoStart() {
        val prefs = getSharedPreferences("apps", Context.MODE_PRIVATE)
        val json = prefs.getString("AutoApp", " ")
        if (json != null)
            if (json.compareTo(" ") != 0) {
                val gson = Gson()
                val autoApp = gson.fromJson(json, App::class.java)
                autoStarted=true
                appHasBeenSelected(autoApp, true)
            }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                    serverServiceBroadcastReceiver,
                    IntentFilter(ServerService.SERVER_SERVICE_RESULT)
                )
        registerReceiver(
            downloadBroadcastReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.ASK_FOR_CONTRIBUTION) {
            billingManager.querySubPurchases()
            billingManager.queryInAppPurchases()
        }
        viewModel.handleOnResume()
    }

    override fun onDestroy() {
        if (BuildConfig.ASK_FOR_CONTRIBUTION) {
            billingManager.destroy()
        }
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.terms_and_conditions) {
            val intent = Intent(
                "android.intent.action.VIEW",
                Uri.parse("https://userland.tech/eula")
            )
            startActivity(intent)
        }
        if (item.itemId == R.id.option_wiki) {
            sendWikiIntent()
        }
        if (item.itemId == R.id.clear_support_files) {
            displayClearSupportFilesDialog()
        }
        return NavigationUI.onNavDestinationSelected(
            item,
            Navigation.findNavController(this, R.id.nav_host_fragment)
        ) ||
                super.onOptionsItemSelected(item)
    }

    private fun sendWikiIntent() {
        val intent = Intent(
            "android.intent.action.VIEW",
            Uri.parse("https://github.com/CypherpunkArmory/UserLAnd/wiki")
        )
        startActivity(intent)
    }

    override fun onStop() {
        super.onStop()

        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(serverServiceBroadcastReceiver)
        unregisterReceiver(downloadBroadcastReceiver)
    }

    override fun appHasBeenSelected(app: App, autoStart: Boolean) {
        getNetInfo()
        getCameraInfo()
        if (!PermissionHandler.permissionsAreGranted(this)) {
            PermissionHandler.showPermissionsNecessaryDialog(this)
            viewModel.waitForPermissions(appToContinue = app)
            return
        }
        viewModel.submitAppSelection(app, autoStart)
    }

    override fun sessionHasBeenSelected(session: Session) {
        getNetInfo()
        getCameraInfo()
        if (!PermissionHandler.permissionsAreGranted(this)) {
            PermissionHandler.showPermissionsNecessaryDialog(this)
            viewModel.waitForPermissions(sessionToContinue = session)
            return
        }
        viewModel.submitSessionSelection(session)
    }

    private fun handleStateUpdate(newState: State) {
        return when (newState) {
            is WaitingForInput -> {
                killProgressBar()
            }
            is CanOnlyStartSingleSession -> {
                showToast(R.string.single_session_supported)
                viewModel.handleUserInputCancelled()
            }
            is SessionCanBeStarted -> {
                prepareSessionForStart(newState.session)
            }
            is SessionCanBeRestarted -> {
                restartRunningSession(newState.session)
            }
            is IllegalState -> {
                handleIllegalState(newState)
            }
            is UserInputRequiredState -> {
                handleUserInputState(newState)
            }
            is ProgressBarUpdateState -> {
                handleProgressBarUpdateState(newState)
            }
        }
    }

    private fun prepareSessionForStart(session: Session) {
        val step = getString(R.string.progress_starting)
        val details = ""
        updateProgressBar(step, details)

        // TODO: Alert user when defaulting to VNC
        // TODO: Is this even possible?
        if (session.serviceType is ServiceType.Xsdl && Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            session.serviceType = ServiceType.Vnc
        }

        when (session.serviceType) {
            ServiceType.Xsdl -> {
                viewModel.lastSelectedSession = session
                sendXsdlIntentToSetDisplayNumberAndExpectResult()
            }
            ServiceType.Vnc -> {
                setVncResolution(session)
                startSession(session)
            }
            else -> startSession(session)
        }
    }

    private fun setVncResolution(session: Session) {
        val deviceDimensions = DeviceDimensions()
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val orientation = applicationContext.resources.configuration.orientation
        deviceDimensions.saveDeviceDimensions(
            windowManager,
            DisplayMetrics(),
            orientation,
            defaultSharedPreferences
        )
        session.geometry = deviceDimensions.getScreenResolution()
    }

    private fun startSession(session: Session) {
        val serviceIntent = Intent(this, ServerService::class.java)
                .putExtra("type", "start")
                .putExtra("session", session)
        startService(serviceIntent)
        if (autoStarted) {
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000)
        }
    }

    /*
    XSDL has a different flow than starting SSH/VNC session.  It sends an intent to XSDL with
        with a display value.  Then XSDL sends an intent to open UserLAnd signalling
        that it has an xserver listening.  We set the initial display number as an environment variable
        then start a twm process to connect to XSDL's xserver.
    */
    private fun sendXsdlIntentToSetDisplayNumberAndExpectResult() {
        try {
            val xsdlIntent = Intent(Intent.ACTION_MAIN, Uri.parse("x11://give.me.display:4721"))
            val setDisplayRequestCode = 1
            startActivityForResult(xsdlIntent, setDisplayRequestCode)
        } catch (e: Exception) {
            val appPackageName = "x.org.server"
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$appPackageName")
                    )
                )
            } catch (error: android.content.ActivityNotFoundException) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")
                    )
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2296) {
            if (Environment.isExternalStorageManager()) {
                with(defaultSharedPreferences.edit()) {
                    putBoolean("granted_manage_external_permission", true)
                    apply()
                }
            }
            viewModel.permissionsHaveBeenGranted()
        } else {
            data?.let {
                val session = viewModel.lastSelectedSession
                val result = data.getStringExtra("run") ?: ""
                if (session.serviceType == ServiceType.Xsdl && result.isNotEmpty()) {
                    startSession(session)
                }
            }
        }
    }

    private fun restartRunningSession(session: Session) {
        val serviceIntent = Intent(this, ServerService::class.java)
                .putExtra("type", "restartRunningSession")
                .putExtra("session", session)
        startService(serviceIntent)
    }

    private fun handleSessionHasBeenActivated() {
        viewModel.handleSessionHasBeenActivated()
        killProgressBar()
    }

    private fun showToast(resId: Int) {
        val content = getString(resId)
        Toast.makeText(this, content, Toast.LENGTH_LONG).show()
    }

    fun getRandPassword(n: Int): String
    {
        val characterSet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        val random = Random(System.nanoTime())
        val password = StringBuilder()

        for (i in 0 until n)
        {
            val rIndex = random.nextInt(characterSet.length)
            password.append(characterSet[rIndex])
        }

        return password.toString()
    }

    private fun handleUserInputState(state: UserInputRequiredState) {
        return when (state) {
            is LowStorageAcknowledgementRequired -> {
                displayLowStorageDialog()
            }
            is UserFeedbackCheckRequired -> {
                if (userFeedbackPrompter.viewShouldBeShown() && BuildConfig.ASK_FOR_FEEDBACK) {
                    getUserFeedback()
                } else {
                    viewModel.userFeedbackChecked()
                }
            }
            is UserContributionCheckRequired -> {
                if (contributionPrompter.viewShouldBeShown() && BuildConfig.ASK_FOR_CONTRIBUTION) {
                    getUserContribution()
                } else {
                    viewModel.userContributionChecked()
                }
            }
            is FilesystemCredentialsRequired -> {
                if (BuildConfig.USE_DEFAULT_CREDS) {
                    viewModel.submitFilesystemCredentials(
                        BuildConfig.DEFAULT_USERNAME,
                        BuildConfig.DEFAULT_SSH_PASSWORD,
                        if (BuildConfig.USE_RANDOM_VNC_PASSWORD) getRandPassword(8) else BuildConfig.DEFAULT_VNC_PASSWORD
                    )
                } else
                    getCredentials()
            }
            is AppServiceTypePreferenceRequired -> {
                if (BuildConfig.USE_DEFAULT_SERVICE_TYPE)
                    viewModel.submitAppServiceType(BuildConfig.DEFAULT_LAUNCH_TYPE.toServiceType())
                else
                    getServiceTypePreference()
            }
            is LargeDownloadRequired -> {
                if (wifiIsEnabled()) {
                    viewModel.startAssetDownloads(state.downloadRequirements)
                    return
                }
                displayNetworkChoicesDialog(state.downloadRequirements)
            }
            is ActiveSessionsMustBeDeactivated -> {
                displayGenericErrorDialog(
                    R.string.general_error_title,
                    R.string.deactivate_sessions
                )
            }
        }
    }

    private fun handleIllegalState(state: IllegalState) {
        val stateDescription = IllegalStateHandler.getLocalizationData(state).getString(this)
        val displayMessage = getString(R.string.illegal_state_github_message, stateDescription)

        AlertDialog.Builder(this)
                .setMessage(displayMessage)
                .setTitle(R.string.illegal_state_title)
                .setPositiveButton(R.string.button_ok) { dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    // TODO sealed classes?
    private fun showDialog(dialogType: String) {
        when (dialogType) {
            "unhandledSessionServiceType" -> {
                displayGenericErrorDialog(
                    R.string.general_error_title,
                    R.string.illegal_state_unhandled_session_service_type
                )
            }
            "playStoreMissingForClient" ->
                displayGenericErrorDialog(
                    R.string.alert_need_client_app_title,
                    R.string.alert_need_client_app_message
                )
        }
    }

    private fun displayClearSupportFilesDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.alert_clear_support_files_message)
                .setTitle(R.string.alert_clear_support_files_title)
                .setPositiveButton(R.string.alert_clear_support_files_clear_button) { dialog, _ ->
                    handleClearSupportFiles()
                    dialog.dismiss()
                }
                .setNeutralButton(R.string.button_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    private fun handleClearSupportFiles() {
        val appsPreferences = AppsPreferences(this)
        val assetDirectoryNames = appsPreferences.getDistributionsList().plus("support")
        val assetFileClearer = AssetFileClearer(ulaFiles, assetDirectoryNames, busyboxExecutor)
        CoroutineScope(Dispatchers.Main).launch { viewModel.handleClearSupportFiles(assetFileClearer) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (PermissionHandler.permissionsWereGranted(requestCode, grantResults)) {
            if (!PermissionHandler.manageExternalPermissionsHaveBeenRequested(this))
                PermissionHandler.showPermissionsNecessaryDialog(this)
            else
                viewModel.permissionsHaveBeenGranted()
        } else {
            PermissionHandler.showPermissionsNecessaryDialog(this)
        }
    }

    private fun handleProgressBarUpdateState(state: ProgressBarUpdateState) {
        return when (state) {
            is StartingSetup -> {
                val step = getString(R.string.progress_start_step)
                updateProgressBar(step, "")
            }
            is FetchingAssetLists -> {
                val step = getString(R.string.progress_fetching_asset_lists)
                updateProgressBar(step, "")
            }
            is CheckingForAssetsUpdates -> {
                val step = getString(R.string.progress_checking_for_required_updates)
                updateProgressBar(step, "")
            }
            is DownloadProgress -> {
                val step = getString(R.string.progress_downloading)
                val details = getString(
                    R.string.progress_downloading_out_of,
                    state.numComplete,
                    state.numTotal
                )
                updateProgressBar(step, details)
            }
            is CopyingDownloads -> {
                val step = getString(R.string.progress_copying_downloads)
                updateProgressBar(step, "")
            }
            is VerifyingFilesystem -> {
                val step = getString(R.string.progress_verifying_assets)
                updateProgressBar(step, "")
            }
            is VerifyingAvailableStorage -> {
                val step = getString(R.string.progress_verifying_sufficient_storage)
                updateProgressBar(step, "")
            }
            is FilesystemExtractionStep -> {
                val step = getString(R.string.progress_setting_up_filesystem)
                val details = getString(
                    R.string.progress_extraction_details,
                    state.extractionTarget
                )
                updateProgressBar(step, details)
            }
            is ClearingSupportFiles -> {
                val step = getString(R.string.progress_clearing_support_files)
                updateProgressBar(step, "")
            }
            is ProgressBarOperationComplete -> {
                killProgressBar()
            }
        }
    }

    override fun updateFilesystemExportProgress(details: String) {
        val step = getString(R.string.progress_exporting_filesystem)
        updateProgressBar(step, details)
    }

    override fun updateFilesystemDeleteProgress() {
        val step = getString(R.string.progress_deleting_filesystem)
        updateProgressBar(step, "")
    }

    override fun stopProgressFromFilesystemList() {
        killProgressBar()
    }

    private fun displayProgressBar() {
        if (!currentFragmentDisplaysProgressDialog) return

        if (!progressBarIsVisible) {
            val inAnimation = AlphaAnimation(0f, 1f)
            inAnimation.duration = 200
            layout_progress.animation = inAnimation

            layout_progress.visibility = View.VISIBLE
            layout_progress.isFocusable = true
            layout_progress.isClickable = true
            progressBarIsVisible = true
        }
    }

    private fun updateProgressBar(step: String, details: String) {
        displayProgressBar()

        text_session_list_progress_step.text = step
        text_session_list_progress_details.text = details
    }

    private fun killProgressBar() {
        val outAnimation = AlphaAnimation(1f, 0f)
        outAnimation.duration = 200
        layout_progress.animation = outAnimation
        layout_progress.visibility = View.GONE
        layout_progress.isFocusable = false
        layout_progress.isClickable = false
        progressBarIsVisible = false
    }

    private fun wifiIsEnabled(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
        }
        return false
    }

    private fun displayNetworkChoicesDialog(downloadsToContinue: List<DownloadMetadata>) {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.alert_wifi_disabled_message)
                .setTitle(R.string.alert_wifi_disabled_title)
                .setPositiveButton(R.string.alert_wifi_disabled_continue_button) { dialog, _ ->
                    dialog.dismiss()
                    viewModel.startAssetDownloads(downloadsToContinue)
                }
                .setNegativeButton(R.string.alert_wifi_disabled_turn_on_wifi_button) { dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(WifiManager.ACTION_PICK_WIFI_NETWORK))
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .setNeutralButton(R.string.alert_wifi_disabled_cancel_button) { dialog, _ ->
                    dialog.dismiss()
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .setOnCancelListener {
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .create()
                .show()
    }

    private fun getUserFeedback() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = this.layoutInflater.inflate(R.layout.dia_place_holder, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        customDialog = dialog.create()

        customDialog!!.setOnCancelListener {
            viewModel.userFeedbackChecked()
        }
        customDialog!!.show()

        userFeedbackPrompter.showView(customDialog!!.findViewById(R.id.layout_user_prompt_insert))
    }

    fun userHasCompletedFeedback() {
        customDialog!!.dismiss()
        viewModel.userFeedbackChecked()
    }

    private fun getUserContribution() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = this.layoutInflater.inflate(R.layout.dia_place_holder, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        customDialog = dialog.create()

        customDialog!!.setOnCancelListener {
            viewModel.userContributionChecked()
        }
        customDialog!!.show()

        contributionPrompter.showView(customDialog!!.findViewById(R.id.layout_user_prompt_insert))
    }

    fun userHasCompletedContribution() {
        customDialog!!.dismiss()
        viewModel.userContributionChecked()
    }

    private fun getCredentials() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = this.layoutInflater.inflate(R.layout.dia_app_credentials, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        customDialog = dialog.create()

        customDialog!!.setOnShowListener {
            customDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = customDialog!!.find<TextInputEditText>(R.id.text_input_username).text.toString()
                val password = customDialog!!.find<TextInputEditText>(R.id.text_input_password).text.toString()
                val vncPassword = customDialog!!.find<TextInputEditText>(R.id.text_input_vnc_password).text.toString()

                if (validateCredentials(username, password, vncPassword)) {
                    customDialog!!.dismiss()
                    viewModel.submitFilesystemCredentials(username, password, vncPassword)
                }
            }
        }
        customDialog!!.setOnCancelListener {
            viewModel.handleUserInputCancelled()
        }
        customDialog!!.show()
    }

    private fun displayLowStorageDialog() {
        displayGenericErrorDialog(
            R.string.alert_storage_low_title,
            R.string.alert_storage_low_message
        ) {
            viewModel.lowAvailableStorageAcknowledged()
        }
    }

    // TODO refactor the names here
    // TODO could this dialog share a layout with the apps details page somehow?
    private fun getServiceTypePreference() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dia_app_select_client, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener {
            val sshTypePreference = customDialog.find<RadioButton>(R.id.ssh_radio_button)
            val vncTypePreference = customDialog.find<RadioButton>(R.id.vnc_radio_button)
            val xsdlTypePreference = customDialog.find<RadioButton>(R.id.xsdl_radio_button)

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
                xsdlTypePreference.isEnabled = false
                xsdlTypePreference.alpha = 0.5f

                val xsdlSupportedText = customDialog.findViewById<TextView>(R.id.text_xsdl_version_supported_description)
                xsdlSupportedText.visibility = View.VISIBLE
            }

            if (!viewModel.lastSelectedApp.supportsCli) {
                sshTypePreference.isEnabled = false
                sshTypePreference.alpha = 0.5f
            }

            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                customDialog.dismiss()
                val selectedType = when {
                    sshTypePreference.isChecked -> ServiceType.Ssh
                    vncTypePreference.isChecked -> ServiceType.Vnc
                    xsdlTypePreference.isChecked -> ServiceType.Xsdl
                    else -> ServiceType.Unselected
                }
                viewModel.submitAppServiceType(selectedType)
            }
        }
        customDialog.setOnCancelListener {
            viewModel.handleUserInputCancelled()
        }

        customDialog.show()
    }

    private fun validateCredentials(username: String, password: String, vncPassword: String): Boolean {
        val blacklistedUsernames = this.resources.getStringArray(R.array.blacklisted_usernames)
        val validator = CredentialValidator()

        val usernameCredentials = validator.validateUsername(username, blacklistedUsernames)
        val passwordCredentials = validator.validatePassword(password)
        val vncPasswordCredentials = validator.validateVncPassword(vncPassword)

        return when {
            !usernameCredentials.credentialIsValid -> {
                Toast.makeText(this, usernameCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            !passwordCredentials.credentialIsValid -> {
                Toast.makeText(this, passwordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            !vncPasswordCredentials.credentialIsValid -> {
                Toast.makeText(this, vncPasswordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            else -> true
        }
    }

}
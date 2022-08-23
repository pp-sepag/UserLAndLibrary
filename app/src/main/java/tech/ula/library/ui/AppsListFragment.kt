package tech.ula.library.ui

import android.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.view.MenuInflater
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.frag_app_list.* // ktlint-disable no-wildcard-imports
import tech.ula.library.MainActivity
import tech.ula.library.R
import tech.ula.library.ServerService
import tech.ula.library.model.entities.App
import tech.ula.library.model.remote.GithubAppsFetcher
import tech.ula.library.model.repositories.AppRefreshStatus
import tech.ula.library.model.repositories.AppsRepository
import tech.ula.library.model.repositories.RefreshStatus
import tech.ula.library.model.repositories.UlaDatabase
import tech.ula.library.utils.* // ktlint-disable no-wildcard-imports
import tech.ula.library.utils.preferences.AppsPreferences
import tech.ula.library.viewmodel.AppsListViewModel
import tech.ula.library.viewmodel.AppsListViewModelFactory

class AppsListFragment : Fragment(), AppsListAdapter.AppsClickHandler {

    interface AppSelection {
        fun appHasBeenSelected(app: App, autoStart: Boolean)
    }

    private val doOnAppSelection: AppSelection by lazy {
        activityContext
    }

    private lateinit var activityContext: MainActivity

    private val appsAdapter by lazy {
        AppsListAdapter(activityContext, this)
    }

    private var refreshStatus = RefreshStatus.INACTIVE

    private val appsPreferences by lazy {
        AppsPreferences(activityContext)
    }

    private val viewModel: AppsListViewModel by lazy {
        val ulaDatabase = UlaDatabase.getInstance(activityContext)
        val appsDao = ulaDatabase.appsDao()

        val githubFetcher = GithubAppsFetcher("${activityContext.filesDir}", activityContext.assets, activityContext.defaultSharedPreferences)

        val appsRepository = AppsRepository(appsDao, githubFetcher, appsPreferences, activityContext.defaultSharedPreferences)
        ViewModelProviders.of(this, AppsListViewModelFactory(appsRepository))
                .get(AppsListViewModel::class.java)
    }

    private val appsObserver = Observer<List<App>> {
        it?.let { list ->
            appsAdapter.updateApps(list)
            list_apps.scrollToPosition(0)
            if (list.isEmpty() || userlandIsNewVersion()) {
                doRefresh()
            }
        }
    }

    private val activeAppsObserver = Observer<List<App>> {
        it?.let { list ->
            appsAdapter.updateActiveApps(list)
        }
    }

    private val refreshStatusObserver = Observer<AppRefreshStatus> {
        it?.let { newStatus ->
            refreshStatus = newStatus.refreshStatus
            swipe_refresh.isRefreshing = refreshStatus == RefreshStatus.ACTIVE

            if (refreshStatus == RefreshStatus.FAILED) showRefreshUnavailableDialog(newStatus.message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_refresh, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when {
            item.itemId == R.id.menu_item_refresh -> {
                swipe_refresh.isRefreshing = true
                doRefresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onClick(app: App) {
        doOnAppSelection.appHasBeenSelected(app, false)
    }

    override fun createContextMenu(menu: Menu) {
        activityContext.menuInflater.inflate(R.menu.context_menu_apps, menu)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_item_app_details -> showAppDetails(appsAdapter.contextMenuItem)
            R.id.menu_item_stop_app -> stopAppSession(appsAdapter.contextMenuItem)
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_app_list, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activityContext = activity!! as MainActivity
        viewModel.getAppsList().observe(viewLifecycleOwner, appsObserver)
        viewModel.getActiveApps().observe(viewLifecycleOwner, activeAppsObserver)
        viewModel.getRefreshStatus().observe(viewLifecycleOwner, refreshStatusObserver)

        registerForContextMenu(list_apps)
        list_apps.layoutManager = LinearLayoutManager(list_apps.context)
        list_apps.adapter = appsAdapter

        swipe_refresh.setOnRefreshListener { doRefresh() }
        swipe_refresh.setColorSchemeResources(
                R.color.holo_blue_light,
                R.color.holo_green_light,
                R.color.holo_orange_light,
                R.color.holo_red_light)
    }

    private fun doRefresh() {
        viewModel.refreshAppsList()
        setLatestUpdateUserlandVersion()
    }

    private fun showAppDetails(app: App): Boolean {
        val bundle = bundleOf("app" to app)
        this.findNavController().navigate(R.id.action_app_list_to_app_details, bundle)
        return true
    }

    private fun stopAppSession(app: App): Boolean {
        val serviceIntent = Intent(activityContext, ServerService::class.java)
                .putExtra("type", "stopApp")
                .putExtra("app", app)
        activityContext.startService(serviceIntent)
        return true
    }

    private fun showRefreshUnavailableDialog(message: String) {
        AlertDialog.Builder(activityContext)
                .setMessage(getString(R.string.alert_network_required_for_refresh) + "\n" + getString(R.string.alert_unreachable_app) + " " + message)
                .setTitle(R.string.general_error_title)
                .setPositiveButton(R.string.button_ok) {
                    dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    private fun userlandIsNewVersion(): Boolean {
        val version = getUserlandVersion()
        val lastUpdatedVersion = activityContext.defaultSharedPreferences.getString("lastAppsUpdate", "")
        return version != lastUpdatedVersion
    }

    private fun setLatestUpdateUserlandVersion() {
        val version = getUserlandVersion()
        with(activityContext.defaultSharedPreferences.edit()) {
            putString("lastAppsUpdate", version)
            apply()
        }
    }

    private fun getUserlandVersion(): String {
        val info = activityContext.packageManager.getPackageInfo(activityContext.packageName, 0)
        return info.versionName
    }
}

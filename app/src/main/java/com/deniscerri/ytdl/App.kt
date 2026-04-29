package com.deniscerri.ytdl

import android.app.Application
import android.os.Looper
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.models.ExecuteException
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.ThemeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val sharedPreferences =  PreferenceManager.getDefaultSharedPreferences(this@App)
        setDefaultValues()
        applicationScope = CoroutineScope(SupervisorJob())
        applicationScope.launch((Dispatchers.IO)) {
            try {
                createNotificationChannels()
                initLibraries()

                val appVer = sharedPreferences.getString("version", "")!!
                if(appVer.isEmpty() || appVer != BuildConfig.VERSION_NAME){
                    sharedPreferences.edit(commit = true){
                        putString("version", BuildConfig.VERSION_NAME)
                    }
                }
            }catch (e: Exception){
                Looper.prepare().runCatching {
                    Toast.makeText(this@App, e.message, Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
        ThemeUtil.init(this)
    }
    @Throws(ExecuteException::class)
    private fun initLibraries() {
        val setupDone = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(com.deniscerri.ytdl.ui.SetupActivity.SETUP_DONE_KEY, false)
        if (!setupDone) return

        RuntimeManager.getInstance().init(this)

        // If RuntimeManager couldn't find Python via normal paths (no bundled .so, no companion APK),
        // patch the location to use our manually downloaded runtime from SetupActivity.
        if (!RuntimeManager.pythonLocation.isAvailable) {
            val baseDir = java.io.File(noBackupFilesDir, RuntimeManager.BASENAME)
            val pythonBinDir     = java.io.File(baseDir, "bin")
            val pythonRuntimeDir = java.io.File(baseDir, "packages/python")
            val pythonExe        = java.io.File(pythonBinDir, "libpython.so")
            RuntimeManager.pythonLocation = com.deniscerri.ytdl.core.packages.PackageBase.PackageLocation(
                binDir       = pythonBinDir,
                ldDir        = pythonRuntimeDir,
                executable   = pythonExe,
                isDownloaded = false,
                isBundled    = true,
                isAvailable  = pythonExe.exists(),
                canUninstall = false
            )
        }
    }

    private fun setDefaultValues(){
        val SPL = 1
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (sp.getInt("spl", 0) != SPL) {
            PreferenceManager.setDefaultValues(this, R.xml.root_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.downloading_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.general_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.processing_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.folders_preference, true)
            PreferenceManager.setDefaultValues(this, R.xml.updating_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.advanced_preferences, true)
            sp.edit().putInt("spl", SPL).apply()
        }

    }

    private fun createNotificationChannels() {
        val notificationUtil = NotificationUtil(this)
        notificationUtil.createNotificationChannel()
    }

    companion object {
        private const val TAG = "App"
        private lateinit var applicationScope: CoroutineScope
        lateinit var instance: App
    }
}
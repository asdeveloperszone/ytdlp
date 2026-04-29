package com.deniscerri.ytdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.RuntimeManager
import com.deniscerri.ytdl.core.ZipUtils
import com.deniscerri.ytdl.core.packages.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class SetupActivity : BaseActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var stepText: TextView

    companion object {
        const val SETUP_DONE_KEY = "setup_complete"

        // Where we store our manually extracted Python runtime
        // This mirrors what PackageBase calls "packages/python"
        private fun getPythonRuntimeDir(context: android.content.Context): File {
            return File(
                File(context.noBackupFilesDir, RuntimeManager.BASENAME),
                "packages/python"
            )
        }

        // Where we place the python executable (.so)
        // RuntimeManager looks here as bundledBinDir = nativeLibraryDir
        // Since we can't put files there, we use a custom bin dir
        private fun getPythonBinDir(context: android.content.Context): File {
            return File(
                File(context.noBackupFilesDir, RuntimeManager.BASENAME),
                "bin"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(SETUP_DONE_KEY, false)) {
            // Verify binaries still exist — reinstall if wiped
            val ytdlpBin = File(
                File(File(noBackupFilesDir, RuntimeManager.BASENAME), RuntimeManager.ytdlpDirName),
                RuntimeManager.ytdlpBin
            )
            val pythonBin = File(getPythonBinDir(this), "libpython.so")
            if (ytdlpBin.exists() && pythonBin.exists()) {
                goToMain()
                return
            }
            // Binaries missing (e.g. app data cleared) — redo setup
            prefs.edit().putBoolean(SETUP_DONE_KEY, false).apply()
        }

        setContentView(R.layout.activity_setup)
        statusText   = findViewById(R.id.setup_status)
        progressBar  = findViewById(R.id.setup_progress)
        progressText = findViewById(R.id.setup_progress_text)
        stepText     = findViewById(R.id.setup_step)

        startSetup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main setup flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun startSetup() {
        lifecycleScope.launch {
            try {
                // Step 1 — Python runtime
                setupPython()

                // Step 2 — yt-dlp binary
                setupYtDlp()

                // Patch RuntimeManager to use our custom Python location
                patchRuntimeManager()

                // Mark complete
                PreferenceManager.getDefaultSharedPreferences(this@SetupActivity)
                    .edit().putBoolean(SETUP_DONE_KEY, true).apply()

                goToMain()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Setup failed:\n${e.message}"
                    stepText.text = "Check internet connection and restart the app."
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1: Python
    //
    // Flow:
    //   1. Fetch releases from deniscerri/ytdlnis-packages
    //   2. Find latest python release for this device's ABI
    //   3. Download the companion APK (it's a ZIP)
    //   4. Extract lib/<arch>/libpython.zip.so from the APK ZIP
    //   5. Unzip the runtime from libpython.zip.so → packages/python/
    //   6. Copy libpython.so executable → bin/libpython.so
    //   7. chmod 755 everything
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun setupPython() {
        updateUI("Downloading Python runtime…", "Step 1 of 2", 0)

        val arch = Python.getInstance().getArchSuffix()
        val client = buildClient()

        // 1. Fetch releases list
        val releasesJson = JSONArray(
            client.get("https://api.github.com/repos/deniscerri/ytdlnis-packages/releases")
        )

        // 2. Find latest python release for this arch
        var downloadUrl: String? = null
        var totalSize = 0L
        outer@ for (i in 0 until releasesJson.length()) {
            val release = releasesJson.getJSONObject(i)
            if (!release.getString("tag_name").contains("python")) continue
            val assets = release.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.getString("name").contains(arch)) {
                    downloadUrl = asset.getString("browser_download_url")
                    totalSize   = asset.getLong("size")
                    break@outer
                }
            }
        }
        if (downloadUrl == null)
            throw Exception("No Python package found for arch: $arch")

        // 3. Download companion APK to cache
        val tempApk = File(cacheDir, "python_companion.apk")
        downloadWithProgress(client, downloadUrl, totalSize, tempApk) { pct ->
            updateProgressOnly(pct)
        }

        // 4. Extract libpython.zip.so from inside the APK
        //    APK is a ZIP — the .so lives at lib/<arch>/libpython.zip.so
        val libPythonZip = File(cacheDir, "libpython.zip.so")
        extractEntryFromZip(tempApk, "lib/$arch/libpython.zip.so", libPythonZip)
        tempApk.delete()

        // 5. Unzip Python runtime to packages/python/
        val pythonRuntimeDir = getPythonRuntimeDir(this).apply {
            deleteRecursively()
            mkdirs()
        }
        withContext(Dispatchers.IO) {
            ZipUtils.unzip(libPythonZip, pythonRuntimeDir)
        }
        libPythonZip.delete()

        // 6. Copy the python executable .so to our custom bin dir
        //    Inside the unzipped runtime, the executable is at usr/bin/python3
        //    But RuntimeManager needs it as libpython.so in a bin dir
        val pythonBinDir = getPythonBinDir(this).apply { mkdirs() }
        val pythonExe = File(pythonRuntimeDir, "usr/bin/python3")
        if (!pythonExe.exists()) {
            // Fallback: search for any python binary
            val found = pythonRuntimeDir.walkTopDown()
                .firstOrNull { it.name.startsWith("python") && it.isFile && !it.name.endsWith(".py") }
                ?: throw Exception("Python executable not found in runtime")
            found.copyTo(File(pythonBinDir, "libpython.so"), overwrite = true)
        } else {
            pythonExe.copyTo(File(pythonBinDir, "libpython.so"), overwrite = true)
        }

        // 7. chmod 755 everything
        withContext(Dispatchers.IO) {
            Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", pythonRuntimeDir.absolutePath)).waitFor()
            Runtime.getRuntime().exec(arrayOf("chmod", "755", File(pythonBinDir, "libpython.so").absolutePath)).waitFor()
        }

        updateUI("Python ready ✓", "Step 1 of 2", 100)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2: yt-dlp
    //
    // Flow:
    //   1. Fetch latest release from yt-dlp/yt-dlp
    //   2. Find the plain "yt-dlp" asset (Linux binary, no extension)
    //   3. Download directly to no_backup/ytdlnis/yt-dlp/yt-dlp
    //   4. chmod 755
    //   5. Save version tag to SharedPreferences (same keys YTDLUpdater uses)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun setupYtDlp() {
        updateUI("Downloading yt-dlp…", "Step 2 of 2", 0)

        val client = buildClient()

        // 1. Get latest release info
        val json = JSONObject(
            client.get("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        )
        val tagName = json.getString("tag_name")

        // 2. Find binary asset
        val assets = json.getJSONArray("assets")
        var downloadUrl: String? = null
        var totalSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == "yt-dlp") {
                downloadUrl = asset.getString("browser_download_url")
                totalSize   = asset.getLong("size")
                break
            }
        }
        if (downloadUrl == null)
            throw Exception("yt-dlp binary not found in release assets")

        // 3. Download to final path
        val ytdlpDir = File(
            File(noBackupFilesDir, RuntimeManager.BASENAME),
            RuntimeManager.ytdlpDirName
        ).apply { mkdirs() }
        val ytdlpBin = File(ytdlpDir, RuntimeManager.ytdlpBin)

        downloadWithProgress(client, downloadUrl, totalSize, ytdlpBin) { pct ->
            updateProgressOnly(pct)
        }

        // 4. chmod +x
        withContext(Dispatchers.IO) {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", ytdlpBin.absolutePath)).waitFor()
        }

        // 5. Save version — same keys as YTDLUpdater so the app knows the version
        PreferenceManager.getDefaultSharedPreferences(this).edit().apply {
            putString("dlpVersion", tagName)
            putString("dlpVersionName", json.optString("name", tagName))
            apply()
        }

        updateUI("yt-dlp ready ✓", "Step 2 of 2", 100)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3: Patch RuntimeManager's pythonLocation to use our custom paths
    //
    // RuntimeManager.init() calls python.init(context) which calls getLocation()
    // getLocation() only finds Python if:
    //   a) companion APK is installed (we skipped this)
    //   b) libpython.zip.so is in nativeLibraryDir (we can't put it there)
    //
    // So we directly set RuntimeManager.pythonLocation after init()
    // using the paths we actually put the files in.
    // ─────────────────────────────────────────────────────────────────────────

    private fun patchRuntimeManager() {
        val pythonBinDir    = getPythonBinDir(this)
        val pythonRuntimeDir = getPythonRuntimeDir(this)
        val pythonExe       = File(pythonBinDir, "libpython.so")

        // Only patch if the normal init didn't find Python
        if (!RuntimeManager.pythonLocation.isAvailable) {
            RuntimeManager.pythonLocation = com.deniscerri.ytdl.core.packages.PackageBase.PackageLocation(
                binDir      = pythonBinDir,
                ldDir       = pythonRuntimeDir,
                executable  = pythonExe,
                isDownloaded = false,
                isBundled   = true,
                isAvailable = pythonExe.exists(),
                canUninstall = false
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private suspend fun OkHttpClient.get(url: String): String = withContext(Dispatchers.IO) {
        val response = newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} fetching $url")
        response.body!!.string()
    }

    private suspend fun downloadWithProgress(
        client: OkHttpClient,
        url: String,
        totalSize: Long,
        dest: File,
        onProgress: suspend (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        // Retry up to 3 times on failure
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (!response.isSuccessful)
                    throw Exception("HTTP ${response.code}")

                var downloaded = 0L
                response.body!!.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                            launch(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }

                // Verify file is not empty
                if (dest.length() == 0L) throw Exception("Downloaded file is empty")
                return@withContext // success
            } catch (e: Exception) {
                lastError = e
                dest.delete()
                if (attempt < 2) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Retrying… (attempt ${attempt + 2}/3)"
                    }
                    kotlinx.coroutines.delay(2000)
                }
            }
        }
        throw lastError ?: Exception("Download failed after 3 attempts")
    }

    private suspend fun extractEntryFromZip(zipFile: File, entryName: String, destFile: File) =
        withContext(Dispatchers.IO) {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        destFile.outputStream().use { out -> zis.copyTo(out) }
                        zis.closeEntry()
                        if (destFile.length() == 0L)
                            throw Exception("Extracted file is empty: $entryName")
                        return@withContext
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                throw Exception("'$entryName' not found inside ${zipFile.name}")
            }
        }

    private suspend fun updateUI(status: String, step: String, progress: Int) {
        withContext(Dispatchers.Main) {
            statusText.text   = status
            stepText.text     = step
            progressBar.progress  = progress
            progressText.text = "$progress%"
        }
    }

    private suspend fun updateProgressOnly(pct: Int) {
        withContext(Dispatchers.Main) {
            progressBar.progress = pct
            progressText.text = "$pct%"
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

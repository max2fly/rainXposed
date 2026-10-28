package io.github.revenge.xposed.modules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.AtomicFile
import android.widget.Toast
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.rushii.libunbound.LibUnbound
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils
import io.github.revenge.xposed.Utils.Companion.JSON
import io.github.revenge.xposed.Utils.Companion.reloadApp
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.bridge.BridgeModule
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.lang.ref.WeakReference

@Serializable
data class CustomLoadUrl(val enabled: Boolean = false, val url: String = "")

@Serializable
data class LoaderConfig(val customLoadUrl: CustomLoadUrl = CustomLoadUrl(), val disableInjection: Boolean = false)

@Serializable
data class EndpointInfo(val paths: ArrayList<String>, val hash: String? = null, val version: String)

object UpdaterModule : Module() {
    // Real Hermes bytecode version of the running Discord build, read from its own bundle
    // header by HookScriptLoaderModule. Authoritative — unlike LibUnbound, which misreports.
    @Volatile
    var deviceBytecodeVersion: Int? = null

    private lateinit var config: LoaderConfig
    val isCustomUrlEnabled: Boolean
        get() = config.customLoadUrl.enabled
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastActivity: WeakReference<Activity>? = null

    private lateinit var cacheDir: File
    private lateinit var bundle: File
    private lateinit var etag: File

    private const val TIMEOUT_STRICT = 15000L
    private const val MIN_BYTECODE_SIZE = 512
    private const val ETAG_FILE = "etag.txt"
    private const val CONFIG_FILE = "loader.json"

    private const val DEFAULT_BASE_URL = "https://codeberg.org/raincord/rain/releases/download/latest/"
    // This is rarely used as now we check for hbc first
    private const val DEFAULT_BUNDLE_NAME = "rain.js"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { mkdirs() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }

        bundle = File(cacheDir, Constants.MAIN_SCRIPT_FILE)
        etag = File(cacheDir, ETAG_FILE)

        val configFile = File(filesDir, CONFIG_FILE)
        config = runCatching {
            if (configFile.exists()) JSON.decodeFromString<LoaderConfig>(configFile.readText()) else LoaderConfig()
        }.getOrDefault(LoaderConfig())

        BridgeModule.registerMethod("updater.clear") {
            if (bundle.exists()) bundle.delete()
            if (etag.exists()) etag.delete()
            null
        }

        BridgeModule.registerMethod("updater.download") {
            scope.launch { downloadScript(showUpdateDialog = false, isExplicit = true).join() }
            null
        }
        
        BridgeModule.registerMethod("updater.reload") {
            scope.launch {
                downloadScript(showUpdateDialog = false, isExplicit = true).join()
                withContext(Dispatchers.Main) { reloadApp() }
            }
            null
        }
    }

    fun downloadScript(activity: Activity? = null, showUpdateDialog: Boolean = true, isExplicit: Boolean = false): Job = scope.launch {
        if (!isExplicit && !config.customLoadUrl.enabled && bundle.exists()) {
            return@launch
        }

        try {
            HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = TIMEOUT_STRICT
                    connectTimeoutMillis = 5000L
                    socketTimeoutMillis = TIMEOUT_STRICT
                }
                install(UserAgent) { agent = Constants.USER_AGENT }
                install(HttpRedirect) { checkHttpMethod = false }
            }.use { client ->
                val targetUrl = resolveTargetUrl(client)
                Log.i("Fetching bundle: $targetUrl")

                val response: HttpResponse = client.get(targetUrl) {
                    headers {
                        if (etag.exists() && bundle.exists()) {
                            append(HttpHeaders.IfNoneMatch, etag.readText())
                        }
                    }
                }

                when (response.status) {
                    HttpStatusCode.OK -> {
                        val bytes: ByteArray = response.body()
                        
                        if (bytes.size < MIN_BYTECODE_SIZE) {
                            throw Exception("Payload too small (${bytes.size} bytes). Possible corrupt build.")
                        }

                        AtomicFile(bundle).apply {
                            val stream = startWrite()
                            try {
                                stream.write(bytes)
                                finishWrite(stream)
                            } catch (e: Exception) {
                                failWrite(stream)
                                throw e
                            }
                        }

                        response.headers[HttpHeaders.ETag]?.let { etag.writeText(it) } ?: etag.delete()
                        Log.i("Bundle updated: ${bytes.size} bytes")

                        if (activity != null) {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Update Successful")
                                    .setMessage("Reload required.")
                                    .setPositiveButton("Reload") { _, _ -> reloadApp() }
                                    .setCancelable(false)
                                    .show()
                            }
                        }
                    }
                    HttpStatusCode.NotModified -> Log.i("Bundle is up to date (304)")
                    else -> throw ResponseException(response, "HTTP ${response.status}")
                }
            }
        } catch (e: Throwable) {
            Log.e("Updater Error", e)
            if (activity != null) withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun resolveTargetUrl(client: HttpClient): String {
        if (config.customLoadUrl.enabled && config.customLoadUrl.url.isNotEmpty()) {
            return config.customLoadUrl.url
        }

        val libunboundVersion = withTimeoutOrNull(2000L) {
            runCatching { LibUnbound.getHermesRuntimeBytecodeVersion() }.getOrNull()
        }
        // Prefer the version read from Discord's own bundle header (authoritative); only fall
        // back to LibUnbound (known to misreport on some builds) or 96 if that wasn't available.
        val hermesVersion = deviceBytecodeVersion ?: libunboundVersion ?: 96
        Log.i("Hermes bytecode version: using=$hermesVersion (bundleHeader=$deviceBytecodeVersion, libunbound=$libunboundVersion)")
        val hbcName = "rain.$hermesVersion.hbc"

        return try {
            // Always use the LATEST release (newest rain code). Prefer a prebuilt .hbc matching
            // THIS device's Hermes version (instant load). Otherwise use the latest rain.js —
            // Hermes compiles it on-device to the right bytecode version, which is how rain runs
            // on older Discord builds (e.g. 331.14 = v96) whose latest release ships no matching
            // .hbc. Match by basename since info.paths entries look like "dist/rain.98.hbc".
            val info = JSON.decodeFromString<EndpointInfo>(client.get("${DEFAULT_BASE_URL}info.json").bodyAsText())
            fun has(name: String) = info.paths.any { it.substringAfterLast('/') == name }
            when {
                has(hbcName) -> DEFAULT_BASE_URL + hbcName
                has("rain.min.js") -> DEFAULT_BASE_URL + "rain.min.js"
                else -> DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
            }
        } catch (e: Exception) {
            Log.e("Failed to resolve bundle URL, falling back to latest rain.js", e)
            DEFAULT_BASE_URL + DEFAULT_BUNDLE_NAME
        }
    }

    override fun onActivity(activity: Activity) {
        lastActivity = WeakReference(activity)
    }

    fun setDisableInjection(context: Context, disabled: Boolean) {
        val filesDir = File(context.dataDir, Constants.FILES_DIR).apply { mkdirs() }
        val configFile = File(filesDir, CONFIG_FILE)
        val newCfg = config.copy(disableInjection = disabled)
        configFile.writeText(JSON.encodeToString(newCfg))
        config = newCfg
        Toast.makeText(context, "Injection ${if (disabled) "disabled" else "enabled"}", Toast.LENGTH_SHORT).show()
    }

    fun isInjectionDisabled(context: Context? = null): Boolean {
        return if (::config.isInitialized) config.disableInjection else false
    }

    fun updateConfig(newConfig: LoaderConfig) {
        config = newConfig
    }
}
package io.github.revenge.xposed.modules

import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.content.Context
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.revenge.xposed.Constants
import io.github.revenge.xposed.HookStateHolder
import io.github.revenge.xposed.Module
import io.github.revenge.xposed.Utils.Log
import io.github.revenge.xposed.modules.HookScriptLoaderModule.PRELOADS_DIR
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Method

/**
 * Hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * Preload scripts should be placed in the [PRELOADS_DIR] directory inside the module's files directory.
 *
 * The main bundle should be placed in the [Constants.CACHE_DIR] directory named [Constants.MAIN_SCRIPT_FILE].
 * If the bundle file does not exist, it will attempt to load `assets://revenge.bundle` from the module's assets.
 */
object HookScriptLoaderModule : Module() {
    private lateinit var preloadsDir: File
    private lateinit var mainScript: File

    // Directory to read loader config from when checking disable flag
    private var moduleFilesDir: File? = null

    private lateinit var modulePath: String
    private lateinit var resources: XModuleResources

    const val PRELOADS_DIR = "preloads"

    override fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {
        this@HookScriptLoaderModule.modulePath = startupParam.modulePath
    }

    override fun onContext(context: android.content.Context) {
        // Keep a reference to the module's files directory so we can check the loader config
        try {
            val dir = File(context.dataDir, Constants.FILES_DIR).apply { asDir() }
            moduleFilesDir = dir
        } catch (_: Exception) {
            // best-effort only
        }
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { asDir() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { asDir() }

        preloadsDir = File(filesDir, PRELOADS_DIR).apply { asDir() }
        mainScript = File(cacheDir, Constants.MAIN_SCRIPT_FILE).apply { asFile() }

        listOf(
            "com.facebook.react.runtime.ReactInstance\$loadJSBundle$1",
            "com.facebook.react.runtime.ReactInstance$1",
            // TODO: Remove once Discord fully switches to Bridgeless
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }.forEach { hook(it) }
    }

    private fun hook(instance: Class<*>) = runCatching {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )

        val loadScriptFromFile = instance.method(
            "loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )

        loadScriptFromAssets.hook {
            before {
                Log.i("Received call to loadScriptFromAssets: ${args[1]} (sync: ${args[2]})")
                runCustomScripts(loadScriptFromFile, loadScriptFromAssets)
            }
        }

        loadScriptFromFile.hook {
            before {
                Log.i("Received call to loadScriptFromFile: ${args[0]} (sync: ${args[2]})")
                runCustomScripts(loadScriptFromFile, loadScriptFromAssets)
            }
        }
    }.onFailure {
        Log.e("Failed to hook script loading methods in ${instance.name}:", it)
    }

    private fun HookScope.runCustomScripts(loadScriptFromFile: Method, loadScriptFromAssets: Method) {
        Log.i("Running custom scripts...")

        // Read the running Discord bundle's Hermes bytecode version from its header so the
        // updater can fetch the matching rain.<v>.hbc. Authoritative (LibUnbound misreports).
        readDeviceBytecodeVersion()?.let {
            Log.i("Discord bundle Hermes bytecode version (from header): $it")
            UpdaterModule.deviceBytecodeVersion = it
        }

        // Self-heal: if a cached bundle was built for a different Hermes version (e.g. left
        // over from before a Discord update), delete it so the correct one is re-downloaded,
        // instead of loading it and crashing the runtime with a bytecode-version mismatch.
        UpdaterModule.deviceBytecodeVersion?.let { deviceVer ->
            if (mainScript.exists()) {
                val cachedVer = runCatching { readBytecodeVersion(mainScript.inputStream()) }.getOrNull()
                if (cachedVer != null && cachedVer != deviceVer) {
                    Log.i("Cached bundle is v$cachedVer but device needs v$deviceVer — deleting stale cache")
                    mainScript.delete()
                }
            }
        }

        runBlocking {
            val ready = async { HookStateHolder.readyDeferred.join() }
            val isCustomUrl = UpdaterModule.isCustomUrlEnabled

            if (!mainScript.exists() || isCustomUrl) {
                val reason = if (isCustomUrl) "Custom URL enabled" else "Main script does not exist"
                Log.i("$reason, downloading before load...")
                val download =
                    async { UpdaterModule.downloadScript(showUpdateDialog = false).join() }
                awaitAll(ready, download)
            } else {
                Log.i("Main script exists, updating in background...")
                UpdaterModule.downloadScript(showUpdateDialog = true)
                ready.await()
            }
        }

        val loadSynchronously = args[2]
        val runScriptFile = { file: File ->
            Log.i("Loading script: ${file.absolutePath}")

            XposedBridge.invokeOriginalMethod(
                loadScriptFromFile, thisObject, arrayOf(file.absolutePath, file.absolutePath, loadSynchronously)
            )

            Unit
        }

        try {
            preloadsDir.walk().filter { it.isFile }.forEach(runScriptFile)

            // Check whether bundle injection execution is disabled via loader config.
            // UpdaterModule.isInjectionDisabled reads the in-memory config (loaded in its onLoad),
            // and falls back to disk if necessary. We call it without a Context because by the time
            // this runs the UpdaterModule config should already be available.
            val injectionDisabled = try {
                io.github.revenge.xposed.modules.UpdaterModule.isInjectionDisabled()
            } catch (e: Throwable) {
                Log.e("Failed to check injection disabled flag: ${e.message}")
                false
            }

            if (injectionDisabled) {
                Log.i("Bundle injection disabled by loader config - skipping main bundle execution")
            } else {
                if (mainScript.exists()) runScriptFile(mainScript)
                else {
                    Log.i("Main script does not exist, falling back")

                    if (!::resources.isInitialized) resources = XModuleResources.createInstance(modulePath, null)

                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromAssets,
                        thisObject,
                        arrayOf(resources.assets, "assets://revenge.bundle", loadSynchronously)
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e("Unable to run scripts:", e)
        }
    }

    /**
     * Reads the Hermes bytecode version from the header of the bundle currently being loaded
     * (Discord's own assets://index.android.bundle). Returns null if this hook invocation isn't
     * an AssetManager load or the data isn't a Hermes bytecode file.
     *
     * Hermes header: magic uint64 0x1F1903C103BC1FC6 (little-endian) then uint32 version at offset 8.
     */
    private fun HookScope.readDeviceBytecodeVersion(): Int? {
        val assetManager = args.getOrNull(0) as? AssetManager ?: return null
        val assetPath = (args.getOrNull(1) as? String)?.substringAfter("assets://")?.takeIf { it.isNotEmpty() }
            ?: return null
        return runCatching { readBytecodeVersion(assetManager.open(assetPath)) }.getOrNull()
    }

    /** Reads the Hermes bytecode version from a stream's header, or null if not Hermes bytecode. */
    private fun readBytecodeVersion(input: java.io.InputStream): Int? = input.use {
        val header = ByteArray(12)
        var read = 0
        while (read < header.size) {
            val n = it.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        when {
            read < header.size -> null
            // Hermes magic little-endian starts with C6 1F ...
            (header[0].toInt() and 0xFF) != 0xC6 || (header[1].toInt() and 0xFF) != 0x1F -> null
            else -> (header[8].toInt() and 0xFF) or
                ((header[9].toInt() and 0xFF) shl 8) or
                ((header[10].toInt() and 0xFF) shl 16) or
                ((header[11].toInt() and 0xFF) shl 24)
        }
    }
}

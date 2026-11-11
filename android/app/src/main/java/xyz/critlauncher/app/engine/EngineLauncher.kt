package xyz.critlauncher.app.engine

import android.content.Context
import android.os.Build
import android.system.Os
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.oracle.dalvik.VMLauncher
import org.json.JSONObject
import java.io.File

/** What the Python backend hands over: the java command it already builds, minus the java executable. */
class LaunchSpec(
    val major: Int,
    val args: List<String>,
    val gameDir: String,
    val logPath: String,
) {
    companion object {
        fun read(path: String): LaunchSpec {
            val o = JSONObject(File(path).readText())
            val arr = o.getJSONArray("args")
            return LaunchSpec(
                major = o.optInt("major", 17),
                args = List(arr.length()) { arr.getString(it) },
                gameDir = o.getString("game_dir"),
                logPath = o.getString("log"),
            )
        }
    }
}

/**
 * Starts the game's JVM inside this process using the Zalith engine. The sequence follows
 * ZalithLauncher2 (Launcher.launchJvm): library path, environment, dlopen the runtime, then VMLauncher.
 * Blocks until the JVM exits and returns its exit code.
 */
object EngineLauncher {
    private const val RENDERER_LIB = "libgl4es_114.so"
    private const val RENDERER_ID = "opengles2"

    fun run(ctx: Context, spec: LaunchSpec, width: Int, height: Int): Int {
        EngineFiles.ensureComponents(ctx)
        val home = EngineFiles.ensureRuntime(ctx, spec.major)
        val isJdk8 = File(home, "jre").isDirectory && File(home, "bin/javac").exists()
        val javaHome = if (isJdk8) File(home, "jre").path else home.path

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val lwjglVersion = "3.3.3"
        val lwjglDir = File(EngineFiles.componentsDir(ctx), "lwjgl/$lwjglVersion")
        val lwjglNatives = findNativesDir(lwjglDir)

        File(spec.gameDir).mkdirs()
        File(spec.logPath).parentFile?.mkdirs()
        LoggerBridge.start(spec.logPath)
        LoggerBridge.appendTitle("Crit Launcher engine")
        LoggerBridge.appendInfo("Java home: $javaHome, LWJGL: $lwjglVersion, natives: $lwjglNatives")

        val libDir = javaLibDir(javaHome)
        val jvmDir = if (File("$javaHome$libDir/server/libjvm.so").exists()) "/server" else "/client"
        val sysLib = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "lib64" else "lib"

        val runtimeLdPath = listOf(
            "$javaHome$libDir/jli",
            "$javaHome$libDir$jvmDir",
            "$javaHome$libDir",
            "/system/$sysLib", "/vendor/$sysLib", "/vendor/$sysLib/hw", "/system_ext/$sysLib",
            lwjglNatives, nativeDir,
        ).joinToString(":")
        ZLBridge.setLdLibraryPath(runtimeLdPath)

        setEnv(ctx, javaHome, nativeDir, lwjglNatives, sysLib, width, height)

        LoggerBridge.appendTitle("DLOPEN Java Runtime")
        dlopenRuntime(javaHome, libDir, jvmDir, home)
        ZLBridge.dlopen("$nativeDir/libopenal.so")
        if (!ZLBridge.dlopen(RENDERER_LIB) && !ZLBridge.dlopen("$nativeDir/$RENDERER_LIB")) {
            LoggerBridge.append("Failed to load renderer $RENDERER_LIB")
        }

        val jvmArgs = buildArgs(ctx, spec, home.path, javaHome, nativeDir, lwjglDir, lwjglNatives, width, height)
        LoggerBridge.appendTitle("JVM Args")
        var hide = false
        for (a in jvmArgs) {
            LoggerBridge.appendInfo(if (hide) "********************" else a)
            hide = a == "--accessToken"
        }

        ZLBridge.setupExitMethod(ctx.applicationContext)
        ZLBridge.initializeGameExitHook()
        ZLBridge.chdir(spec.gameDir)

        val code = VMLauncher.launchJVM(jvmArgs.toTypedArray())
        LoggerBridge.append("Java Exit code: $code")
        return code
    }

    private fun findNativesDir(lwjglDir: File): String {
        val natives = File(lwjglDir, "natives")
        val abi = File(natives, EngineFiles.abiName())
        if (abi.isDirectory) return abi.path
        // fall back to whatever single per-arch folder the engine shipped
        natives.listFiles { f -> f.isDirectory }?.firstOrNull()?.let { return it.path }
        return natives.path
    }

    private fun javaLibDir(javaHome: String): String {
        for (arch in listOf("aarch64", "arm")) {
            if (File(javaHome, "lib/$arch").isDirectory) return "/lib/$arch"
        }
        return "/lib"
    }

    private fun setEnv(
        ctx: Context, javaHome: String, nativeDir: String, lwjglNatives: String,
        sysLib: String, width: Int, height: Int,
    ) {
        val sysLdPath = listOf(
            lwjglNatives, "/system/$sysLib", "/vendor/$sysLib", "/vendor/$sysLib/hw", "/system_ext/$sysLib", nativeDir,
        ).joinToString(":")
        val env = linkedMapOf(
            "POJAV_NATIVEDIR" to nativeDir,
            "JAVA_HOME" to javaHome,
            "HOME" to (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath,
            "TMPDIR" to ctx.cacheDir.absolutePath,
            "LD_LIBRARY_PATH" to sysLdPath,
            "PATH" to "$javaHome/bin:" + (Os.getenv("PATH") ?: ""),
            "AWTSTUB_WIDTH" to width.toString(),
            "AWTSTUB_HEIGHT" to height.toString(),
            "MOD_ANDROID_RUNTIME" to "",
            "DALVIK_JAVAVM" to ZLBridge.getJavaVMPointer().toString(),
            "DALVIK_APPLICATION" to ZLBridge.jObjectToString(ctx.applicationContext),
            "ALSOFT_DRIVERS" to "opensl",
            // renderer: GL4ES (OpenGL ES 2 translation)
            "POJAV_RENDERER" to RENDERER_ID,
            "SDL_OPENGL_LIBRARY" to RENDERER_ID,
            "LIBGL_ES" to "2",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NOERROR" to "1",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NORMALIZE" to "1",
        )
        LoggerBridge.appendTitle("Env Map")
        for ((k, v) in env) {
            LoggerBridge.appendInfo("$k = $v")
            try {
                Os.setenv(k, v, true)
            } catch (e: Exception) {
                LoggerBridge.append("setenv $k failed: $e")
            }
        }
    }

    private fun dlopenRuntime(javaHome: String, libDir: String, jvmDir: String, home: File) {
        val lib = "$javaHome$libDir"
        val jli = if (File("$lib/jli/libjli.so").exists()) "$lib/jli" else lib
        ZLBridge.dlopen("$jli/libjli.so")
        ZLBridge.dlopen("$lib$jvmDir/libjvm.so")
        for (n in listOf("libfreetype", "libverify", "libjava", "libnet", "libnio", "libawt", "libawt_headless", "libfontmanager")) {
            ZLBridge.dlopen("$lib/$n.so")
        }
        home.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { ZLBridge.dlopen(it.absolutePath) }
    }

    private fun resolvConf(ctx: Context): File {
        val f = File(ctx.filesDir, "engine/resolv.conf")
        f.parentFile?.mkdirs()
        val text = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
        if (!f.exists() || f.readText() != text) f.writeText(text)
        return f
    }

    private fun buildArgs(
        ctx: Context, spec: LaunchSpec, home: String, javaHome: String, nativeDir: String,
        lwjglDir: File, lwjglNatives: String, width: Int, height: Int,
    ): List<String> {
        val props = linkedMapOf(
            "java.home" to javaHome,
            "java.io.tmpdir" to ctx.cacheDir.absolutePath,
            "jna.boot.library.path" to nativeDir,
            "user.home" to spec.gameDir,
            "os.name" to "Linux",
            "os.version" to "Android-${Build.VERSION.RELEASE}",
            "pojav.path.minecraft" to spec.gameDir,
            "org.lwjgl.vulkan.libname" to "libvulkan.so",
            "org.lwjgl.librarypath" to lwjglNatives,
            "glfwstub.windowWidth" to width.toString(),
            "glfwstub.windowHeight" to height.toString(),
            "glfwstub.initEgl" to "false",
            "ext.net.resolvPath" to resolvConf(ctx).absolutePath,
            "java.awt.headless" to "true",
            "log4j2.formatMsgNoLookups" to "true",
            "java.rmi.server.useCodebaseOnly" to "true",
            "com.sun.jndi.rmi.object.trustURLCodebase" to "false",
            "com.sun.jndi.cosnaming.object.trustURLCodebase" to "false",
            "net.minecraft.clientmodname" to "CritLauncher",
            "fml.earlyprogresswindow" to "false",
            "fml.ignoreInvalidMinecraftCertificates" to "true",
            "fml.ignorePatchDiscrepancies" to "true",
            "loader.disable_forked_guis" to "true",
            "jdk.lang.Process.launchMechanism" to "FORK",
            "file.encoding" to "UTF-8",
            "sun.stdout.encoding" to "UTF-8",
            "sun.stderr.encoding" to "UTF-8",
        )

        // Python's command, with the pieces the engine replaces taken out.
        val given = spec.args.toMutableList()
        given.removeAll { it.startsWith("-Djava.library.path=") || it.startsWith("-Dorg.lwjgl.librarypath=") }
        val cpIdx = given.indexOfFirst { it == "-cp" || it == "-classpath" }
        if (cpIdx >= 0 && cpIdx + 1 < given.size) {
            given[cpIdx + 1] = rewriteClasspath(given[cpIdx + 1], lwjglDir)
        }

        val out = mutableListOf("$home/bin/java")
        props.forEach { (k, v) -> out += "-D$k=$v" }
        out += "-Djava.library.path=$lwjglNatives"
        out += "-Dorg.lwjgl.opengl.libname=$RENDERER_LIB"
        out += "-Dorg.lwjgl.openal.libname=$nativeDir/libopenal.so"
        out += "-Dorg.lwjgl.freetype.libname=$lwjglNatives/libfreetype.so"
        out += "-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared"
        out += "-Dorg.lwjgl.system.allocator=system"
        out += "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}"
        out += given
        return out
    }

    /** Desktop LWJGL jars are x86; swap them for the engine's ARM-ready LWJGL build. */
    private fun rewriteClasspath(cp: String, lwjglDir: File): String {
        val kept = cp.split(":").filter { entry ->
            val name = entry.substringAfterLast('/')
            entry.isNotEmpty() && !name.startsWith("lwjgl") && !entry.contains("/org/lwjgl/")
        }
        val engineJars = lwjglDir.listFiles { f -> f.name.endsWith(".jar") && f.name != "lwjgl-lwjglx.jar" }
            ?.sortedBy { f ->
                when {
                    f.name == "lwjgl.jar" -> 0
                    f.name.endsWith("-merged-modules.jar") -> 1
                    else -> 2
                }
            }?.map { it.absolutePath } ?: emptyList()
        return (engineJars + kept).joinToString(":")
    }
}

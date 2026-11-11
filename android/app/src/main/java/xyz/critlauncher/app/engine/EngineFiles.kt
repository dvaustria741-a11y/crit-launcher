package xyz.critlauncher.app.engine

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Unpacks the engine pieces that extract-engine.sh put into assets/engine/:
 *   assets/engine/app_runtime/lwjgl/<v>/*  -> files/engine/components/lwjgl/<v>/
 *   assets/engine/components/*             -> files/engine/components/
 *   assets/engine/runtimes/jre-<n>/*.tar.xz -> files/engine/runtimes/Internal-<n>/   (on demand)
 */
object EngineFiles {
    private const val ASSET_ROOT = "engine"

    fun root(ctx: Context) = File(ctx.filesDir, "engine")
    fun componentsDir(ctx: Context) = File(root(ctx), "components")

    /** "arm64" or "arm", the suffix Zalith uses for its per-architecture JRE packs. */
    fun packArch(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "arm64" else "arm"

    /** Directory name Zalith uses for per-ABI natives. */
    fun abiName(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "arm64-v8a" else "armeabi-v7a"

    fun isBundled(ctx: Context): Boolean =
        (ctx.assets.list(ASSET_ROOT) ?: emptyArray()).isNotEmpty()

    /** Copies the LWJGL components and launcher jars (refreshed whenever the app is updated). */
    fun ensureComponents(ctx: Context) {
        if (!isBundled(ctx)) {
            throw IOException("This build has no launch engine bundled (assets/engine is missing).")
        }
        val dest = componentsDir(ctx)
        val stamp = File(dest, ".version")
        val current = stampOf(ctx)
        if (stamp.exists() && stamp.readText() == current) return
        dest.deleteRecursively()
        dest.mkdirs()
        copyAssetDir(ctx, "$ASSET_ROOT/components", dest)
        copyAssetDir(ctx, "$ASSET_ROOT/app_runtime", dest)
        stamp.writeText(current)
    }

    /** Picks the bundled JRE for [major], or the smallest bundled one that is newer. Returns its folder. */
    fun ensureRuntime(ctx: Context, major: Int): File {
        val bundled = (ctx.assets.list("$ASSET_ROOT/runtimes") ?: emptyArray())
            .filter { it.startsWith("jre-") }
            .mapNotNull { it.removePrefix("jre-").toIntOrNull() }
            .sorted()
        if (bundled.isEmpty()) throw IOException("No Java runtime is bundled in this build.")
        val use = bundled.firstOrNull { it >= major } ?: bundled.last()
        val dest = File(root(ctx), "runtimes/Internal-$use")
        val stamp = File(dest, ".crit-version")
        val current = stampOf(ctx)
        if (stamp.exists() && stamp.readText() == current && File(dest, "bin/java").exists()) return dest
        dest.deleteRecursively()
        dest.mkdirs()
        val base = "$ASSET_ROOT/runtimes/jre-$use"
        extractTarXz(ctx.assets.open("$base/universal.tar.xz"), dest)
        extractTarXz(ctx.assets.open("$base/bin-${packArch()}.tar.xz"), dest)
        File(dest, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        stamp.writeText(current)
        return dest
    }

    private fun stampOf(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()

    private fun copyAssetDir(ctx: Context, assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in children) copyAssetDir(ctx, "$assetPath/$name", File(dest, name))
    }

    private fun extractTarXz(raw: InputStream, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destPath) && out.canonicalPath != dest.canonicalPath) {
                    throw IOException("Blocked path outside the runtime folder: ${entry.name}")
                }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.path)
                    }
                    else -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        if (entry.mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                    }
                }
            }
        }
    }
}

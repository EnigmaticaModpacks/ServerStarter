package atm.bloodworkxgaming.serverstarter

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import org.apache.commons.io.FileUtils
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class JavaDownloadException(message: String, exception: Exception? = null) : IOException(message, exception)

/**
 * Handles finding, downloading and extracting a suitable JVM when none of the
 * ones on the $PATH match the versions supported by the modpack.
 */
class JavaManager(private val configFile: ConfigFile, private val internetManager: InternetManager) {

    private val runtimeBaseDir: File
        get() = File(configFile.install.baseInstallPath + "runtime/")

    /**
     * Returns the path to a locally managed 'java' executable for the given major version,
     * downloading and extracting it first if it isn't already cached.
     * Returns null if the user declines the download or if anything goes wrong.
     */
    fun obtainJava(majorVersion: String): String? {
        val installDir = File(runtimeBaseDir, "jdk-$majorVersion")
        val existing = findJavaExecutable(installDir)
        if (existing != null) {
            LOGGER.info("Using previously downloaded JVM at ${existing.absolutePath}")
            return existing.absolutePath
        }

        if (!configFile.launch.autoDownloadJava) {
            LOGGER.warn("No local JVM $majorVersion found and autoDownloadJava is disabled in the config.")
            return null
        }

        if (!promptUserForDownload(majorVersion)) {
            LOGGER.warn("User declined download of JVM $majorVersion.")
            return null
        }

        return try {
            downloadAndExtract(majorVersion, installDir)
            findJavaExecutable(installDir)?.absolutePath
        } catch (e: IOException) {
            LOGGER.error("Failed to download and install JVM $majorVersion", e)
            null
        }
    }

    private fun promptUserForDownload(majorVersion: String): Boolean {
        LOGGER.info("No suitable JVM (version $majorVersion) was found on your system.")
        LOGGER.info("ServerStarter can automatically download a matching JVM from Eclipse Adoptium (https://adoptium.net).")
        LOGGER.info("Do you want to download it now? [y/n]")

        val answer = readLine()
        return answer?.trim()?.equals("y", ignoreCase = true) == true
    }

    @Throws(IOException::class)
    private fun downloadAndExtract(majorVersion: String, installDir: File) {
        val os = OSUtil.adoptiumOs
        val arch = OSUtil.adoptiumArch
        val url = "https://api.adoptium.net/v3/binary/latest/$majorVersion/ga/$os/$arch/jre/hotspot/normal/eclipse"

        val downloadFile = File(runtimeBaseDir, "jdk-$majorVersion-download.${if (OSUtil.isWindows) "zip" else "tar.gz"}")

        LOGGER.info("Downloading JVM $majorVersion for $os/$arch from Adoptium...")
        try {
            internetManager.downloadToFile(url, downloadFile)
        } catch (e: IOException) {
            throw JavaDownloadException("Could not download JVM $majorVersion for $os/$arch. It might not be available.", e)
        }

        val tempExtractDir = File(runtimeBaseDir, "jdk-$majorVersion-extracting")
        LOGGER.info("Extracting JVM to ${tempExtractDir.absolutePath}")
        try {
            FileUtils.deleteDirectory(tempExtractDir)
            tempExtractDir.mkdirs()

            if (OSUtil.isWindows) {
                extractZip(downloadFile, tempExtractDir)
            } else {
                extractTarGz(downloadFile, tempExtractDir)
            }

            if (findJavaExecutable(tempExtractDir) == null) {
                throw JavaDownloadException("Extracted JVM $majorVersion but couldn't find a java executable in it.")
            }

            FileUtils.deleteDirectory(installDir)
            FileUtils.moveDirectory(tempExtractDir, installDir)
        } finally {
            downloadFile.delete()
            FileUtils.deleteDirectory(tempExtractDir)
        }

        LOGGER.info("Successfully installed JVM $majorVersion")
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = resolveEntry(destDir, entry.name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(tarGzFile.inputStream())).use { tarIn ->
            var entry: TarArchiveEntry? = tarIn.nextTarEntry
            while (entry != null) {
                val outFile = resolveEntry(destDir, entry.name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else if (entry.isSymbolicLink) {
                    // Ignore symlinks, the java runtime doesn't strictly need them to function
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> tarIn.copyTo(output) }

                    if (entry.mode and 0b001_000_000 != 0) {
                        try {
                            Files.setPosixFilePermissions(
                                outFile.toPath(),
                                Files.getPosixFilePermissions(outFile.toPath()) + PosixFilePermission.OWNER_EXECUTE
                            )
                        } catch (e: UnsupportedOperationException) {
                            // Not a posix filesystem, ignore
                        }
                    }
                }

                entry = tarIn.nextTarEntry
            }
        }
    }

    /**
     * Resolves an archive entry path against the destination directory, guarding against
     * zip-slip style path traversal from a malicious or corrupted archive.
     */
    private fun resolveEntry(destDir: File, entryName: String): File {
        val outFile = File(destDir, entryName)
        val destPath = destDir.canonicalFile.toPath().normalize()
        val outPath = outFile.canonicalFile.toPath().normalize()

        if (!outPath.startsWith(destPath)) {
            throw IOException("Archive entry is outside of the target directory: $entryName")
        }

        return outFile
    }

    /**
     * Adoptium archives extract to a single top-level folder (e.g. jdk-17.0.2+8-jre),
     * so we search one level deep for the actual java executable.
     */
    private fun findJavaExecutable(installDir: File): File? {
        if (!installDir.isDirectory) return null

        val exeName = if (OSUtil.isWindows) "java.exe" else "java"

        val macBundle = File(installDir, "Contents/Home/bin/$exeName")
        if (macBundle.isFile) return macBundle

        val direct = File(installDir, "bin/$exeName")
        if (direct.isFile) return direct

        return installDir.listFiles()?.firstNotNullOfOrNull { child ->
            val macBundleChild = File(child, "Contents/Home/bin/$exeName")
            if (macBundleChild.isFile) return@firstNotNullOfOrNull macBundleChild

            val candidate = File(child, "bin/$exeName")
            if (candidate.isFile) candidate else null
        }
    }
}

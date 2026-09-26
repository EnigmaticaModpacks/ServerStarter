package atm.bloodworkxgaming.serverstarter

object OSUtil {
    val osName: String by lazy {
        try {
            System.getProperty("os.name")
        } catch (e: Exception) {
            ""
        }
    }

    val isLinux: Boolean = osName.toLowerCase().startsWith("linux")
    val isWindows: Boolean = osName.toLowerCase().startsWith("win")
    val isMac: Boolean = osName.toLowerCase().startsWith("mac")

    val osArch: String by lazy {
        try {
            System.getProperty("os.arch")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Maps the jvm's os.arch to the architecture identifiers used by the Adoptium API
     */
    val adoptiumArch: String by lazy {
        when (osArch.toLowerCase()) {
            "x86_64", "amd64" -> "x64"
            "x86", "i386", "i486", "i586", "i686" -> "x86-32"
            "aarch64", "arm64" -> "aarch64"
            "arm" -> "arm"
            "ppc64" -> "ppc64"
            "ppc64le" -> "ppc64le"
            "s390x" -> "s390x"
            else -> osArch
        }
    }

    /**
     * Maps the OS to the identifiers used by the Adoptium API
     */
    val adoptiumOs: String
        get() = when {
            isWindows -> "windows"
            isMac -> "mac"
            isLinux -> "linux"
            else -> "linux"
        }
}

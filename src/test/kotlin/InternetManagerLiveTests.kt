import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.config.InstallConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hits the real CurseForge CDN using the built-in api key. Not run as part of the
 * normal test suite (needs network + a valid key); run manually with:
 *   ./gradlew test --tests "InternetManagerLiveTests"
 */
class InternetManagerLiveTests {

    @Test
    fun `downloads real file from edge forgecdn host`() {
        assertDownloads("https://edge.forgecdn.net/files/2560/919/Pam's HarvestCraft 1.12.2u.jar")
    }

    @Test
    fun `downloads real file from mediafilez forgecdn host`() {
        assertDownloads("https://mediafilez.forgecdn.net/files/2560/919/Pam's HarvestCraft 1.12.2u.jar")
    }

    private fun assertDownloads(url: String) {
        val config = ConfigFile(install = InstallConfig())
        val manager = InternetManager(config)

        val dest = File.createTempFile("live-cdn-test", ".jar")
        dest.delete()

        manager.downloadToFile(url, dest)

        assertTrue("downloaded file should exist", dest.exists())
        assertTrue("downloaded file should be non-trivial size", dest.length() > 1_000_000)

        dest.delete()
    }
}

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.config.InstallConfig
import atm.bloodworkxgaming.serverstarter.isCurseforgeCdnHost
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class InternetManagerTests {

    @Test
    fun `recognizes curseforge cdn hosts`() {
        assertTrue(isCurseforgeCdnHost("edge.forgecdn.net"))
        assertTrue(isCurseforgeCdnHost("media.forgecdn.net"))
        assertTrue(isCurseforgeCdnHost("forgecdn.net"))
        assertTrue(isCurseforgeCdnHost("minecraft.curseforge.com"))
        assertTrue(isCurseforgeCdnHost("curseforge.com"))
    }

    @Test
    fun `rejects unrelated hosts`() {
        assertFalse(isCurseforgeCdnHost("example.com"))
        assertFalse(isCurseforgeCdnHost("notforgecdn.net.evil.com"))
        assertFalse(isCurseforgeCdnHost("files.minecraftforge.net"))
    }

    @Test
    fun `attaches api key header for cdn requests when key configured`() {
        val server = MockWebServer()
        server.start()

        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val loopback = InetAddress.getByName("127.0.0.1")
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                    "edge.forgecdn.net" -> listOf(loopback)
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }

            val config = ConfigFile(install = InstallConfig(curseforgeApiKey = "test-key"))
            val client = InternetManager(config).httpClient.newBuilder().dns(dns).build()

            val url = "http://edge.forgecdn.net:${server.port}/files/1/2/mod.jar".toHttpUrl()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().close()

            val seenRequest = server.takeRequest()
            assertEquals("test-key", seenRequest.getHeader("x-api-key"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `does not attach api key header for unrelated hosts`() {
        val server = MockWebServer()
        server.start()

        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val loopback = InetAddress.getByName("127.0.0.1")
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                    "files.minecraftforge.net" -> listOf(loopback)
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }

            val config = ConfigFile(install = InstallConfig(curseforgeApiKey = "test-key"))
            val client = InternetManager(config).httpClient.newBuilder().dns(dns).build()

            val url = "http://files.minecraftforge.net:${server.port}/installer.jar".toHttpUrl()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().close()

            val seenRequest = server.takeRequest()
            assertNull(seenRequest.getHeader("x-api-key"))
        } finally {
            server.shutdown()
        }
    }

    /**
     * Real redirect across two hosts via two local MockWebServers, with a fake Dns
     * resolving "edge.forgecdn.net" / "files.minecraftforge.net" to each server's
     * loopback address. Confirms the key is attached on the CDN hop and stripped
     * once OkHttp's real redirect-follow logic moves to the non-CDN host.
     */
    @Test
    fun `does not leak api key to redirect target on different host`() {
        val cdnServer = MockWebServer()
        val otherServer = MockWebServer()
        cdnServer.start()
        otherServer.start()

        try {
            cdnServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://files.minecraftforge.net:${otherServer.port}/installer.jar")
            )
            otherServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val loopback = InetAddress.getByName("127.0.0.1")
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                    "edge.forgecdn.net", "files.minecraftforge.net" -> listOf(loopback)
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }

            val config = ConfigFile(install = InstallConfig(curseforgeApiKey = "test-key"))
            val client = InternetManager(config).httpClient.newBuilder()
                .dns(dns)
                .build()

            // Rewrite the request to hit the CDN mock server's actual port while keeping
            // the CDN hostname (so the interceptor's host check and Dns fake both apply).
            val cdnUrl = "http://edge.forgecdn.net:${cdnServer.port}/files/1/2/mod.jar".toHttpUrl()
            val request = Request.Builder().url(cdnUrl).get().build()

            client.newCall(request).execute().close()

            val cdnRequest = cdnServer.takeRequest()
            assertEquals("test-key", cdnRequest.getHeader("x-api-key"))

            val redirectedRequest = otherServer.takeRequest()
            assertNull(redirectedRequest.getHeader("x-api-key"))
        } finally {
            cdnServer.shutdown()
            otherServer.shutdown()
        }
    }
}

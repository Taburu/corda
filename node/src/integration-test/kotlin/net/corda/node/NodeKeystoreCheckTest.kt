package net.corda.node

import net.corda.core.crypto.Crypto
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.cert
import net.corda.core.internal.div
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.config.configureDevKeyAndTrustStores
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.testing.ALICE_NAME
import net.corda.testing.driver.driver
import org.junit.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeKeystoreCheckTest {
    @Test
    fun `node should throw exception if cert path doesn't chain to the trust root`() {
        driver(startNodesInProcess = true) {
            // This will fail because there are no keystore configured.
            assertFailsWith(IllegalArgumentException::class) {
                startNode(customOverrides = mapOf("devMode" to false)).getOrThrow()
            }.apply {
                assertTrue(message?.startsWith("Identity certificate not found. ") ?: false)
            }

            // Create keystores
            val keystorePassword = "password"
            val config = object : SSLConfiguration {
                override val keyStorePassword: String = keystorePassword
                override val trustStorePassword: String = keystorePassword
                override val certificatesDirectory: Path = baseDirectory(ALICE_NAME) / "certificates"
            }
            config.configureDevKeyAndTrustStores(ALICE_NAME)

            // This should pass with correct keystore.
            val node = startNode(providedName = ALICE_NAME, customOverrides = mapOf("devMode" to false,
                    "keyStorePassword" to keystorePassword,
                    "trustStorePassword" to keystorePassword)).get()
            node.stop()

            // Fiddle with node keystore.
            config.openNodeKeyStore().update {
                // Self signed root
                val badRootKeyPair = Crypto.generateKeyPair()
                val badRoot = X509Utilities.createSelfSignedCACertificate(CordaX500Name("Bad Root", "Lodnon", "GB"), badRootKeyPair)
                val nodeCA = getCertificateAndKeyPair(X509Utilities.CORDA_CLIENT_CA)
                val badNodeCACert = X509Utilities.createCertificate(CertificateType.NODE_CA, badRoot, badRootKeyPair, ALICE_NAME, nodeCA.keyPair.public)
                setPrivateKey(X509Utilities.CORDA_CLIENT_CA, nodeCA.keyPair.private, listOf(badNodeCACert.cert, badRoot.cert))
            }

            assertFailsWith(IllegalArgumentException::class) {
                startNode(providedName = ALICE_NAME, customOverrides = mapOf("devMode" to false)).getOrThrow()
            }.apply {
                assertEquals("Client CA certificate must chain to the trusted root.", message)
            }
        }
    }
}

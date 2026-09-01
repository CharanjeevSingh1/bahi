package dev.charanjeev.bahi.core.sync.drivetest

import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.sync.SyncTransportContractTest
import dev.charanjeev.bahi.core.sync.drive.DriveApi
import dev.charanjeev.bahi.core.sync.drive.DriveTransport
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import java.io.File
import java.util.Properties
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before

/**
 * `DriveTransportContractTest : SyncTransportContractTest` -- yes, this
 * really does run every guarantee `InMemoryTransportTest` checks against a
 * real, already-authorized Drive account (docs/sync-design.md §10.5). Never
 * run by CI: see `core/sync/build.gradle.kts`'s `driveTest` task and the
 * `sourceSets.getByName("test")` block above it for why the sources aren't
 * even compiled without `drive-test.properties` present.
 *
 * Run by hand: create `core/sync/drive-test.properties` (gitignored) --
 *
 * ```
 * clientId=<OAuth client id for a throwaway account, drive.appdata scope>
 * clientSecret=<its client secret>
 * refreshToken=<a refresh token already consented for that account/scope>
 * ```
 *
 * -- then `./gradlew :core:sync:driveTest`. A dedicated file rather than
 * reusing `local.properties` (which AGP itself already writes to), matching
 * the existing `sync.properties` precedent of one small file per concern.
 *
 * [wipeAccount] runs before every test: a real account is persistent across
 * runs, and `SyncTransportContractTest`'s methods assume a transport with no
 * prior history ("pull returns nothing from a transport nothing has been
 * pushed to"), which only a genuinely empty `appDataFolder` can honestly
 * satisfy on a second or later run.
 */
class DriveTransportContractTest : SyncTransportContractTest() {

    private val properties = Properties().apply { File("drive-test.properties").inputStream().use(::load) }
    private val callFactory = OkHttpClient()
    private val authorization = RefreshTokenDriveAuthorization(
        clientId = properties.getProperty("clientId") ?: error("drive-test.properties is missing clientId"),
        clientSecret = properties.getProperty("clientSecret") ?: error("drive-test.properties is missing clientSecret"),
        refreshToken = properties.getProperty("refreshToken") ?: error("drive-test.properties is missing refreshToken"),
        callFactory = callFactory,
    )

    @Before
    fun wipeAccount(): Unit = runBlocking {
        val api = DriveApi(callFactory) { (authorization.currentAccessToken() as AuthorizationOutcome.Authorized).accessToken }
        api.listAll().forEach { api.delete(it.id) }
    }

    override fun createTransport(): SyncTransport = DriveTransport(
        driveAuthorization = authorization,
        keyStore = FixedKeyStore,
        callFactory = callFactory,
        ioDispatcher = Dispatchers.IO,
    )

    /** A fixed, already-set-up key -- what this test suite is proving is the transport, not encryption, which OpBatchCipherTest already covers. */
    private object FixedKeyStore : SyncEncryptionKeyStore {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override val isSetUp = flowOf(true)
        override suspend fun setUp(passphrase: CharArray): String = error("not exercised by DriveTransportContractTest")
        override suspend fun pair(passphrase: CharArray, salt: ByteArray): String = error("not exercised by DriveTransportContractTest")
        override suspend fun cachedKey(): SecretKey = key
        override suspend fun pairingCode(): String? = error("not exercised by DriveTransportContractTest")
    }
}

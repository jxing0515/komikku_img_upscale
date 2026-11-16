package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.await
import exh.log.xLogD
import exh.log.xLogE
import exh.log.xLogI
import exh.log.xLogW
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.http.HttpStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class WebDavSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {

    private class WebDavException(message: String?) : Exception(message)

    private val url: String = syncPreferences.webDavUrl().get().trim()
    private val folder: String = syncPreferences.webDavFolder().get().trim('/')
    private val username: String = syncPreferences.webDavUsername().get().trim()
    private val password: String = syncPreferences.webDavPassword().get()
    private val credentials: String = Credentials.basic(username, password)

    private fun buildWebDavFileUrl(fileName: String = "backup.proto"): String {
        val cleanBase = url.trimEnd('/')
        return if (folder.isNotEmpty()) "$cleanBase/$folder/$fileName" else "$cleanBase/$fileName"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private fun validateSettings(): Boolean {
        if (url.isEmpty() || !url.startsWith("http")) {
            notifier.showSyncError("Invalid WebDAV URL. Please check your settings.")
            return false
        }
        if (username.isBlank() || password.isBlank()) {
            notifier.showSyncError("Username or password cannot be empty.")
            return false
        }
        return true
    }

    private suspend fun ensureFolderExists() {
        if (folder.isEmpty()) return
        if (!validateSettings()) return
        val baseUrl = url.trimEnd('/')
        val folderParts = folder.split('/').filter { it.isNotEmpty() }
        if (folderParts.isEmpty()) return

        var currentPath = baseUrl
        for (part in folderParts) {
            currentPath = "$currentPath/$part"
            if (!createSingleFolder(currentPath)) {
                throw WebDavException("Failed to create folder: $currentPath")
            }
        }
    }

    private suspend fun createSingleFolder(folderUrl: String): Boolean {
        val request = Request.Builder()
            .url(folderUrl)
            .method("MKCOL", null)
            .header("Authorization", credentials)
            .header("Content-Length", "0")
            .build()

        val response = client.newCall(request).await()
        val success = response.isSuccessful || response.code == 405 || response.code == 409
        response.close()
        return success
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        try {
            // KMK --> Inside the try: a failing MKCOL used to escape this handler and skip the
            // "no result" reporting the caller relies on.
            ensureFolderExists()
            // KMK <--
            val (remoteData, etag) = pullSyncData()

            // KMK --> 304 means the remote file is unchanged, NOT that it is missing: treating it
            // as "no remote data" overwrote the server backup with the local one. Skipping the
            // upload unconditionally is just as wrong, because after the first successful PUT the
            // remote never changes again, so every later local edit would stay on the device.
            // Decide by payload instead: if what the remote holds is what we would upload there is
            // genuinely nothing to do, otherwise push it back under If-Match so that a concurrent
            // change from another device still fails with 412 instead of being overwritten.
            if (remoteData == null && etag.isNotEmpty()) {
                val localBytes = syncData.backup?.let { encodeBackup(it) }
                    ?: throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
                val localHash = sha256(localBytes)
                val knownHash = syncPreferences.lastSyncContentHash().get()
                val remoteHoldsLocalPayload = when {
                    // Nothing but a conditional GET happened, so this is the cheap normal path.
                    knownHash.isNotEmpty() -> knownHash == localHash
                    // No hash recorded yet (first sync after upgrading): read the remote payload
                    // once, so the first 304 does not turn into a pointless upload.
                    else -> fetchRemoteBackupBytes()?.let { sha256(it) == localHash } ?: false
                }
                if (remoteHoldsLocalPayload) {
                    syncPreferences.lastSyncContentHash().set(localHash)
                    skippedNoChange = true
                    xLogD("Remote and local payload are identical (304), nothing to upload")
                    return syncData.backup
                }
                xLogD("Remote unchanged (304) but the local payload differs, updating with ETag(%s)", etag)
                pushSyncData(syncData, etag, localBytes)
                return syncData.backup
            }
            // KMK <--

            val finalSyncData = if (remoteData != null) {
                assert(etag.isNotEmpty()) { "ETag should never be empty if remote data is not null" }
                xLogD("Try update remote data with ETag(%s)", etag)
                mergeSyncData(syncData, remoteData)
            } else {
                xLogD("Try overwrite remote data with ETag(%s)", etag)
                syncData
            }

            val payload = finalSyncData.backup?.let { encodeBackup(it) }
                ?: throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            pushSyncData(finalSyncData, etag, payload)
            return finalSyncData.backup
        } catch (e: Exception) {
            xLogE("WebDAV sync error:", e)
            notifier.showSyncError(e.message)
            return null
        }
    }

    // KMK -->
    private fun encodeBackup(backup: Backup): ByteArray {
        val byteArray = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        return byteArray
    }

    private fun sha256(bytes: ByteArray): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads the remote payload without any conditional header. Only used when a 304 arrives while
     * no content hash is recorded yet; it never writes to the server.
     */
    private suspend fun fetchRemoteBackupBytes(): ByteArray? {
        if (!validateSettings()) return null
        val headers = Headers.Builder().add("Authorization", credentials).build()
        val response = client.newCall(GET(buildWebDavFileUrl(), headers = headers)).await()
        return when {
            response.code == HttpStatus.SC_NOT_FOUND -> null
            response.isSuccessful -> response.body.byteStream().use { it.readBytes() }
            else -> null
        }
    }
    // KMK <--

    private suspend fun pullSyncData(): Pair<SyncData?, String> {
        if (!validateSettings()) return Pair(null, "")

        // KMK --> Entity-tags must stay quoted (RFC 7232). Unquoted If-Match/If-None-Match makes
        // nginx answer 412/200 instead of 412/304, which broke every sync once a remote file existed.
        // Values stored by older builds were unquoted, so they are dropped once here.
        val lastETag = syncPreferences.lastSyncEtag().get().takeIf { it.startsWith("\"") }.orEmpty()
        // KMK <--
        val headersBuilder = Headers.Builder().add("Authorization", credentials)
        if (lastETag.isNotEmpty()) headersBuilder.add("If-None-Match", lastETag)

        val requestUrl = buildWebDavFileUrl()
        val request = GET(requestUrl, headers = headersBuilder.build())
        val response = client.newCall(request).await()

        return when (response.code) {
            HttpStatus.SC_NOT_MODIFIED -> Pair(null, lastETag)
            HttpStatus.SC_NOT_FOUND -> Pair(null, "")
            else -> {
                if (response.isSuccessful) {
                    val newETag = response.headers["ETag"] ?: ""
                    val bytes = response.body.byteStream().use { it.readBytes() }
                    try {
                        val backup = protoBuf.decodeFromByteArray(Backup.serializer(), bytes)
                        Pair(SyncData(backup = backup), newETag)
                    } catch (e: Exception) {
                        xLogE("Invalid backup format:", e)
                        Pair(null, "")
                    }
                } else {
                    val body = response.body.string()
                    throw WebDavException("Failed to download: $body")
                }
            }
        }
    }

    private suspend fun pushSyncData(syncData: SyncData, eTag: String, byteArray: ByteArray) {
        val backup = syncData.backup ?: return

        if (!validateSettings()) return

        if (byteArray.isEmpty()) throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))

        val body = byteArray.toRequestBody("application/octet-stream".toMediaType())
        val headersBuilder = Headers.Builder().add("Authorization", credentials)
        if (eTag.isNotEmpty()) headersBuilder.add("If-Match", eTag)

        val requestUrl = buildWebDavFileUrl()
        val request = PUT(requestUrl, headers = headersBuilder.build(), body = body)
        val response = client.newCall(request).await()

        when {
            response.isSuccessful -> {
                // KMK --> Keep the quotes, see pullSyncData().
                val newETag = response.headers["ETag"] ?: ""
                // KMK <--
                if (newETag.isNotEmpty()) syncPreferences.lastSyncEtag().set(newETag)
                // KMK --> The remote now holds exactly these bytes; that is what a later 304 is
                // compared against.
                syncPreferences.lastSyncContentHash().set(sha256(byteArray))
                // KMK <--
                xLogI("WebDAV sync completed")
            }
            response.code == HttpStatus.SC_PRECONDITION_FAILED -> {
                val message = "Sync conflict detected. Another device may have updated the remote backup. " +
                    "Please retry syncing or check your WebDAV folder."
                xLogW(message)
                // KMK --> Must fail loudly: returning normally made the caller report success over
                // this error notification, stamp the sync time, and leave the remote file untouched.
                throw WebDavException(message)
                // KMK <--
            }
            else -> {
                val bodyStr = response.body.string()
                throw WebDavException("Upload failed: $bodyStr")
            }
        }
    }
}

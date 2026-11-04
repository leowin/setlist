package at.zentriert.setlist.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

/**
 * Band server. Environment:
 *  PORT (8080) · STORE_DIR (./data; on Cloud Run the mounted bucket) · ROOM_TTL_DAYS (90) ·
 *  ANDROID_PACKAGE / ANDROID_SHA256 (comma-separated fingerprints) / IOS_APP_ID for the app-link files.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val store = FileStore(File(System.getenv("STORE_DIR") ?: "data"))
    val config = ServerConfig(
        roomTtlDays = System.getenv("ROOM_TTL_DAYS")?.toIntOrNull() ?: 90,
        androidPackage = System.getenv("ANDROID_PACKAGE").orEmpty(),
        androidSha256 = System.getenv("ANDROID_SHA256").orEmpty(),
        iosAppId = System.getenv("IOS_APP_ID").orEmpty(),
    )
    embeddedServer(CIO, port = port, host = "0.0.0.0") { bandRelay(RoomStore(), store, config) }.start(wait = true)
}

package at.zentriert.setlist.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File

/** Band relay server. PORT (default 8080) and STORE_DIR (optional, keeps each room's last setlist). */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val store = System.getenv("STORE_DIR")?.let { File(it) }
    embeddedServer(CIO, port = port, host = "0.0.0.0") { bandRelay(RoomStore(store)) }.start(wait = true)
}

fun Application.module() = bandRelay(RoomStore(null))

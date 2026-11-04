package at.zentriert.setlist

import at.zentriert.setlist.band.SERVICE_TYPE
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject

/** Bonjour via NSNetService (needs NSLocalNetworkUsageDescription + NSBonjourServices "_setlist._tcp" in Info.plist). */
class BonjourDiscovery : Discovery {
    private var service: NSNetService? = null
    private var browser: NSNetServiceBrowser? = null
    private val pending = mutableListOf<NSNetService>()
    private var browserDelegate: NSObject? = null

    override fun advertise(name: String, port: Int) {
        stopAdvertising()
        service = NSNetService(domain = "local.", type = "$SERVICE_TYPE.", name = name, port = port).also { it.publish() }
    }

    override fun stopAdvertising() { service?.stop(); service = null }

    override fun discover(onFound: (String, String, Int) -> Unit, onLost: (String) -> Unit) {
        stopDiscovery()
        val delegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
                pending += didFindService
                didFindService.delegate = this
                didFindService.resolveWithTimeout(5.0)
            }
            override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
                onLost(didRemoveService.name)
            }
            override fun netServiceDidResolveAddress(sender: NSNetService) {
                val host = sender.hostName ?: return
                onFound(sender.name, host, sender.port.toInt())
            }
        }
        browserDelegate = delegate
        browser = NSNetServiceBrowser().also {
            it.delegate = delegate
            it.searchForServicesOfType("$SERVICE_TYPE.", inDomain = "local.")
        }
    }

    override fun stopDiscovery() { browser?.stop(); browser = null; pending.clear() }
}

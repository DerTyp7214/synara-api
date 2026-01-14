package dev.dertyp.plugins

import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

private const val DEFAULT_HOST = "0.0.0.0"
private const val DEFAULT_PORT = 8080
private const val KTOR_HOST_CONFIG_PATH = "ktor.deployment.host"
private const val KTOR_PORT_CONFIG_PATH = "ktor.deployment.port"

class JmDNSConfig {
    var serviceName: String = "KtorServerService"
    var serviceType: String = "_http._tcp.local."
    var properties: Map<String, String> = emptyMap()
}

val JmDNSPlugin = createApplicationPlugin(
    name = "JmDNSPlugin",
    createConfiguration = ::JmDNSConfig
) {
    val config = pluginConfig
    var jmDNS: JmDNS? = null
    var serviceInfo: ServiceInfo? = null

    fun getConfiguredPort(application: Application): Int {
        return application.environment.config
            .propertyOrNull(KTOR_PORT_CONFIG_PATH)
            ?.getString()
            ?.toIntOrNull()
            ?: DEFAULT_PORT
    }

    fun getConfiguredHost(application: Application): String {
        return application.environment.config
            .propertyOrNull(KTOR_HOST_CONFIG_PATH)
            ?.getString()
            ?: DEFAULT_HOST
    }

    on(MonitoringEvent(ApplicationStarted)) { application ->
        try {
            val host = getConfiguredHost(application)
            val port = getConfiguredPort(application)

            val localAddress = InetAddress.getLocalHost()

            jmDNS = JmDNS.create(localAddress, localAddress.hostName)

            serviceInfo = ServiceInfo.create(
                config.serviceType,
                config.serviceName,
                port,
                0, 0,
                config.properties
            )

            jmDNS?.registerService(serviceInfo)
            application.log.info("JmDNS Service Registered: ${config.serviceName} advertised at $host:$port (via ${localAddress.hostName}.local)")

        } catch (e: Exception) {
            application.log.error("Failed to start JmDNS service registration", e)
            application.log.info("Server still starts normally.")
        }
    }

    on(MonitoringEvent(ApplicationStopped)) { application ->
        jmDNS?.let {
            it.unregisterAllServices()
            it.close()
            application.log.info("JmDNS Service Unregistered and closed.")
        }
        jmDNS = null
    }
}
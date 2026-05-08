package com.zcam.service.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.w
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidRuntimeEnvironmentMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : RuntimeEnvironmentMonitor {

    private val stateMutex = Mutex()

    private val _thermalBand = MutableStateFlow(ThermalBand.NOMINAL)
    override val thermalBand: StateFlow<ThermalBand> = _thermalBand.asStateFlow()

    private val _networkConnectivity = MutableStateFlow(NetworkConnectivity(connected = true))
    override val networkConnectivity: StateFlow<NetworkConnectivity> = _networkConnectivity.asStateFlow()

    private var started = false
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override suspend fun start() = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (started) return@withLock
            started = true
            refreshStateLocked()
            registerThermalListenerLocked()
            registerNetworkCallbackLocked()
        }
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (!started) return@withLock
            started = false
            unregisterThermalListenerLocked()
            unregisterNetworkCallbackLocked()
        }
    }

    private fun refreshStateLocked() {
        _thermalBand.value = readThermalBand()
        _networkConnectivity.value = readNetworkConnectivity()
    }

    private fun registerThermalListenerLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            _thermalBand.value = status.toThermalBand()
        }
        runCatching {
            powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
            thermalListener = listener
        }.onFailure { error ->
            logger.w(LogEventId.COMPONENT_FAILED, "Thermal listener registration failed: ${error.message}")
        }
    }

    private fun unregisterThermalListenerLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val listener = thermalListener ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            powerManager.removeThermalStatusListener(listener)
        }
        thermalListener = null
    }

    private fun registerNetworkCallbackLocked() {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _networkConnectivity.value = readNetworkConnectivity()
            }

            override fun onLost(network: Network) {
                _networkConnectivity.value = readNetworkConnectivity()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                _networkConnectivity.value = readNetworkConnectivity()
            }
        }

        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { error ->
            logger.w(LogEventId.COMPONENT_FAILED, "Network callback registration failed: ${error.message}")
        }
    }

    private fun unregisterNetworkCallbackLocked() {
        val callback = networkCallback ?: return
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            connectivityManager.unregisterNetworkCallback(callback)
        }
        networkCallback = null
    }

    private fun readThermalBand(): ThermalBand {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalBand.NOMINAL
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return ThermalBand.NOMINAL
        return powerManager.currentThermalStatus.toThermalBand()
    }

    private fun readNetworkConnectivity(): NetworkConnectivity {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkConnectivity(connected = false, transport = "unavailable")
        val activeNetwork = connectivityManager.activeNetwork
            ?: return NetworkConnectivity(connected = false, transport = "none")
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkConnectivity(connected = false, transport = "unknown")

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
        return NetworkConnectivity(
            connected = hasInternet,
            transport = transport
        )
    }

    private fun Int.toThermalBand(): ThermalBand {
        return when (this) {
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalBand.THROTTLED

            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalBand.CRITICAL

            else -> ThermalBand.NOMINAL
        }
    }
}

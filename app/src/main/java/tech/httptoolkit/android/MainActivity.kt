package tech.httptoolkit.android

import android.app.AlertDialog
import android.content.*
import android.net.Uri
import android.net.VpnService
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.security.KeyChain
import android.security.KeyChain.EXTRA_CERTIFICATE
import android.security.KeyChain.EXTRA_NAME
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.lang.IllegalArgumentException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.cert.CertificateFactory
import java.security.KeyStore
import java.net.ConnectException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate


const val START_VPN_REQUEST = 123
const val INSTALL_CERT_REQUEST = 456
const val SCAN_REQUEST = 789

enum class MainState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

private fun getCertificateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(cert.publicKey.encoded)
    val fingerprint = md.digest()
    return Base64.encodeToString(
        fingerprint,
        Base64.URL_SAFE or Base64.NO_WRAP // We use -_ rather than +/ for URLs
    )
}

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private val TAG = MainActivity::class.simpleName
    private var app: HttpToolkitApplication? = null

    private var localBroadcastManager: LocalBroadcastManager? = null
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VPN_STARTED_BROADCAST) {
                mainState = MainState.CONNECTED
                currentProxyConfig = intent.getParcelableExtra(PROXY_CONFIG_EXTRA)
                updateVpnUiState()
            } else if (intent.action == VPN_STOPPED_BROADCAST) {
                mainState = MainState.DISCONNECTED
                currentProxyConfig = null
                updateVpnUiState()
            }
        }
    }

    private var mainState: MainState = if (isVpnActive()) MainState.CONNECTED else MainState.DISCONNECTED
    // If connected/connecting, the proxy we're connected/trying to connect to. Otherwise null.
    private var currentProxyConfig: ProxyConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        updateVpnUiState()

        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(VPN_STARTED_BROADCAST)
            addAction(VPN_STOPPED_BROADCAST)
        })
        app = this.application as HttpToolkitApplication

        // TODO: Listen for referrer data from app installs too

        // Check if first run
        // Check if packageManager.getPackageInfo(packageName, 0).firstInstallTime is recent
        // ^ TODO: Test if this is reset on reinstall
        // If it's recent (1 hour), get the referrer
        // If set & valid (android...), use it.

        if (intent != null && intent.action == Intent.ACTION_VIEW) {

            // TODO: Save global state, remembering the last proxy we connected to.
            // Only show this message if there has been at least one.

            // If we were started from an intent (e.g. another barcode scanner/link), confirm
            // interception, then start the VPN.
            AlertDialog.Builder(this)
                .setTitle("Enable Interception")
                .setMessage(
                    "Do you want to share all this device's HTTP traffic with HTTP Toolkit?" +
                    "\n\n" +
                    "Only accept this if you trust the source."
                )
                // Specifying a listener allows you to take an action before dismissing the dialog.
                // The dialog is automatically dismissed when a dialog button is clicked.
                .setPositiveButton("Enable"){ _, _ ->
                    launch { connectToVpnFromUrl(intent.data!!) }
                 }
                .setNegativeButton("Cancel", null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        app!!.trackScreen("Main")
    }

    override fun onPause() {
        super.onPause()
        app!!.clearScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
    }

    private fun updateVpnUiState() {
    }

    fun scanCode(@Suppress("UNUSED_PARAMETER") view: View) {
        app!!.trackEvent("Button", "scan-code")
        startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
    }

    fun connectToVpn(config: ProxyConfig) {
        Log.i(TAG, "Connect to VPN")

        this.mainState = MainState.CONNECTING
        this.currentProxyConfig = config
        updateVpnUiState()

        app!!.trackEvent("Button", "start-vpn")
        val vpnIntent = VpnService.prepare(this)
        Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")

        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, START_VPN_REQUEST)
        } else {
            onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
        }

    }

    fun disconnect() {
        this.mainState = MainState.DISCONNECTING
        this.currentProxyConfig = null
        updateVpnUiState()

        app!!.trackEvent("Button", "stop-vpn")
        startService(Intent(this, ProxyVpnService::class.java).apply {
            action = STOP_VPN_ACTION
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.i(TAG, "onActivityResult")
        Log.i(TAG, when (requestCode) {
            START_VPN_REQUEST -> "start-vpn"
            INSTALL_CERT_REQUEST -> "install-cert"
            SCAN_REQUEST -> "scan-request"
            else -> requestCode.toString()
        })
        Log.i(TAG, if (resultCode == RESULT_OK) "ok" else resultCode.toString())

        if (resultCode != RESULT_OK) return

        if (requestCode == START_VPN_REQUEST && currentProxyConfig != null) {
            Log.i(TAG, "Installing cert")
            ensureCertificateTrusted(currentProxyConfig!!)
        } else if (requestCode == INSTALL_CERT_REQUEST) {
            Log.i(TAG, "Starting VPN")
            startService(Intent(this, ProxyVpnService::class.java).apply {
                action = START_VPN_ACTION
                putExtra(PROXY_CONFIG_EXTRA, currentProxyConfig)
            })
        } else if (requestCode == SCAN_REQUEST && data != null) {
            val url = data.getStringExtra(SCANNED_URL_EXTRA)
            launch { connectToVpnFromUrl(Uri.parse(url)) }
        }
    }

    private suspend fun connectToVpnFromUrl(uri: Uri) {
        withContext(Dispatchers.IO) {
            val data = uri.getQueryParameter("data")
            val proxyInfo = Klaxon().parse<ProxyInfo>(data)
                // TODO: Wrap this all in a try, and properly handle failures
                ?: throw IllegalArgumentException("Invalid proxy JSON: $data")

            val config = getProxyConfig(proxyInfo)
            connectToVpn(config)
        }
    }

    private suspend fun getProxyConfig(proxyInfo: ProxyInfo): ProxyConfig {
        return withContext(Dispatchers.IO) {
            Log.v(TAG, "Validating proxy info $proxyInfo")

            val request = Request.Builder()
                .url("http://android.httptoolkit.tech/certificate")
                .build()

            val certFactory = CertificateFactory.getInstance("X.509")

            var validatedIp: String? = null
            var validatedCertificate: Certificate? = null

            for (possibleIp in proxyInfo.ips) {
                val httpClient = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(possibleIp, proxyInfo.port)))
                    .build()

                try {
                    val certString = httpClient.newCall(request).execute().use { response ->
                        if (response.code != 200) {
                            throw ConnectException("Proxy responded with non-200: ${response.code}")
                        }
                        response.body!!.string()
                    }
                    val foundCert = certFactory.generateCertificate(
                        ByteArrayInputStream(certString.toByteArray(Charsets.UTF_8))
                    ) as X509Certificate
                    val foundCertHash = getCertificateFingerprint(foundCert)

                    if (proxyInfo.certificateHash == foundCertHash) {
                        validatedCertificate = foundCert
                        validatedIp = possibleIp
                        break
                    } else {
                        Log.w(TAG, "Proxy returned mismatched certificate: '${
                            proxyInfo.certificateHash
                        }' != '$foundCertHash' ($possibleIp) ${
                            proxyInfo.certificateHash == foundCertHash
                        } ${
                            proxyInfo.certificateHash.equals(foundCertHash)
                        }")
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "Error for ip $possibleIp: $e")
                }
            }

            if (validatedIp != null && validatedCertificate != null) {
                ProxyConfig(
                    validatedIp,
                    proxyInfo.port,
                    validatedCertificate
                )
            } else {
                throw ConnectException("Could not connect to proxy")
            }
        }
    }

    private fun isVpnConfigured(): Boolean {
        return VpnService.prepare(this) == null
    }

    private fun isCertTrusted(proxyConfig: ProxyConfig): Boolean {
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)

        val certificateAlias = keyStore.getCertificateAlias(proxyConfig.certificate)
        return certificateAlias != null
    }

    private fun ensureCertificateTrusted(proxyConfig: ProxyConfig) {
        if (isCertTrusted(proxyConfig)) {
            app!!.trackEvent("Setup", "installing-cert")
            Log.i(TAG, "Certificate not trusted, prompting to install")
            val certInstallIntent = KeyChain.createInstallIntent()
            certInstallIntent.putExtra(EXTRA_NAME, "HTTP Toolkit CA")
            certInstallIntent.putExtra(EXTRA_CERTIFICATE, proxyConfig.certificate.encoded)
            startActivityForResult(certInstallIntent, INSTALL_CERT_REQUEST)
        } else {
            app!!.trackEvent("Setup", "existing-cert")
            Log.i(TAG, "Certificate already trusted, continuing")
            onActivityResult(INSTALL_CERT_REQUEST, RESULT_OK, null)
        }
    }

}

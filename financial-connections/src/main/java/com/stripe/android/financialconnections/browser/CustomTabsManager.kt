package com.stripe.android.financialconnections.browser

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import com.stripe.android.core.Logger

/**
 * Manages the connection to the CustomTabsService.
 *
 */
internal class CustomTabsManager(
    private val logger: Logger
) {

    private var client: CustomTabsClient? = null
    private var connection: CustomTabsServiceConnection? = null
    private var session: CustomTabsSession? = null

    /**
     * Binds the Activity to the CustomTabsService.
     */
    fun onStart(context: Context) {
        if (client != null) return
        connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(
                name: ComponentName,
                client: CustomTabsClient
            ) {
                log("SERVICE CONNECTED")
                this@CustomTabsManager.client = client
                this@CustomTabsManager.client?.warmup(0)
                session = this@CustomTabsManager.client?.newSession(CustomTabsCallback())
            }

            override fun onServiceDisconnected(name: ComponentName) {
                log("SERVICE DISCONNECTED")
                client = null
                session = null
            }
        }
        bindCustomTabService(context)
    }

    /**
     * Unbinds the Activity from the CustomTabsService.
     */
    fun onStop(context: Context) {
        if (connection == null) return
        connection?.let {
            log("OnStop: unbinding service")
            runCatching { context.unbindService(it) }.onFailure {
                log("OnStop: couldn't unbind, ${it.stackTrace}")
            }
        }
        client = null
        connection = null
        session = null
    }

    /**
     * Creates or retrieves an exiting CustomTabsSession.
     *
     * @return a CustomTabsSession.
     */
    private fun getSession(): CustomTabsSession? {
        when {
            client == null -> session = null
            session == null -> session = client?.newSession(null)
        }
        return session
    }

    /**
     * Opens the URL on a Custom Tab if possible. Otherwise fallsback to opening it on a WebView.
     *
     * @param activity The host activity.
     * @param uri the Uri to be opened.
     * @param fallback a CustomTabFallback to be used if Custom Tabs is not available.
     */
    fun openCustomTab(
        activity: Activity,
        uri: Uri,
        fallback: (Uri) -> Unit = { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    ) {
        val packageName: String? = getCustomTabPackage(activity)
        val customTabsIntent = buildCustomTabsIntent()

        // If we cant find a package name no browser that supports Custom Tabs is installed.
        if (packageName == null) {
            fallback.invoke(uri)
        } else {
            customTabsIntent.intent.setPackage(packageName)
            customTabsIntent.launchUrl(activity, uri)
        }
    }

    private fun buildCustomTabsIntent() = CustomTabsIntent.Builder(getSession())
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .build()

    /**
     * Warms up the browser for a given URL, so that it loads faster when launched.
     */

    fun mayLaunchUrl(url: String): Boolean {
        return client
            ?.let { getSession() }
            ?.mayLaunchUrl(Uri.parse(url), null, null)?.also {
                log("URL prefetch: $url, Result: $it")
            } ?: run {
            log("URL not prefetched, ${if (client == null) "null client" else "null session"}")
            false
        }
    }

    private fun bindCustomTabService(context: Context) {
        // Check for an existing connection
        if (client != null) {
            log("Bind unnecessary: Client already exists")
            return
        }

        val packageName = getCustomTabPackage(context)
        if (packageName == null) {
            log("Unable to bind: No Custom Tabs compatible browser found")
            return
        }
        connection?.let {
            if (CustomTabsClient.bindCustomTabsService(context, packageName, it)) {
                log("Bind successful")
            } else {
                log("Bind failed")
            }
        }
    }

    /**
     * Get the package name of the preferred browser to use that supports Custom Tabs.
     *
     * @return the package name of the preferred browser to use that supports Custom Tabs, or null
     * if no browser that supports Custom Tabs is installed.
     */
    private fun getCustomTabPackage(context: Context): String? {
        val browserPackage = CustomTabsClient.getPackageName(
            context,
            emptyList(),
            false
        )
        log("Browser package: $browserPackage")
        return browserPackage
    }

    fun log(message: String) {
        logger.debug("CustomTabsManager: $message")
    }
}

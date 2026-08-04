package com.mailsearch.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mailsearch.app.auth.SignInState
import com.mailsearch.app.ui.MessageScreen
import com.mailsearch.app.ui.SearchScreen
import com.mailsearch.app.ui.SetupScreen
import com.mailsearch.app.ui.SignInScreen

/**
 * The only activity.
 *
 * It also receives the OAuth redirect: the manifest registers the
 * `mailsearch://auth` scheme against this activity in singleTop mode, so the
 * browser handing control back arrives as [onNewIntent] rather than as a second
 * copy of the app.
 */
class MainActivity : ComponentActivity() {
    private var redirect by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        redirect = intent?.data
        setContent { MailSearchApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        redirect = intent.data
    }

    @Composable
    private fun MailSearchApp(model: MailSearchViewModel = viewModel()) {
        val state by model.uiState.collectAsState()
        var forceSetup by remember { mutableStateOf(false) }

        // Hand the browser's answer to the view model exactly once. This has to
        // be a side effect rather than a call in the composition body, which
        // Compose is free to run again at any time.
        val pending = redirect
        LaunchedEffect(pending) {
            if (pending != null) {
                redirect = null
                model.completeSignIn(
                    code = pending.getQueryParameter("code"),
                    returnedState = pending.getQueryParameter("state"),
                    error = pending.getQueryParameter("error"),
                    description = pending.getQueryParameter("error_description"),
                )
            }
        }

        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF9CC0FF))
            } else {
                lightColorScheme(primary = Color(0xFF2F6DF6))
            },
        ) {
            Surface(Modifier, color = MaterialTheme.colorScheme.background) {
                val signIn = state.signIn
                when {
                    forceSetup || signIn is SignInState.NeedsSetup -> SetupScreen(
                        initialClientId = model.clientId,
                        initialTenant = model.tenant,
                        onSave = { clientId, tenant ->
                            model.saveSetup(clientId, tenant)
                            forceSetup = false
                        },
                    )

                    signIn is SignInState.SignedIn -> {
                        val opened = state.opened
                        BackHandler(enabled = opened != null) { model.closeMessage() }
                        if (opened == null) {
                            SearchScreen(
                                state = state,
                                account = signIn.account,
                                onQueryChanged = model::onQueryChanged,
                                onSubmit = model::searchNow,
                                onOpen = model::open,
                                onLoadMore = model::loadMore,
                                onRecent = model::useRecent,
                                onSignOut = model::signOut,
                            )
                        } else {
                            MessageScreen(
                                message = opened,
                                body = state.openedBody,
                                loading = state.bodyLoading,
                                terms = state.terms,
                                onBack = model::closeMessage,
                                onOpenInOutlook = ::openExternally,
                            )
                        }
                    }

                    else -> SignInScreen(
                        account = (signIn as? SignInState.SignedIn)?.account.orEmpty(),
                        error = (signIn as? SignInState.Failed)?.message.orEmpty(),
                        busy = signIn is SignInState.InProgress,
                        onSignIn = { model.beginSignIn()?.let(::openInBrowser) },
                        onSetup = { forceSetup = true },
                    )
                }
            }
        }
    }

    /**
     * Sign-in opens in a Custom Tab — the real browser, with its address bar and
     * saved passwords — so credentials are typed into Microsoft's own page and
     * never into anything this app draws.
     */
    private fun openInBrowser(url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            openExternally(url)
        }
    }

    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser is installed to open that.", Toast.LENGTH_LONG).show()
        }
    }
}

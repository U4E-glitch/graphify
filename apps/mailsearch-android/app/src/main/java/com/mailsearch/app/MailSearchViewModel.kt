package com.mailsearch.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mailsearch.app.auth.AuthClient
import com.mailsearch.app.auth.AuthException
import com.mailsearch.app.auth.Pkce
import com.mailsearch.app.auth.SignInState
import com.mailsearch.app.auth.Tokens
import com.mailsearch.app.graph.GraphClient
import com.mailsearch.app.graph.GraphException
import com.mailsearch.app.graph.Message
import com.mailsearch.app.search.GraphQuery
import com.mailsearch.app.search.QueryMode
import com.mailsearch.app.search.QueryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val signIn: SignInState = SignInState.SignedOut,
    val query: String = "",
    val results: List<Message> = emptyList(),
    val terms: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String = "",
    val nextLink: String = "",
    val opened: Message? = null,
    val openedBody: String = "",
    val bodyLoading: Boolean = false,
    val recent: List<String> = emptyList(),
) {
    val hasMore: Boolean get() = nextLink.isNotEmpty()
}

/**
 * Holds the app's state and does the talking to Microsoft.
 *
 * Every network call runs on [Dispatchers.IO]; the state flow is only ever
 * touched from the main thread by the collectors in the UI.
 */
class MailSearchViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)
    private val state = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = state.asStateFlow()

    private var tokens: Tokens? = storage.loadTokens()
    private var pending: Pkce? = null
    private var pendingState: String = ""
    private var searchJob: Job? = null

    private val auth get() = AuthClient(storage.clientId, storage.tenant)
    private val graph = GraphClient { requireAccessToken() }

    init {
        state.update {
            it.copy(
                signIn = when {
                    !storage.isConfigured -> SignInState.NeedsSetup
                    tokens != null -> SignInState.SignedIn(tokens?.account.orEmpty())
                    else -> SignInState.SignedOut
                },
                recent = storage.recentSearches,
            )
        }
        if (tokens != null && tokens?.account.isNullOrEmpty()) refreshAccountName()
    }

    // -- setup and sign-in -------------------------------------------------
    val clientId: String get() = storage.clientId
    val tenant: String get() = storage.tenant

    fun saveSetup(clientId: String, tenant: String) {
        storage.clientId = clientId
        storage.tenant = tenant
        state.update {
            it.copy(
                signIn = if (storage.isConfigured) SignInState.SignedOut else SignInState.NeedsSetup,
            )
        }
    }

    /** The URL to open in the browser, or null if setup is incomplete. */
    fun beginSignIn(): String? {
        if (!storage.isConfigured) {
            state.update { it.copy(signIn = SignInState.NeedsSetup) }
            return null
        }
        val pkce = Pkce.generate()
        val stateValue = Pkce.randomState()
        pending = pkce
        pendingState = stateValue
        state.update { it.copy(signIn = SignInState.InProgress, error = "") }
        return auth.authorizationUrl(pkce, stateValue)
    }

    /** Called when the browser redirects back to the app. */
    fun completeSignIn(code: String?, returnedState: String?, error: String?, description: String?) {
        val verifier = pending?.verifier
        pending = null
        when {
            !error.isNullOrEmpty() -> {
                val text = description?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                state.update {
                    it.copy(signIn = SignInState.Failed(
                        text.ifEmpty { if (error == "access_denied") "Sign-in was cancelled." else error }
                    ))
                }
                return
            }
            code.isNullOrEmpty() || verifier == null -> {
                state.update { it.copy(signIn = SignInState.Failed("Sign-in did not complete. Try again.")) }
                return
            }
            returnedState != pendingState -> {
                // The redirect did not come from the request we started.
                state.update { it.copy(signIn = SignInState.Failed("Sign-in could not be verified. Try again.")) }
                return
            }
        }

        viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { auth.redeemCode(code!!, verifier!!) }
                val address = withContext(Dispatchers.IO) {
                    runCatching { GraphClient { fresh.accessToken }.accountAddress() }.getOrDefault("")
                }
                val stored = fresh.copy(account = address)
                tokens = stored
                storage.saveTokens(stored)
                state.update { it.copy(signIn = SignInState.SignedIn(address), error = "") }
            } catch (failure: Exception) {
                state.update { it.copy(signIn = SignInState.Failed(readable(failure))) }
            }
        }
    }

    fun signOut() {
        tokens = null
        storage.clearTokens()
        searchJob?.cancel()
        state.update {
            UiState(
                signIn = if (storage.isConfigured) SignInState.SignedOut else SignInState.NeedsSetup,
                recent = storage.recentSearches,
            )
        }
    }

    private fun refreshAccountName() {
        viewModelScope.launch {
            val address = withContext(Dispatchers.IO) {
                runCatching { graph.accountAddress() }.getOrDefault("")
            }
            if (address.isNotEmpty()) {
                tokens = tokens?.copy(account = address)?.also(storage::saveTokens)
                state.update { it.copy(signIn = SignInState.SignedIn(address)) }
            }
        }
    }

    // -- searching ---------------------------------------------------------
    fun onQueryChanged(text: String) {
        state.update { it.copy(query = text) }
        searchJob?.cancel()
        if (text.isBlank()) {
            state.update {
                it.copy(results = emptyList(), hasSearched = false, error = "", notes = emptyList(),
                    searching = false, nextLink = "")
            }
            return
        }
        searchJob = viewModelScope.launch {
            // Each search is a request to Microsoft, so wait for a pause in
            // typing rather than firing on every keystroke.
            delay(DEBOUNCE_MS)
            runSearch(text)
        }
    }

    fun searchNow() {
        searchJob?.cancel()
        val text = state.value.query
        if (text.isBlank()) return
        searchJob = viewModelScope.launch { runSearch(text) }
    }

    fun useRecent(text: String) {
        state.update { it.copy(query = text) }
        searchNow()
    }

    private suspend fun runSearch(text: String) {
        val parsed = QueryParser.parse(text)
        if (parsed.mode == QueryMode.EMPTY) {
            state.update { it.copy(results = emptyList(), hasSearched = false, notes = parsed.notes) }
            return
        }
        state.update { it.copy(searching = true, error = "", notes = parsed.notes, terms = parsed.terms) }
        try {
            val found = withContext(Dispatchers.IO) { graph.search(parsed) }
            storage.rememberSearch(text)
            state.update {
                it.copy(
                    results = found.messages,
                    nextLink = found.nextLink,
                    searching = false,
                    hasSearched = true,
                    recent = storage.recentSearches,
                )
            }
        } catch (failure: Exception) {
            handleFailure(failure)
        }
    }

    fun loadMore() {
        val link = state.value.nextLink
        if (link.isEmpty() || state.value.loadingMore) return
        viewModelScope.launch {
            state.update { it.copy(loadingMore = true) }
            try {
                val more = withContext(Dispatchers.IO) { graph.page(link) }
                state.update {
                    it.copy(
                        // Graph can repeat a message across page boundaries;
                        // the list keys on id, and a duplicate key would crash it.
                        results = (it.results + more.messages).distinctBy(Message::id),
                        nextLink = more.nextLink,
                        loadingMore = false,
                    )
                }
            } catch (failure: Exception) {
                state.update { it.copy(loadingMore = false) }
                handleFailure(failure)
            }
        }
    }

    // -- reading -----------------------------------------------------------
    fun open(message: Message) {
        state.update { it.copy(opened = message, openedBody = message.preview, bodyLoading = true) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { graph.body(message.id) }
                if (state.value.opened?.id == message.id) {
                    state.update { it.copy(openedBody = text, bodyLoading = false) }
                }
            } catch (failure: Exception) {
                if (state.value.opened?.id == message.id) {
                    state.update { it.copy(bodyLoading = false, openedBody = readable(failure)) }
                }
            }
        }
    }

    fun closeMessage() = state.update { it.copy(opened = null, openedBody = "", bodyLoading = false) }

    fun dismissError() = state.update { it.copy(error = "") }

    // -- tokens ------------------------------------------------------------
    /**
     * A valid access token, refreshed if necessary.
     *
     * Called from IO threads inside [GraphClient]; refreshing is synchronous
     * and guarded so two parallel calls cannot both refresh.
     */
    @Synchronized
    private fun requireAccessToken(): String {
        val current = tokens ?: throw AuthException("Not signed in.", needsSignIn = true)
        if (current.isFresh()) return current.accessToken
        if (current.refreshToken.isEmpty()) {
            throw AuthException("The Outlook session expired. Please sign in again.", needsSignIn = true)
        }
        val refreshed = auth.refresh(current)
        val stored = refreshed.copy(account = current.account)
        tokens = stored
        storage.saveTokens(stored)
        return stored.accessToken
    }

    private fun handleFailure(failure: Exception) {
        val needsSignIn = (failure as? AuthException)?.needsSignIn == true ||
            (failure as? GraphException)?.needsSignIn == true
        if (needsSignIn) {
            tokens = null
            storage.clearTokens()
        }
        state.update {
            it.copy(
                searching = false,
                error = readable(failure),
                signIn = if (needsSignIn) SignInState.SignedOut else it.signIn,
            )
        }
    }

    private fun readable(failure: Exception): String = when (failure) {
        is NetworkUnavailable -> "No connection. Check your internet and try again."
        is AuthException -> failure.message.orEmpty()
        is GraphException -> failure.message.orEmpty()
        else -> failure.message ?: "Something went wrong."
    }

    private companion object {
        const val DEBOUNCE_MS = 450L
    }
}

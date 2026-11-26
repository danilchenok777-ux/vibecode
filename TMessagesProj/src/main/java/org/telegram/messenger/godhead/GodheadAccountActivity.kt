package org.telegram.messenger.godhead

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.R
import kotlinx.coroutines.launch

/**
 * GODHEAD identity is intentionally separate from the imported Telegram account session.
 * The endpoint is configured by the user and persisted for subsequent sign-ins.
 */
class GodheadAccountActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var serverField: EditText
    private lateinit var emailField: EditText
    private lateinit var usernameField: EditText
    private lateinit var displayNameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var statusView: TextView
    private lateinit var actionButtons: List<Button>
    private lateinit var sessionsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.GodheadAccountTitle)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = dp(20)
            setPadding(inset, inset, inset, inset)
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = getString(R.string.GodheadAccountTitle)
            textSize = 25f
        }, matchWrap())

        content.addView(TextView(this).apply {
            text = getString(R.string.GodheadAccountSubtitle)
            textSize = 14f
        }, spacedParams())

        val preferences = getSharedPreferences(CONFIG_PREFS, MODE_PRIVATE)
        val savedEndpoint = preferences.getString(CONFIG_BASE_URL, null)
            ?: if (BuildConfig.DEBUG) "http://10.0.2.2:8000" else ""

        serverField = addField(content, getString(R.string.GodheadServerUrl), savedEndpoint).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        emailField = addField(content, getString(R.string.GodheadEmail)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        usernameField = addField(content, getString(R.string.GodheadUsername))
        displayNameField = addField(content, getString(R.string.GodheadDisplayName))
        passwordField = addField(content, getString(R.string.GodheadPassword)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val registerButton = addButton(content, getString(R.string.GodheadCreateAccount))
        val loginButton = addButton(content, getString(R.string.GodheadSignIn))
        val profileButton = addButton(content, getString(R.string.GodheadCheckProfile))
        val signOutButton = addButton(content, getString(R.string.GodheadSignOut))
        val chatsButton = addButton(content, getString(R.string.GodheadOpenChats))
        val sessionsButton = addButton(content, getString(R.string.GodheadManageSessions))
        actionButtons = listOf(registerButton, loginButton, profileButton, signOutButton, chatsButton, sessionsButton)

        chatsButton.setOnClickListener {
            try {
                if (repository().hasLocalSession()) {
                    startActivity(Intent(this, GodheadConversationsActivity::class.java))
                } else {
                    statusView.text = getString(R.string.GodheadSignInRequired)
                }
            } catch (error: Exception) {
                statusView.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }

        statusView = TextView(this).apply {
            text = getString(R.string.GodheadStatusIdle)
            textSize = 14f
        }
        content.addView(statusView, spacedParams())

        content.addView(TextView(this).apply {
            text = getString(R.string.GodheadActiveSessions)
            textSize = 18f
        }, spacedParams())
        sessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(sessionsContainer, spacedParams())

        sessionsButton.setOnClickListener { loadSessions() }

        registerButton.setOnClickListener {
            runRequest {
                val user = repository().register(
                    emailField.text.toString().trim(),
                    usernameField.text.toString().trim(),
                    passwordField.text.toString(),
                    displayNameField.text.toString().trim()
                )
                getString(R.string.GodheadAccountCreated, user.displayName, user.username)
            }
        }

        loginButton.setOnClickListener {
            runRequest {
                val user = repository().login(
                    emailField.text.toString().trim(),
                    passwordField.text.toString()
                )
                getString(R.string.GodheadSignedIn, user.displayName, user.username)
            }
        }

        profileButton.setOnClickListener {
            runRequest {
                val user = repository().currentUser()
                getString(
                    R.string.GodheadProfileLoaded,
                    user.displayName,
                    user.username,
                    user.email
                )
            }
        }

        signOutButton.setOnClickListener {
            runRequest {
                repository().signOut()
                getString(R.string.GodheadSignedOut)
            }
        }
    }

    private fun loadSessions() {
        setBusy(true)
        statusView.text = getString(R.string.GodheadWorking)
        activityScope.launch {
            try {
                val sessions = repository().listSessions()
                renderSessions(sessions)
                statusView.text = getString(
                    R.string.GodheadSessionsLoaded,
                    sessions.size
                )
            } catch (error: Exception) {
                statusView.text = error.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.GodheadRequestFailed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderSessions(sessions: List<GodheadRemoteSession>) {
        sessionsContainer.removeAllViews()
        if (sessions.isEmpty()) {
            sessionsContainer.addView(TextView(this).apply {
                text = getString(R.string.GodheadNoSessions)
            }, spacedParams())
            return
        }

        sessions.forEach { session ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val details = TextView(this).apply {
                text = if (session.isCurrent) {
                    getString(R.string.GodheadCurrentSession, session.id) +
                        "\n" + getString(R.string.GodheadSessionExpires, session.expiresAt)
                } else {
                    getString(
                        R.string.GodheadRemoteSession,
                        session.id,
                        session.createdAt,
                        session.expiresAt
                    )
                }
                textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            row.addView(details, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))

            if (!session.isCurrent) {
                val revokeButton = Button(this).apply {
                    text = getString(R.string.GodheadRevokeSession)
                    isAllCaps = false
                    setOnClickListener { revokeSession(session.id) }
                }
                row.addView(revokeButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            sessionsContainer.addView(row, spacedParams())
        }
    }

    private fun revokeSession(sessionId: Long) {
        setBusy(true)
        statusView.text = getString(R.string.GodheadWorking)
        activityScope.launch {
            try {
                repository().revokeSession(sessionId)
                renderSessions(repository().listSessions())
                statusView.text = getString(R.string.GodheadSessionRevoked)
            } catch (error: Exception) {
                statusView.text = error.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.GodheadRequestFailed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun repository(): GodheadAccountRepository {
        val endpoint = serverField.text.toString().trim().trimEnd('/')
        require(endpoint.isNotBlank()) { getString(R.string.GodheadServerRequired) }

        val preferences = getSharedPreferences(CONFIG_PREFS, MODE_PRIVATE)
        val previousEndpoint = preferences.getString(CONFIG_BASE_URL, null)
        if (previousEndpoint != null && previousEndpoint != endpoint) {
            // Never send a previously saved token to a different API origin.
            GodheadSessionStore(this).clear()
        }
        preferences.edit().putString(CONFIG_BASE_URL, endpoint).apply()
        return GodheadAccountRepository(this, endpoint)
    }

    private fun runRequest(operation: suspend () -> String) {
        setBusy(true)
        statusView.text = getString(R.string.GodheadWorking)
        activityScope.launch {
            try {
                statusView.text = operation()
            } catch (error: Exception) {
                statusView.text = error.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.GodheadRequestFailed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        actionButtons.forEach { it.isEnabled = !busy }
        serverField.isEnabled = !busy
        emailField.isEnabled = !busy
        usernameField.isEnabled = !busy
        displayNameField.isEnabled = !busy
        passwordField.isEnabled = !busy
    }

    private fun addField(parent: LinearLayout, label: String, initialValue: String = ""): EditText {
        val field = EditText(this).apply {
            hint = label
            setSingleLine(true)
            setText(initialValue)
            textSize = 16f
        }
        parent.addView(field, spacedParams())
        return field
    }

    private fun addButton(parent: LinearLayout, label: String): Button {
        val button = Button(this).apply { text = label }
        parent.addView(button, spacedParams())
        return button
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun spacedParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(10)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CONFIG_PREFS = "godhead_client_config"
        const val CONFIG_BASE_URL = "api_base_url"
    }
}

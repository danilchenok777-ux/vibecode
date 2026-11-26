package org.telegram.messenger.godhead
import org.telegram.messenger.R

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import kotlinx.coroutines.launch

/** GODHEAD-only conversations UI; this screen never reads Telegram chat storage. */
class GodheadConversationsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var api: GodheadAccountRepository
    private lateinit var status: TextView
    private lateinit var conversations: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private var selected: GodheadConversation? = null
    private var currentUserId: Long? = null
    private var realtime: GodheadRealtimeClient? = null
    private val displayedMessageIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.GodheadChatsTitle)
        val endpoint = getSharedPreferences(PREFS, MODE_PRIVATE).getString(API_URL, null)
        if (endpoint.isNullOrBlank()) {
            finish()
            return
        }
        api = GodheadAccountRepository(this, endpoint)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14)
            setPadding(p, p, p, p)
        }
        setContentView(root)
        root.addView(TextView(this).apply {
            text = getString(R.string.GodheadChatsTitle)
            textSize = 23f
        }, matchParams())

        status = TextView(this).apply {
            text = getString(R.string.GodheadChatsStatus)
        }
        root.addView(status, spacedParams())

        val userSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val userSearchInput = EditText(this).apply {
            hint = getString(R.string.GodheadSearchUsersHint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val userSearchResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        userSearchRow.addView(userSearchInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        userSearchRow.addView(Button(this).apply {
            text = getString(R.string.GodheadSearchUsers)
            setOnClickListener {
                val query = userSearchInput.text.toString().trim()
                if (query.removePrefix("@").length < 2) {
                    status.text = getString(R.string.GodheadSearchUsersTooShort)
                } else {
                    runTask {
                        val matches = api.searchUsers(query)
                        userSearchResults.removeAllViews()
                        if (matches.isEmpty()) {
                            userSearchResults.addView(TextView(this@GodheadConversationsActivity).apply {
                                text = getString(R.string.GodheadNoUsersFound)
                            }, spacedParams())
                        }
                        matches.forEach { candidate ->
                            userSearchResults.addView(Button(this@GodheadConversationsActivity).apply {
                                text = "${candidate.displayName} (@${candidate.username})"
                                isAllCaps = false
                                setOnClickListener {
                                    runTask {
                                        val chat = api.openDirectConversation(candidate.id)
                                        selectChat(chat)
                                        refreshChats()
                                        getString(R.string.GodheadChatOpened, chat.id)
                                    }
                                }
                            }, spacedParams())
                        }
                        getString(R.string.GodheadSearchUsersCount, matches.size)
                    }
                }
            }
        }, wrapParams())
        root.addView(userSearchRow, spacedParams())
        root.addView(userSearchResults, spacedParams())

        val groupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val groupTitle = EditText(this).apply {
            hint = getString(R.string.GodheadGroupTitle)
            setSingleLine(true)
        }
        val memberIds = EditText(this).apply {
            hint = getString(R.string.GodheadGroupMemberIds)
            setSingleLine(true)
        }
        groupRow.addView(groupTitle, LinearLayout.LayoutParams(0, dp(52), 1f))
        groupRow.addView(memberIds, LinearLayout.LayoutParams(0, dp(52), 1f))
        groupRow.addView(Button(this).apply {
            text = getString(R.string.GodheadCreateGroup)
            setOnClickListener {
                val ids = memberIds.text.toString()
                    .split(',', ' ', ';')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .filter { it > 0 }
                    .distinct()
                runTask {
                    val chat = api.createGroupConversation(groupTitle.text.toString(), ids)
                    selectChat(chat)
                    refreshChats()
                    getString(R.string.GodheadChatOpened, chat.id)
                }
            }
        }, wrapParams())
        root.addView(groupRow, spacedParams())

        root.addView(TextView(this).apply {
            text = getString(R.string.GodheadConversationsHeader)
            textSize = 16f
        }, spacedParams())
        val conversationScroll = ScrollView(this)
        conversations = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        conversationScroll.addView(conversations)
        root.addView(conversationScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(135)
        ))

        root.addView(TextView(this).apply {
            text = getString(R.string.GodheadMessagesHeader)
            textSize = 16f
        }, spacedParams())
        messageScroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageScroll.addView(messages)
        root.addView(messageScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val sendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        messageInput = EditText(this).apply {
            hint = getString(R.string.GodheadMessageInput)
            setSingleLine(false)
            maxLines = 3
        }
        sendRow.addView(messageInput, LinearLayout.LayoutParams(0, dp(56), 1f))
        sendButton = Button(this).apply {
            text = getString(R.string.GodheadSendMessage)
            setOnClickListener { sendMessage() }
        }
        sendRow.addView(sendButton, wrapParams())
        root.addView(sendRow, spacedParams())

        scope.launch {
            try {
                currentUserId = api.currentUser().id
                connectRealtime()
                refreshChats()
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }
    }

    private fun connectRealtime() {
        realtime?.disconnect()
        realtime = api.connectRealtime(object : GodheadRealtimeClient.Listener {
            override fun onConnected() {
                status.text = getString(R.string.GodheadRealtimeConnected)
            }

            override fun onMessage(message: GodheadRealtimeMessage) {
                if (selected?.id == message.conversationId) {
                    appendMessage(GodheadMessage(
                        message.id, message.conversationId, message.senderId,
                        message.content, message.createdAt
                    ))
                }
                refreshChats()
            }

            override fun onDisconnected(code: Int, reason: String) {
                status.text = getString(R.string.GodheadRealtimeDisconnected)
            }

            override fun onError(error: Throwable) {
                status.text = error.message ?: getString(R.string.GodheadRealtimeFailed)
            }
        })
    }

    private fun refreshChats() {
        scope.launch {
            try {
                val items = api.listConversations()
                conversations.removeAllViews()
                if (items.isEmpty()) {
                    conversations.addView(TextView(this@GodheadConversationsActivity).apply {
                        text = getString(R.string.GodheadNoConversations)
                    }, spacedParams())
                }
                items.forEach { chat ->
                    val name = chat.title ?: getString(R.string.GodheadDirectConversation, chat.id)
                    val last = chat.lastMessage?.content.orEmpty()
                    conversations.addView(Button(this@GodheadConversationsActivity).apply {
                        text = if (last.isBlank()) name else name + "\n" + last
                        isAllCaps = false
                        setOnClickListener { selectChat(chat) }
                    }, spacedParams())
                }
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }
    }

    private fun selectChat(chat: GodheadConversation) {
        selected = chat
        displayedMessageIds.clear()
        messages.removeAllViews()
        status.text = getString(R.string.GodheadLoadingMessages)
        scope.launch {
            try {
                val page = api.listMessages(chat.id)
                page.items.forEach { appendMessage(it) }
                if (page.items.isEmpty()) {
                    messages.addView(TextView(this@GodheadConversationsActivity).apply {
                        text = getString(R.string.GodheadNoMessages)
                    }, spacedParams())
                }
                messageScroll.post { messageScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                status.text = getString(R.string.GodheadSelectedConversation, chat.id)
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }
    }

    private fun appendMessage(message: GodheadMessage) {
        if (message.conversationId != selected?.id) return
        if (!displayedMessageIds.add(message.id)) return
        val sender = if (message.senderId == currentUserId) {
            getString(R.string.GodheadMessageFromYou)
        } else {
            getString(R.string.GodheadMessageFromUser, message.senderId)
        }
        messages.addView(TextView(this).apply {
            text = sender + "\n" + message.content
            textSize = 15f
            val p = dp(10)
            setPadding(p, p, p, p)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }, spacedParams())
        messageScroll.post { messageScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val chatId = selected?.id
        if (chatId == null) {
            status.text = getString(R.string.GodheadChooseConversation)
            return
        }
        val body = messageInput.text.toString()
        if (body.isBlank()) return
        sendButton.isEnabled = false
        scope.launch {
            try {
                val created = api.sendMessage(chatId, body)
                messageInput.text.clear()
                appendMessage(created)
                refreshChats()
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            } finally {
                sendButton.isEnabled = true
            }
        }
    }

    private fun runTask(operation: suspend () -> String) {
        status.text = getString(R.string.GodheadWorking)
        scope.launch {
            try {
                status.text = operation()
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun wrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun spacedParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        realtime?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "godhead_client_config"
        const val API_URL = "api_base_url"
    }
}

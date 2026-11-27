package org.telegram.messenger.godhead
import org.telegram.messenger.R

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

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
    private var pendingDownload: Pair<Long, GodheadAttachment>? = null
    private var olderMessageCursor: Long? = null
    private var olderMessagesButton: Button? = null

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
        root.addView(Button(this).apply {
            text = getString(R.string.GodheadChooseAttachment)
            setOnClickListener { chooseAttachment() }
        }, spacedParams())

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
                scope.launch {
                    try {
                        if (message.senderId != currentUserId) {
                            api.acknowledgeDelivery(message.conversationId, message.id)
                        }
                        if (selected?.id == message.conversationId) {
                            api.markConversationRead(message.conversationId, message.id)
                        }
                        refreshChats()
                    } catch (error: Exception) {
                        status.text = error.message ?: getString(R.string.GodheadRequestFailed)
                    }
                }
            }

            override fun onDisconnected(code: Int, reason: String) {
                status.text = getString(R.string.GodheadRealtimeDisconnected)
            }

            override fun onError(error: Throwable) {
                status.text = error.message ?: getString(R.string.GodheadRealtimeFailed)
            }

            override fun onStateEvent(type: String, data: org.json.JSONObject) {
                status.text = when (type) {
                    "message.delivered" -> "Message #${data.optLong("message_id")} delivered to user #${data.optLong("user_id")}"
                    "read.state.updated" -> "Conversation #${data.optLong("conversation_id")} read through message #${data.optLong("last_read_message_id")}"
                    else -> getString(R.string.GodheadChatsStatus)
                }
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
                        val label = if (chat.unreadCount > 0) {
                            getString(R.string.GodheadUnreadCount, name, chat.unreadCount)
                        } else name
                        text = if (last.isBlank()) label else label + "\n" + last
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
        olderMessageCursor = null
        olderMessagesButton = null
        displayedMessageIds.clear()
        messages.removeAllViews()
        status.text = getString(R.string.GodheadLoadingMessages)
        scope.launch {
            try {
                val page = api.listMessages(chat.id)
                if (selected?.id != chat.id) return@launch
                olderMessageCursor = page.nextBeforeId
                page.items.forEach { appendMessage(it) }
                updateOlderMessagesButton()
                page.items.filter { it.senderId != currentUserId }.forEach { message ->
                    launch { runCatching { api.acknowledgeDelivery(chat.id, message.id) } }
                }
                page.items.lastOrNull()?.let { api.markConversationRead(chat.id, it.id) }
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

    private fun appendMessage(message: GodheadMessage, position: Int? = null): Int {
        val startIndex = position ?: messages.childCount
        if (message.conversationId != selected?.id) return startIndex
        if (!displayedMessageIds.add(message.id)) return startIndex
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
        }, startIndex, spacedParams())
        message.attachment?.let { attachment ->
            messages.addView(Button(this).apply {
                text = getString(R.string.GodheadDownloadAttachment, attachment.filename)
                isAllCaps = false
                setOnClickListener {
                    pendingDownload = selected?.id?.let { it to attachment }
                    if (pendingDownload == null) return@setOnClickListener
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = attachment.contentType
                        putExtra(Intent.EXTRA_TITLE, attachment.filename)
                    }
                    startActivityForResult(intent, REQUEST_DOWNLOAD)
                }
            }, startIndex + 1, spacedParams())
        }
        if (position == null) {
            messageScroll.post { messageScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        return startIndex + if (message.attachment == null) 1 else 2
    }

    private fun updateOlderMessagesButton() {
        if (olderMessageCursor == null) {
            olderMessagesButton?.let(messages::removeView)
            olderMessagesButton = null
            return
        }
        val button = olderMessagesButton ?: Button(this).apply {
            text = getString(R.string.GodheadLoadEarlierMessages)
            isAllCaps = false
            setOnClickListener { loadOlderMessages() }
            olderMessagesButton = this
            messages.addView(this, 0, spacedParams())
        }
        button.isEnabled = true
    }

    private fun loadOlderMessages() {
        val chat = selected ?: return
        val cursor = olderMessageCursor ?: return
        olderMessagesButton?.isEnabled = false
        scope.launch {
            try {
                val page = api.listMessages(chat.id, beforeId = cursor)
                if (selected?.id != chat.id) return@launch
                olderMessageCursor = page.nextBeforeId
                var insertionIndex = if (olderMessagesButton == null) 0 else 1
                page.items.forEach { message ->
                    insertionIndex = appendMessage(message, insertionIndex)
                }
                updateOlderMessagesButton()
            } catch (error: Exception) {
                olderMessagesButton?.isEnabled = true
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }
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

    private fun chooseAttachment() {
        if (selected == null) {
            status.text = getString(R.string.GodheadChooseConversation)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_UPLOAD)
    }

    @Deprecated("Activity result API is kept compatible with the existing Activity-based screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_DOWNLOAD) pendingDownload = null
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_UPLOAD -> uploadSelectedAttachment(uri)
            REQUEST_DOWNLOAD -> downloadSelectedAttachment(uri)
        }
    }

    private fun uploadSelectedAttachment(uri: Uri) {
        val conversationId = selected?.id ?: return
        val displayName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
        val filename = displayName?.substringAfterLast('/')
            ?.replace(Regex("[\\u0000\\r\\n]"), "")
            ?.take(255)?.ifBlank { null } ?: "attachment"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        status.text = getString(R.string.GodheadWorking)
        scope.launch {
            val temp = File.createTempFile("godhead-upload-", ".tmp", cacheDir)
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(getString(R.string.GodheadAttachmentFailed))
                input.use { source -> temp.outputStream().use { source.copyTo(it) } }
                if (temp.length() > MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("Attachments cannot exceed 25 MiB")
                }
                val sent = api.uploadAttachment(conversationId, temp, filename, mimeType)
                appendMessage(sent)
                refreshChats()
                status.text = getString(R.string.GodheadAttachmentSent)
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadAttachmentFailed)
            } finally {
                temp.delete()
            }
        }
    }

    private fun downloadSelectedAttachment(uri: Uri) {
        val target = pendingDownload ?: return
        pendingDownload = null
        status.text = getString(R.string.GodheadWorking)
        scope.launch {
            try {
                val output = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException(getString(R.string.GodheadAttachmentFailed))
                output.use { api.downloadAttachment(target.first, target.second.id, it) }
                status.text = getString(R.string.GodheadAttachmentSaved, target.second.filename)
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadAttachmentFailed)
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
        const val REQUEST_UPLOAD = 4201
        const val REQUEST_DOWNLOAD = 4202
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    }
}

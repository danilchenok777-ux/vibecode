package org.telegram.messenger.godhead

import org.telegram.messenger.R

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.MimeTypeMap
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** GODHEAD-only conversations UI; this screen never reads Telegram chat storage. */
class GodheadConversationsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var api: GodheadAccountRepository
    private lateinit var status: TextView
    private lateinit var readReceipts: TextView
    private lateinit var conversations: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var attachmentButton: Button
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
        readReceipts = TextView(this).apply {
            text = getString(R.string.GodheadReadReceiptsEmpty)
            textSize = 12f
        }
        root.addView(readReceipts, spacedParams())

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
        attachmentButton = Button(this).apply {
            text = getString(R.string.GodheadAttachFile)
            isAllCaps = false
            setOnClickListener { chooseAttachment() }
        }
        sendRow.addView(attachmentButton, wrapParams())
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
                    val item = GodheadMessage(
                        message.id,
                        message.conversationId,
                        message.senderId,
                        message.content,
                        message.createdAt,
                        message.attachment
                    )
                    appendMessage(item)
                    if (message.senderId != currentUserId) {
                        scope.launch { markConversationRead(message.conversationId, message.id) }
                    }
                }
                refreshChats()
            }

            override fun onReadStateUpdated(state: GodheadReadState) {
                if (selected?.id == state.conversationId) {
                    scope.launch { refreshReadReceipts(state.conversationId) }
                }
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
        readReceipts.text = getString(R.string.GodheadReadReceiptsLoading)
        status.text = getString(R.string.GodheadLoadingMessages)
        scope.launch {
            try {
                val page = api.listMessages(chat.id)
                page.items.forEach { appendMessage(it) }
                if (page.items.isEmpty()) {
                    messages.addView(TextView(this@GodheadConversationsActivity).apply {
                        text = getString(R.string.GodheadNoMessages)
                    }, spacedParams())
                } else {
                    markConversationRead(chat.id, page.items.last().id)
                }
                refreshReadReceipts(chat.id)
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
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(10)
            setPadding(p, p, p, p)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        bubble.addView(TextView(this).apply {
            text = sender + "\n" + message.content
            textSize = 15f
        }, matchParams())
        message.attachment?.let { attachment ->
            bubble.addView(Button(this).apply {
                text = getString(
                    R.string.GodheadOpenAttachment,
                    attachment.filename,
                    formatBytes(attachment.sizeBytes)
                )
                isAllCaps = false
                setOnClickListener { openAttachment(message.conversationId, attachment) }
            }, wrapParams())
        }
        messages.addView(bubble, spacedParams())
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
                markConversationRead(chatId, created.id)
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
        val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(
            Intent.createChooser(picker, getString(R.string.GodheadChooseAttachment)),
            REQUEST_ATTACHMENT
        )
    }

    @Deprecated("Legacy activity result is used to keep compatibility with the existing Activity base class.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ATTACHMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        uploadAttachment(uri)
    }

    private fun uploadAttachment(uri: Uri) {
        val conversationId = selected?.id ?: run {
            status.text = getString(R.string.GodheadChooseConversation)
            return
        }
        attachmentButton.isEnabled = false
        status.text = getString(R.string.GodheadAttachmentReading)
        scope.launch {
            try {
                val metadata = queryAttachmentMetadata(uri)
                val allowed = ALLOWED_ATTACHMENT_TYPES.contains(metadata.contentType)
                if (!allowed) {
                    throw IOException(getString(R.string.GodheadAttachmentTypeUnsupported))
                }
                if (metadata.sizeBytes != null && metadata.sizeBytes > MAX_ATTACHMENT_BYTES) {
                    throw IOException(getString(R.string.GodheadAttachmentTooLarge))
                }
                val bytes = readAttachment(uri)
                val message = api.uploadAttachment(
                    conversationId,
                    metadata.filename,
                    metadata.contentType,
                    bytes,
                    messageInput.text.toString()
                )
                appendMessage(message)
                messageInput.text.clear()
                markConversationRead(conversationId, message.id)
                refreshChats()
                status.text = getString(R.string.GodheadAttachmentUploaded, metadata.filename)
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            } finally {
                attachmentButton.isEnabled = true
            }
        }
    }

    private fun queryAttachmentMetadata(uri: Uri): AttachmentPickerMetadata {
        var filename = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.GodheadAttachmentFallbackName)
        var size: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    filename = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: filename
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        val rawType = contentResolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        val extension = filename.substringAfterLast('.', "").lowercase()
        val guessedType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        val contentType = when (rawType) {
            "image/jpg" -> "image/jpeg"
            null, "", "application/octet-stream" -> guessedType?.lowercase() ?: rawType.orEmpty()
            else -> rawType
        }
        return AttachmentPickerMetadata(filename.take(255), contentType, size)
    }

    private suspend fun readAttachment(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Android could not open the selected file")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_ATTACHMENT_BYTES) {
                    throw IOException(getString(R.string.GodheadAttachmentTooLarge))
                }
                output.write(buffer, 0, count)
            }
        }
        output.toByteArray()
    }

    private fun openAttachment(conversationId: Long, attachment: GodheadAttachment) {
        status.text = getString(R.string.GodheadAttachmentPreparingDownload)
        scope.launch {
            try {
                val download = api.createAttachmentDownload(conversationId, attachment.id)
                val uri = Uri.parse(download.url)
                val localHost = uri.host == "localhost" || uri.host == "127.0.0.1" ||
                    uri.host == "10.0.2.2"
                require(uri.scheme == "https" || (uri.scheme == "http" && localHost)) {
                    "GODHEAD returned an unsafe attachment download URL"
                }
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, download.attachment.contentType)
                }
                startActivity(Intent.createChooser(viewIntent, download.attachment.filename))
                status.text = getString(
                    R.string.GodheadAttachmentLinkExpires,
                    download.expiresInSeconds
                )
            } catch (error: Exception) {
                status.text = error.message ?: getString(R.string.GodheadRequestFailed)
            }
        }
    }

    private suspend fun markConversationRead(conversationId: Long, messageId: Long) {
        if (selected?.id != conversationId) return
        try {
            api.markConversationRead(conversationId, messageId)
            refreshReadReceipts(conversationId)
        } catch (error: Exception) {
            if (selected?.id == conversationId) {
                status.text = error.message ?: getString(R.string.GodheadReadReceiptFailed)
            }
        }
    }

    private suspend fun refreshReadReceipts(conversationId: Long) {
        try {
            val states = api.listConversationReadStates(conversationId)
            if (selected?.id != conversationId) return
            val summary = states.mapNotNull { state ->
                state.lastReadMessageId?.let {
                    getString(R.string.GodheadReadCursorEntry, state.userId, it)
                }
            }
            readReceipts.text = if (summary.isEmpty()) {
                getString(R.string.GodheadReadReceiptsEmpty)
            } else {
                getString(R.string.GodheadReadReceiptsSummary, summary.joinToString(" · "))
            }
        } catch (_: Exception) {
            if (selected?.id == conversationId) {
                readReceipts.text = getString(R.string.GodheadReadReceiptFailed)
            }
        }
    }

    private fun formatBytes(sizeBytes: Long): String = when {
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KiB"
        else -> String.format("%.1f MiB", sizeBytes.toDouble() / (1024.0 * 1024.0))
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

    private data class AttachmentPickerMetadata(
        val filename: String,
        val contentType: String,
        val sizeBytes: Long?
    )

    private companion object {
        const val PREFS = "godhead_client_config"
        const val API_URL = "api_base_url"
        const val REQUEST_ATTACHMENT = 7412
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
        val ALLOWED_ATTACHMENT_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "video/mp4", "video/webm", "video/quicktime",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/wav",
            "application/pdf"
        )
    }
}

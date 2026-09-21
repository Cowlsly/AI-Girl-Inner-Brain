package app.maskan.chat.data.repository

import android.util.Base64
import app.maskan.chat.BuildConfig
import app.maskan.chat.data.local.ConversationDao
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.DocumentDao
import app.maskan.chat.data.local.DocumentEntity
import app.maskan.chat.data.local.FolderDao
import app.maskan.chat.data.local.FolderEntity
import app.maskan.chat.data.local.MessageDao
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.local.Presets
import app.maskan.chat.data.model.Dialect
import app.maskan.chat.data.remote.ChatCompletionResponse
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.VideoBackend
import app.maskan.chat.data.remote.VideoJobClient
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.util.DocumentChunks
import app.maskan.chat.util.DocumentExtract
import app.maskan.chat.util.ErrorMapper
import app.maskan.chat.util.ImageStore
import app.maskan.chat.util.TokenEstimate
import app.maskan.chat.video.VideoJobs
import app.maskan.chat.video.VideoOptions
import app.maskan.chat.video.VideoRenderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

enum class ExportFormat { PLAIN_TEXT, MARKDOWN }

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val keyRepository: KeyRepository,
    private val localeRepository: LocaleRepository,
    private val preferenceRepository: PreferenceRepository,
    private val imageStore: ImageStore,
    private val videoJobClient: VideoJobClient,
    private val videoBackendFor: (String) -> VideoBackend,
    private val videoJobs: VideoJobs
) {

    // ── Conversations ──────────────────────────────────────────────────

    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun getConversationById(id: Long): ConversationEntity? =
        conversationDao.getConversationById(id)

    suspend fun createConversation(
        title: String = "New Chat",
        providerId: String = ProviderRegistry.getDefaultProvider().id,
        modelId: String? = null
    ): Long {
        val conversation = ConversationEntity(
            title = title,
            providerId = providerId,
            modelId = modelId
        )
        return conversationDao.insertConversation(conversation)
    }

    suspend fun deleteConversation(id: Long) {
        // Collect the image files FIRST: the foreign-key cascade wipes the message rows, and
        // after that there is nothing left to say which files belonged to this conversation.
        val images = messageDao.getImagePathsForConversation(id)
        conversationDao.deleteConversationById(id)
        if (images.isNotEmpty()) imageStore.delete(images)
    }

    /**
     * Point a conversation at a different model, keeping its provider. Used to un-stick a chat
     * whose frozen modelId names a model the provider has retired.
     */
    suspend fun updateConversationModel(id: Long, modelId: String?) {
        conversationDao.updateConversationModel(id, modelId)
    }

    suspend fun updateConversationTitle(id: Long, title: String) {
        conversationDao.updateConversationTitle(id, title)
    }

    suspend fun updateSystemPrompt(id: Long, systemPromptId: String?, dialectId: String?) {
        conversationDao.updateSystemPrompt(id, systemPromptId, dialectId)
    }

    suspend fun moveConversationToFolder(conversationId: Long, folderId: Long?) {
        conversationDao.moveToFolder(conversationId, folderId)
    }

    suspend fun searchConversations(query: String): List<ConversationEntity> {
        val titleMatches = conversationDao.searchConversationsByTitle(query)
        val messageMatchIds = messageDao.searchMessages(query)
        val messageMatches = if (messageMatchIds.isNotEmpty()) {
            conversationDao.getConversationsByIds(messageMatchIds)
        } else {
            emptyList()
        }
        return (titleMatches + messageMatches)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
    }

    // ── Folders ────────────────────────────────────────────────────────

    fun getAllFolders(): Flow<List<FolderEntity>> = folderDao.getAll()

    suspend fun createFolder(name: String, colorHex: String? = null): Long {
        return folderDao.insert(FolderEntity(name = name, colorHex = colorHex))
    }

    suspend fun renameFolder(id: Long, newName: String) {
        folderDao.rename(id, newName)
    }

    suspend fun updateFolderColor(id: Long, colorHex: String) {
        folderDao.updateColor(id, colorHex)
    }

    suspend fun getFolder(id: Long): FolderEntity? = folderDao.getById(id)

    suspend fun updateFolderInstructions(id: Long, text: String?) {
        folderDao.updateInstructions(id, text?.takeIf { it.isNotBlank() })
    }

    suspend fun updateFolderMemory(id: Long, text: String?) {
        folderDao.updateMemory(id, text?.takeIf { it.isNotBlank() })
    }

    suspend fun deleteFolder(id: Long) {
        folderDao.delete(id)
    }

    // ── Export ─────────────────────────────────────────────────────────

    suspend fun exportConversation(conversationId: Long, format: ExportFormat): String {
        val conversation = conversationDao.getConversationById(conversationId)
        val title = conversation?.title ?: "Chat"
        val messages = messageDao.getMessagesForConversationOnce(conversationId)
            .filter { it.role != "system" }

        return buildString {
            appendLine("# $title")
            appendLine()
            when (format) {
                ExportFormat.PLAIN_TEXT -> {
                    for (msg in messages) {
                        val label = if (msg.role == "user") "You" else "AI"
                        appendLine("$label: ${msg.content}")
                    }
                }
                ExportFormat.MARKDOWN -> {
                    for ((index, msg) in messages.withIndex()) {
                        val label = if (msg.role == "user") "**You:**" else "**AI:**"
                        appendLine("$label ${msg.content}")
                        appendLine()
                        if (index < messages.lastIndex) {
                            appendLine("---")
                            appendLine()
                        }
                    }
                }
            }
        }.trimEnd()
    }

    // ── Messages ───────────────────────────────────────────────────────

    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    suspend fun saveMessage(
        conversationId: Long,
        role: String,
        content: String,
        imageBase64: String? = null,
        imageMimeType: String? = null
    ): Long {
        val message = MessageEntity(
            conversationId = conversationId,
            role = role,
            content = content,
            imageBase64 = imageBase64,
            imageMimeType = imageMimeType
        )
        return messageDao.insertMessage(message)
    }

    // ── Documents ──────────────────────────────────────────────────────

    fun documentsFor(conversationId: Long): Flow<List<DocumentEntity>> =
        documentDao.getForConversation(conversationId)

    /**
     * Read once, for the screen.
     *
     * Not the Flow: Room's invalidation is unreliable under SQLCipher (the message list is
     * driven the same way for the same reason), and the notes card must show 12 of 30 the
     * moment the twelfth chunk lands, not whenever Room notices.
     */
    suspend fun documentsOnce(conversationId: Long): List<DocumentEntity> =
        documentDao.getForConversationOnce(conversationId)

    suspend fun getDocument(id: Long): DocumentEntity? = documentDao.getById(id)

    suspend fun deleteDocument(id: Long) = documentDao.delete(id)

    suspend fun setDocumentPages(id: Long, messageIds: List<Long>) {
        documentDao.updatePageImageIds(id, messageIds.joinToString(",").ifBlank { null })
    }

    suspend fun setDocumentMessage(id: Long, messageId: Long?) {
        documentDao.updateAttachedMessage(id, messageId)
    }

    /**
     * The chunk size this conversation's provider can afford.
     *
     * A 4k-context model on someone's own machine has room for a 1,000-token excerpt and the
     * question and its own answer; 1,500 would have the server truncate the history silently,
     * which reads as a model that forgot the conversation.
     */
    suspend fun chunkTokensFor(conversationId: Long): Int {
        val conversation = conversationDao.getConversationById(conversationId)
        val provider = conversation?.let { ProviderRegistry.getProvider(it.providerId) }
        return if (provider?.isLocal == true) {
            DocumentChunks.CHUNK_TOKENS_LOCAL
        } else {
            DocumentChunks.CHUNK_TOKENS
        }
    }

    /**
     * Store an extracted file against a conversation.
     *
     * chunkCount is 0 for a document small enough to be pasted into the message whole - that is
     * the flag everything downstream reads as "this one needs no notes pass and must not be
     * added to the request a second time, because its text is already in the history".
     */
    suspend fun saveDocument(
        conversationId: Long,
        doc: DocumentExtract.Doc,
        attachedMessageId: Long? = null
    ): Long {
        val chunkTokens = chunkTokensFor(conversationId)
        val tokens = TokenEstimate.of(doc.text)
        val chunkCount = if (tokens > DocumentChunks.INLINE_CEILING_TOKENS) {
            DocumentChunks.chunk(doc.text, chunkTokens).size
        } else {
            0
        }
        return documentDao.insert(
            DocumentEntity(
                conversationId = conversationId,
                attachedMessageId = attachedMessageId,
                name = doc.name,
                kind = doc.kind,
                pages = doc.pages,
                tokens = tokens,
                text = doc.text,
                chunkCount = chunkCount,
                chunkTokens = chunkTokens,
                warning = doc.warning
            )
        )
    }

    sealed class NotesEvent {
        data class Progress(val done: Int, val total: Int) : NotesEvent()

        /** Rate limited; waiting [seconds] before trying the same chunk again. */
        data class Waiting(val seconds: Int) : NotesEvent()

        data class Stopped(val error: Throwable) : NotesEvent()
        data object Finished : NotesEvent()
    }

    /**
     * Summarise the document's remaining chunks, one request at a time.
     *
     * Sequential rather than parallel on purpose. A free-tier key answers a burst of thirty
     * requests with 429s, and the pass that was supposed to make a long file usable instead
     * fails halfway; one at a time with a backoff finishes, slowly, on every key there is.
     *
     * Every chunk is written with the count that describes it in one statement, so the pass
     * resumes exactly where it stopped - a process killed mid-pass loses the request in flight
     * and nothing else. Nothing here starts on its own: the user taps Continue, because these
     * are their requests being spent.
     */
    fun readDocument(documentId: Long): Flow<NotesEvent> = flow {
        while (true) {
            val document = documentDao.getById(documentId) ?: return@flow
            if (document.chunkCount <= 0 || document.notesDone >= document.chunkCount) {
                emit(NotesEvent.Finished)
                return@flow
            }
            val conversation = conversationDao.getConversationById(document.conversationId)
                ?: return@flow

            val chunks = DocumentChunks.chunk(document.text, document.chunkTokens)
            val index = document.notesDone
            val chunk = chunks.getOrNull(index)
            if (chunk == null) {
                // The stored count and the re-cut text disagree, which should not happen and
                // must not spin: call the pass done rather than ask for chunk 29 of 28 forever.
                documentDao.updateNotes(documentId, document.notes, document.chunkCount)
                emit(NotesEvent.Finished)
                return@flow
            }

            var attempt = 0
            while (true) {
                try {
                    val note = summariseChunk(conversation, chunk, index + 1, chunks.size)
                    val merged = listOfNotNull(
                        document.notes?.takeIf { it.isNotBlank() },
                        note
                    ).joinToString("\n\n")
                    documentDao.updateNotes(documentId, merged, index + 1)
                    emit(NotesEvent.Progress(index + 1, chunks.size))
                    break
                } catch (e: Exception) {
                    val code = ErrorMapper.httpCode(e)
                    val worthWaiting = (code == 429 || code == 503) && attempt < NOTES_MAX_RETRIES
                    if (!worthWaiting) {
                        emit(NotesEvent.Stopped(e))
                        return@flow
                    }
                    val seconds = NOTES_BACKOFF_SECONDS shl attempt
                    emit(NotesEvent.Waiting(seconds))
                    delay(seconds * 1000L)
                    attempt++
                }
            }
        }
    }

    private suspend fun summariseChunk(
        conversation: ConversationEntity,
        chunk: String,
        number: Int,
        total: Int
    ): String {
        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: " + providerId)

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        if (apiKey.isBlank() && !provider.supportsCustomBaseUrl) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }
        val model = conversation.modelId
            ?: keyRepository.getSelectedModel(providerId)
            ?: provider.defaultModel

        // No history and no folder instructions: this is an indexing job, not a conversation,
        // and sending the chat's context with all thirty of these would multiply the cost of
        // reading a file by the length of the chat it was dropped into.
        val instruction = Message(
            role = "system",
            text = "You are indexing one part of a longer document so that it can be found " +
                "again later. Summarise the excerpt in AT MOST five short lines. Keep names, " +
                "dates, numbers, amounts and defined terms exactly as they are written. Write " +
                "in the same language as the excerpt. Reply with the lines alone - no " +
                "preamble, no heading, no commentary."
        )
        val summary = provider.sendMessage(
            apiKey,
            model,
            listOf(instruction, Message(role = "user", text = chunk)),
            keyRepository.getBaseUrl(providerId)
        ).trim()

        if (summary.isBlank()) throw Exception("Empty response from " + provider.displayName)
        return "[" + number + "/" + total + "] " + summary
    }

    /**
     * What this question needs from the conversation's documents, or null.
     *
     * Only documents with chunks: one small enough to have been pasted into the message is
     * already in the history, and repeating it here would send it twice.
     */
    private fun documentBlock(
        docs: List<DocumentEntity>,
        question: String,
        local: Boolean
    ): String? {
        val readable = docs.filter { it.chunkCount > 0 && it.text.isNotBlank() }
        if (readable.isEmpty()) return null

        val budget = if (local) MAX_DOCUMENT_TOKENS_LOCAL else MAX_DOCUMENT_TOKENS
        val perDocument = (budget / readable.size).coerceAtLeast(400)
        val blocks = readable.map { oneDocumentBlock(it, question, perDocument) }
        return blocks.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun oneDocumentBlock(
        document: DocumentEntity,
        question: String,
        budget: Int
    ): String {
        val header = StringBuilder()
        header.append("### From the file \"").append(document.name).append("\"")
        if (document.pages > 0) header.append(" (").append(document.pages).append(" pages)")
        header.append("\n")
        header.append(
            "The user attached this file. Answer from it when the question is about it, and " +
                "say which part an answer came from."
        )
        if (document.partial) {
            // The honest sentence, and the reason the Continue button is worth tapping: a model
            // asked about the half of a contract nobody has read yet must say so, not invent it.
            header.append(
                "\nOnly " + document.notesDone + " of " + document.chunkCount +
                    " parts have been read so far. If the answer is not in what follows, say " +
                    "that this part of the file has not been read yet - do not guess."
            )
        }
        if (document.warning == DocumentExtract.WARN_ARABIC) {
            header.append(
                "\nThe Arabic in this file was stored as letter shapes and its word order may " +
                    "be reversed. If a passage reads as nonsense, say so instead of guessing."
            )
        }

        // Notes first and clamped least: they are what the model has actually been told about
        // the parts the excerpts do not cover.
        val notesBudget = (budget * 0.45).toInt().coerceAtLeast(200)
        val notes = document.notes?.takeIf { it.isNotBlank() }
            ?.let { TokenEstimate.clamp(it, notesBudget) }

        val chunks = DocumentChunks.chunk(document.text, document.chunkTokens)
        val picked = DocumentChunks.rank(question, chunks)
        val excerptBudget = (budget - TokenEstimate.of(notes) - TokenEstimate.of(header.toString()))
            .coerceAtLeast(200)
        val perExcerpt = (excerptBudget / picked.size.coerceAtLeast(1)).coerceAtLeast(150)
        val excerpts = picked.mapNotNull { index ->
            chunks.getOrNull(index)?.let { TokenEstimate.clamp(it, perExcerpt) }
        }

        val out = StringBuilder(header)
        if (notes != null) out.append("\n\n#### Summary of the whole file\n").append(notes)
        if (excerpts.isNotEmpty()) {
            out.append("\n\n#### The parts closest to this question\n")
            out.append(excerpts.joinToString("\n\n---\n\n"))
        }
        return out.toString()
    }

    // ── API Call ───────────────────────────────────────────────────────

    suspend fun sendMessage(
        conversationId: Long,
        userContent: String,
        model: String = "deepseek-chat"
    ): Result<ChatCompletionResponse> {
        var userMessageId: Long? = null
        return try {
            val conversation = conversationDao.getConversationById(conversationId)
                ?: return Result.failure(Exception("Conversation not found"))

            val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
            val hasSystemMessage = existingMessages.any { it.role == "system" }

            if (conversation.systemPromptId != null && !hasSystemMessage) {
                val preset = resolvePreset(conversation)
                if (preset != null) {
                    val isArabic = localeRepository.getLocale() == "ar"
                    val systemContent = if (isArabic) preset.systemPromptAr else preset.systemPromptEn
                    if (systemContent.isNotBlank()) {
                        saveMessage(conversationId, "system", systemContent)
                    }
                }
            }

            userMessageId = saveMessage(conversationId, "user", userContent)

            val messages = buildMessageList(conversation, conversation.modelId ?: model)

            val providerId = conversation.providerId
            val provider = ProviderRegistry.getProvider(providerId)
                ?: run {
                    messageDao.deleteMessageById(userMessageId)
                    return Result.failure(Exception("Unknown provider: $providerId"))
                }

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val isLocalProvider = provider.supportsCustomBaseUrl
            if (apiKey.isBlank() && !isLocalProvider) {
                messageDao.deleteMessageById(userMessageId)
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val effectiveModel = conversation.modelId ?: model

            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val assistantContent = provider.sendMessage(apiKey, effectiveModel, messages, storedBaseUrl)

            saveMessage(conversationId, "assistant", assistantContent)

            if (conversation.title == "New Chat") {
                val title = userContent.take(50).let {
                    if (it.length == 50) "$it..." else it
                }
                conversationDao.updateConversationTitle(conversationId, title)
            }

            Result.success(ChatCompletionResponse(
                id = "",
                obj = "chat.completion",
                created = System.currentTimeMillis() / 1000,
                model = effectiveModel,
                choices = emptyList(),
                usage = null
            ))
        } catch (e: Exception) {
            userMessageId?.let { messageDao.deleteMessageById(it) }
            Result.failure(e)
        }
    }

    private fun resolvePreset(conversation: ConversationEntity) =
        when (conversation.systemPromptId) {
            "en_to_ar" -> {
                val dialect = conversation.dialectId?.let { Dialect.fromId(it) } ?: Dialect.MSA
                Presets.enToArPreset(dialect)
            }
            "custom" -> null
            else -> conversation.systemPromptId?.let { Presets.getById(it) }
        }

    fun sendMessageStreaming(
        conversationId: Long,
        userContent: String,
        model: String = "deepseek-chat",
        imageData: ByteArray? = null,
        imageMimeType: String? = null
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        val existingMessages = messageDao.getMessagesForConversationOnce(conversationId)
        val hasSystemMessage = existingMessages.any { it.role == "system" }

        if (conversation.systemPromptId != null && !hasSystemMessage) {
            val preset = resolvePreset(conversation)
            if (preset != null) {
                val isArabic = localeRepository.getLocale() == "ar"
                val systemContent = if (isArabic) preset.systemPromptAr else preset.systemPromptEn
                if (systemContent.isNotBlank()) {
                    saveMessage(conversationId, "system", systemContent)
                }
            }
        }

        val imageBase64ForStorage = imageData?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        val userEntity = MessageEntity(
            conversationId = conversationId,
            role = "user",
            content = userContent,
            imageBase64 = imageBase64ForStorage,
            imageMimeType = imageMimeType
        )
        val userMessageId = messageDao.insertMessage(userEntity)
        // Drive the open chat from in-memory state: emit the saved user message so the
        // ViewModel can show it immediately, independent of the (unreliable under SQLCipher)
        // Room invalidation Flow.
        emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))

        streamAssistantReply(conversation, model, imageData, imageMimeType)

        if (conversation.title == "New Chat") {
            val title = userContent.take(50).let {
                if (it.length == 50) "$it..." else it
            }
            conversationDao.updateConversationTitle(conversationId, title)
        }

        emit(StreamEvent.Done)
    }

    /**
     * Re-run the last user turn WITHOUT inserting it again.
     *
     * A failed send leaves the user message in the DB - it is saved and emitted before the
     * provider is ever called - and deletes only the empty assistant placeholder. So the retry
     * after moving a conversation off a retired model must not go through sendMessageStreaming,
     * which would duplicate the user's bubble. Same streaming tail, no user insert, no title
     * rewrite.
     */
    fun regenerateLastReply(conversationId: Long): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        // buildMessageList carries text only; an attached image lives on the message row, so
        // recover it from the last user turn or the retry silently drops the attachment.
        val lastUser = messageDao.getMessagesForConversationOnce(conversationId)
            .lastOrNull { it.role == "user" }
        val imageData = lastUser?.imageBase64?.let { Base64.decode(it, Base64.NO_WRAP) }

        streamAssistantReply(
            conversation = conversation,
            model = conversation.modelId ?: "",
            imageData = imageData,
            imageMimeType = lastUser?.imageMimeType
        )

        emit(StreamEvent.Done)
    }

    /**
     * Turn a rough description into a prompt an image model can actually use, using the CHAT
     * model the user already has selected.
     *
     * Two jobs in one call. Image models are trained overwhelmingly on English and answer a
     * short Arabic or Thai phrase poorly, so this bridges the language; and they respond to
     * concrete visual detail (subject, setting, light, style) that a person typing three words
     * has not supplied. The result is handed BACK to the user to edit rather than sent
     * straight on - a silent rewrite of what someone asked for is not help, it is substitution.
     *
     * Runs on the chat model, not the image model, so it costs a few cents of text at most.
     */
    suspend fun improveImagePrompt(conversationId: Long, rough: String): Result<String> {
        return try {
            val conversation = conversationDao.getConversationById(conversationId)
                ?: return Result.failure(Exception("Conversation not found"))

            val providerId = conversation.providerId
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            if (apiKey.isBlank() && !provider.supportsCustomBaseUrl) {
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val model = conversation.modelId
                ?: keyRepository.getSelectedModel(providerId)
                ?: provider.defaultModel

            val instruction = Message(
                role = "system",
                text = "You write prompts for image-generation models. Rewrite the user's " +
                    "description as ONE vivid English image prompt. Translate it if it is not " +
                    "in English. Keep every subject the user named and add only concrete " +
                    "visual detail: setting, lighting, colour, composition, style. Do not add " +
                    "people, text or objects they did not mention. Reply with the prompt alone " +
                    "- no quotes, no preamble, no explanation."
            )
            val ask = Message(role = "user", text = rough)

            val improved = provider.sendMessage(
                apiKey,
                model,
                listOf(instruction, ask),
                keyRepository.getBaseUrl(providerId)
            ).trim().trim('"')

            if (improved.isBlank()) {
                Result.failure(Exception("Empty response from ${provider.displayName}"))
            } else {
                Result.success(improved)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ask the provider to draw [prompt] and land the result as an image bubble in this chat.
     *
     * Shaped as the same StreamEvent flow as a chat turn so the ViewModel needs no second
     * pipeline, even though nothing streams: the prompt is saved as the user's message, an empty
     * assistant placeholder appears while the provider works, and ImageReady swaps in the
     * finished picture. On failure the placeholder is removed exactly as a failed chat turn does,
     * so a dead request never leaves a blank bubble behind.
     *
     * The image model is a SEPARATE preference from the chat model - asking for a picture must
     * not cost the user the chat model they picked.
     */
    fun generateImage(conversationId: Long, prompt: String, size: String? = null): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")

        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val isLocalProvider = provider.supportsCustomBaseUrl
        if (apiKey.isBlank() && !isLocalProvider) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }

        val model = keyRepository.getSelectedImageModel(providerId)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("no image model selected")

        // A model the user's own server lists as a VIDEO model takes the async job path instead
        // of the blocking image request. The server says which ids those are (GET /health) so
        // nothing is hardcoded here; a server without /health is simply an image server.
        val baseUrl = keyRepository.getBaseUrl(providerId)
        if (isLocalProvider && !baseUrl.isNullOrBlank()) {
            val capabilities = withContext(Dispatchers.IO) { videoJobClient.probe(baseUrl, apiKey) }
            if (capabilities != null && model in capabilities.videoModels) {
                submitVideo(conversation, providerId, apiKey, baseUrl, model, prompt, null,
                    VideoOptions.DEFAULT_SIZE, VideoOptions.DEFAULT_SECONDS)
                return@flow
            }
        }

        val userEntity = MessageEntity(
            conversationId = conversationId,
            role = "user",
            content = prompt
        )
        val userMessageId = messageDao.insertMessage(userEntity)
        emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = ""
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        emit(StreamEvent.Started(assistantEntity.copy(id = assistantMessageId)))

        try {
            val image = provider.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                baseUrl = keyRepository.getBaseUrl(providerId),
                // Only the user's own server takes a free-form WxH; cloud vocabularies differ.
                size = if (isLocalProvider) size else null
            )
            val fileName = imageStore.save(image.bytes)
            messageDao.updateImagePath(assistantMessageId, fileName, image.mimeType)
            emit(
                StreamEvent.ImageReady(
                    assistantEntity.copy(
                        id = assistantMessageId,
                        imagePath = fileName,
                        imageMimeType = image.mimeType
                    )
                )
            )
        } catch (e: Exception) {
            messageDao.deleteMessageById(assistantMessageId)
            throw e
        }

        if (conversation.title == "New Chat") {
            val title = prompt.take(50).let { if (it.length == 50) "$it..." else it }
            conversationDao.updateConversationTitle(conversationId, title)
        }

        emit(StreamEvent.Done)
    }

    /**
     * Start a video and return at once. Nothing here waits for the render: the job is
     * submitted, its id is written into the assistant row, and a WorkManager worker takes over
     * (VideoRenderWorker) - that worker, not this flow, survives the screen locking. The bubble
     * shows progress from the worker until the clip replaces it.
     *
     * The prompt goes to the server AS TYPED, in any language: a server that serves the job
     * API expands and translates it itself (prompt_expanded), and expanding an already
     * translated prompt would only lose the user's own words.
     */
    private suspend fun FlowCollector<StreamEvent>.submitVideo(
        conversation: ConversationEntity,
        providerId: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        /** An attached photo makes it photo-to-video: the photo is what moves. */
        image: Pair<ByteArray, String>?,
        size: String,
        seconds: Int,
        saveUserMessage: Boolean = true
    ) {
        val conversationId = conversation.id
        if (saveUserMessage) {
            val userEntity = MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = prompt,
                imageBase64 = image?.let { Base64.encodeToString(it.first, Base64.NO_WRAP) },
                imageMimeType = image?.second
            )
            val userMessageId = messageDao.insertMessage(userEntity)
            emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))
        }

        // Submit BEFORE creating the assistant row: a refused request then fails like a failed
        // chat turn (the user's message stays, nothing else appears) with no placeholder to undo.
        val jobId = withContext(Dispatchers.IO) {
            videoBackendFor(providerId).submit(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                seconds = seconds,
                size = size,
                enhance = !VideoOptions.isCloud(providerId),
                imageDataUri = image?.let { (bytes, mime) ->
                    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            )
        }

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = "",
            imageMimeType = VideoRenderWorker.VIDEO_MIME,
            videoJobId = jobId
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        videoJobs.enqueue(assistantMessageId, conversationId, providerId)
        emit(StreamEvent.VideoQueued(assistantEntity.copy(id = assistantMessageId)))

        if (conversation.title == "New Chat") {
            val title = prompt.take(50).let { if (it.length == 50) "$it..." else it }
            conversationDao.updateConversationTitle(conversationId, title)
        }

        emit(StreamEvent.Done)
    }

    /**
     * Edit the attached photo. The user's bubble keeps the original (attached-image shape,
     * imageBase64) so before and after sit one above the other; the result lands in the same
     * stored-image bubble a drawing does. Blocking like a drawing - an edit is ~2-3 minutes on
     * a home GPU, inside the image client's timeout.
     */
    fun editImage(
        conversationId: Long,
        prompt: String,
        model: String,
        imageBytes: ByteArray,
        imageMimeType: String
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")
        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")
        val apiKey = keyRepository.getApiKey(providerId) ?: ""

        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val userEntity = MessageEntity(
            conversationId = conversationId,
            role = "user",
            content = prompt,
            imageBase64 = base64,
            imageMimeType = imageMimeType
        )
        val userMessageId = messageDao.insertMessage(userEntity)
        emit(StreamEvent.UserSaved(userEntity.copy(id = userMessageId)))

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = ""
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        emit(StreamEvent.Started(assistantEntity.copy(id = assistantMessageId)))

        try {
            val image = provider.editImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                imageDataUri = "data:$imageMimeType;base64,$base64",
                baseUrl = keyRepository.getBaseUrl(providerId)
            )
            val fileName = imageStore.save(image.bytes)
            messageDao.updateImagePath(assistantMessageId, fileName, image.mimeType)
            emit(
                StreamEvent.ImageReady(
                    assistantEntity.copy(
                        id = assistantMessageId,
                        imagePath = fileName,
                        imageMimeType = image.mimeType
                    )
                )
            )
        } catch (e: Exception) {
            messageDao.deleteMessageById(assistantMessageId)
            throw e
        }

        if (conversation.title == "New Chat") {
            val title = prompt.take(50).let { if (it.length == 50) "$it..." else it }
            conversationDao.updateConversationTitle(conversationId, title)
        }
        emit(StreamEvent.Done)
    }

    /**
     * Make a video from the composer's dedicated video entry: the chosen VIDEO model, the
     * prompt as typed, and the attached photo if there is one. Same flow shape as
     * [generateImage]; the render itself happens in the worker.
     */
    fun generateVideo(
        conversationId: Long,
        prompt: String,
        imageBytes: ByteArray?,
        imageMimeType: String?,
        size: String,
        seconds: Int,
        /** False on a retry: the user's message is already in the chat. */
        saveUserMessage: Boolean = true
    ): Flow<StreamEvent> = flow {
        val conversation = conversationDao.getConversationById(conversationId)
            ?: throw Exception("Conversation not found")
        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")
        if (!provider.supportsVideoGeneration) throw Exception("no video model selected")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val baseUrl = keyRepository.getBaseUrl(providerId)?.takeIf { it.isNotBlank() }
            ?: provider.defaultBaseUrl.takeIf { it.isNotBlank() }
            ?: throw Exception("No server URL configured. Please enter your ${provider.displayName} server URL in Settings.")
        val chosen = keyRepository.getSelectedVideoModel(providerId)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: throw Exception("no video model selected")

        val image = if (imageBytes != null && imageMimeType != null) imageBytes to imageMimeType else null
        // Venice names the two jobs separately - "wan-2-7-text-to-video" refuses a photo and
        // "wan-2-7-image-to-video" needs one - so the chosen family follows what was sent
        // rather than making the user re-pick a model per message.
        val model = when {
            providerId != "venice" -> chosen
            image != null -> chosen.replace("text-to-video", "image-to-video")
            else -> chosen.replace("image-to-video", "text-to-video")
        }
        submitVideo(conversation, providerId, apiKey, baseUrl, model, prompt, image, size, seconds, saveUserMessage)
    }

    /**
     * Stop waiting for a video and drop its bubble. The server is told to cancel (best effort -
     * it forgets the job on its own if unreachable) and the worker is stopped.
     */
    suspend fun cancelVideo(messageId: Long) {
        val row = messageDao.getMessageById(messageId) ?: return
        videoJobs.cancel(messageId)
        val jobId = row.videoJobId
        if (jobId != null) {
            val providerId = conversationDao.getConversationById(row.conversationId)?.providerId
            val baseUrl = providerId?.let { keyRepository.getBaseUrl(it) }
            if (providerId != null && !baseUrl.isNullOrBlank()) {
                val apiKey = keyRepository.getApiKey(providerId) ?: ""
                withContext(Dispatchers.IO) {
                    runCatching { videoBackendFor(providerId).cancel(baseUrl, apiKey, jobId) }
                }
            }
        }
        messageDao.deleteMessageById(messageId)
    }

    suspend fun deleteMessage(messageId: Long) {
        messageDao.deleteMessageById(messageId)
    }

    /** The provider's own price for a clip, when it will quote one (Venice); null otherwise. */
    suspend fun quoteVideo(providerId: String, model: String, seconds: Int, size: String): Double? {
        if (model.isBlank()) return null
        val quoter = videoBackendFor(providerId) as? app.maskan.chat.data.remote.VideoQuoter ?: return null
        val provider = ProviderRegistry.getProvider(providerId) ?: return null
        val baseUrl = keyRepository.getBaseUrl(providerId)?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        return withContext(Dispatchers.IO) {
            runCatching { quoter.quote(baseUrl, apiKey, model, seconds, size) }.getOrNull()
        }
    }

    /** One-shot read, for refreshing the open chat after a background worker changed a row. */
    suspend fun getMessagesOnce(conversationId: Long): List<MessageEntity> =
        messageDao.getMessagesForConversationOnce(conversationId)

    /**
     * The shared tail of both paths: build the context window, insert the assistant
     * placeholder, stream tokens into it, and clean up an empty placeholder on failure.
     */
    private suspend fun FlowCollector<StreamEvent>.streamAssistantReply(
        conversation: ConversationEntity,
        model: String,
        imageData: ByteArray?,
        imageMimeType: String?
    ) {
        val conversationId = conversation.id
        val messages = buildMessageList(conversation, conversation.modelId ?: model)

        val providerId = conversation.providerId
        val provider = ProviderRegistry.getProvider(providerId)
            ?: throw Exception("Unknown provider: $providerId")

        val apiKey = keyRepository.getApiKey(providerId) ?: ""
        val isLocalProvider = provider.supportsCustomBaseUrl
        if (apiKey.isBlank() && !isLocalProvider) {
            throw Exception("API key not set. Please add your API key in Settings.")
        }

        val effectiveModel = conversation.modelId ?: model
        val storedBaseUrl = keyRepository.getBaseUrl(providerId)

        val assistantEntity = MessageEntity(
            conversationId = conversationId,
            role = "assistant",
            content = ""
        )
        val assistantMessageId = messageDao.insertMessage(assistantEntity)
        // Emit the empty assistant placeholder (with its real id) so the ViewModel inserts a
        // bubble keyed by that id - every following Token updates that exact message.
        emit(StreamEvent.Started(assistantEntity.copy(id = assistantMessageId)))

        try {
            val fullContent = StringBuilder()
            provider.sendMessageStreaming(
                apiKey, effectiveModel, messages, storedBaseUrl,
                imageData, imageMimeType
            ).collect { token ->
                fullContent.append(token)
                val snapshot = fullContent.toString()
                messageDao.updateMessageContent(assistantMessageId, snapshot)
                emit(StreamEvent.Token(assistantMessageId, snapshot))
            }

            val finalContent = fullContent.toString()
            if (finalContent.isBlank()) {
                throw Exception("Empty response from ${provider.displayName}")
            }
        } catch (e: Exception) {
            val current = messageDao.getMessagesForConversationOnce(conversationId)
                .find { it.id == assistantMessageId }
            if (current == null || current.content.isBlank()) {
                messageDao.deleteMessageById(assistantMessageId)
            }
            throw e
        }
    }
    sealed class StreamEvent {
        data class UserSaved(val message: MessageEntity) : StreamEvent()
        data class Started(val message: MessageEntity) : StreamEvent()
        data class Token(val messageId: Long, val fullContent: String) : StreamEvent()

        /** A generated image finished and was written to disk; the entity carries its path. */
        data class ImageReady(val message: MessageEntity) : StreamEvent()

        /**
         * A video job was accepted by the server; the entity carries its job id and no file yet.
         * The flow ends here - a WorkManager worker delivers the clip minutes later.
         */
        data class VideoQueued(val message: MessageEntity) : StreamEvent()
        data object Done : StreamEvent()
    }

    suspend fun fetchModels(providerId: String): Result<app.maskan.chat.data.remote.providers.FetchedModels> {
        return try {
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))
            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val models = provider.fetchModels(apiKey, storedBaseUrl)
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Everything the app can honestly say about what this key can do, gathered in one pass:
     * what the catalogue offers, whether a real chat call answers, and the account balance
     * where the provider exposes one. This exists because "is it free?" is mostly unknowable
     * (a 200 looks identical on free and paid tiers) - but "does it work with YOUR key, and
     * why not" is always knowable, and that is the question users actually have.
     */
    data class KeyCapabilityReport(
        val chatFailure: Throwable?,   // null = chat answered
        val modelCount: Int,
        val freeCount: Int,
        val imageSupported: Boolean,
        val imageModelCount: Int,
        val balance: String?,          // formatted, only OpenRouter/DeepSeek publish one
        val isLocal: Boolean
    )

    suspend fun keyCapabilityReport(providerId: String): Result<KeyCapabilityReport> {
        return try {
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))
            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val isLocal = provider.supportsCustomBaseUrl
            if (apiKey.isBlank() && !isLocal) {
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }
            val baseUrl = keyRepository.getBaseUrl(providerId)

            // What the provider says it serves. Failure here is not fatal - the report can
            // still say whether chat answers.
            val fetched = runCatching { provider.fetchModels(apiKey, baseUrl) }
                .getOrDefault(app.maskan.chat.data.remote.providers.FetchedModels())
            val modelCount = if (fetched.ids.isNotEmpty()) fetched.ids.size
                else provider.availableModels.size

            // Does a real chat call answer? The provider's own refusal wording rides along.
            val model = keyRepository.getSelectedModel(providerId) ?: provider.defaultModel
            val chat = runCatching {
                provider.sendMessage(apiKey, model, listOf(Message(role = "user", text = "Hi")), baseUrl)
            }

            val balance = runCatching { provider.fetchBalance(apiKey) }.getOrNull()

            Result.success(
                KeyCapabilityReport(
                    chatFailure = chat.exceptionOrNull(),
                    modelCount = modelCount,
                    freeCount = fetched.freeIds.size,
                    imageSupported = provider.supportsImageGeneration,
                    imageModelCount = fetched.imageIds.size,
                    balance = balance,
                    isLocal = isLocal
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testConnection(providerId: String): Result<String> {
        return try {
            val provider = ProviderRegistry.getProvider(providerId)
                ?: return Result.failure(Exception("Unknown provider: $providerId"))

            val apiKey = keyRepository.getApiKey(providerId) ?: ""
            val isLocalProvider = provider.supportsCustomBaseUrl
            if (apiKey.isBlank() && !isLocalProvider) {
                return Result.failure(Exception("API key not set. Please add your API key in Settings."))
            }

            val storedBaseUrl = keyRepository.getBaseUrl(providerId)
            val model = keyRepository.getSelectedModel(providerId) ?: provider.defaultModel
            val testMessages = listOf(Message(role = "user", text = "Hi"))

            val response = provider.sendMessage(apiKey, model, testMessages, storedBaseUrl)

            // If the user has chosen an image model, a green tick that only proves chat works is
            // a half-truth - the image model lives on a different endpoint with its own access
            // rules (Together answers 403 there while chat is fine). Test it too.
            //
            // This DRAWS A REAL PICTURE and is billed like any other, so the caller says so in
            // the result rather than spending the user's money silently.
            val imageModel = keyRepository.getSelectedImageModel(providerId)
                ?.trim()?.takeIf { it.isNotBlank() }
            if (imageModel != null && provider.supportsImageGeneration) {
                provider.generateImage(apiKey, imageModel, "a small grey circle", storedBaseUrl)
                return Result.success("$response\n\u2713 $imageModel")
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * What this conversation's folder contributes to the system role, or null when it
     * contributes nothing.
     *
     * Read fresh on EVERY request. A preset is injected once, as a message row, which is why
     * editing a preset does nothing for a chat that already started; project instructions must
     * behave the opposite way - edit the text, and the chat opened last week obeys it on its
     * next message. Nothing here is ever written to the conversation.
     */
    private data class ProjectText(val instructions: String, val memory: String)

    private suspend fun projectText(conversation: ConversationEntity): ProjectText? {
        val folder = conversation.folderId?.let { folderDao.getById(it) }
        val instructions = folder?.instructions?.trim().orEmpty()
        val folderMemory = folder?.memory?.trim().orEmpty()

        // Global memory belongs to no folder, which is why a chat OUTSIDE every folder can carry
        // it: "what you know about me" is about the person, not the project. It is off until the
        // user turns it on, so on every install that has not opted in this reads empty and the
        // 2.5.0 early return below still fires for every unfiled chat, exactly as before.
        val globalMemory = if (preferenceRepository.isGlobalMemoryEnabled()) {
            preferenceRepository.getGlobalMemory()?.trim().orEmpty()
        } else {
            ""
        }
        if (instructions.isEmpty() && folderMemory.isEmpty() && globalMemory.isEmpty()) return null

        // One section, in assembly order: the folder's memory first, then the global file. The
        // model is being told facts; which file on this phone they came from is our business.
        val memory = listOf(folderMemory, globalMemory)
            .filter { it.isNotEmpty() }
            .joinToString("\n")

        return ProjectText(instructions = instructions, memory = memory)
    }

    /**
     * The system message this request will carry, within the ceiling.
     *
     * Which half gives way matters, and the device showed why: a folder with 7,795 tokens of
     * instructions had its memory cut away entirely, because the clamp trims the tail and memory
     * is assembled last - so the model kept the 40th paragraph of a style guide and lost "our
     * office is in Jabal Amman". Memory is a handful of short facts the user asked to keep; the
     * instructions are the part that can run to pages. So the instructions are cut to whatever
     * the ceiling leaves after the preset and the memory, and the whole is clamped again as a
     * backstop for the case where the memory alone is enormous.
     */
    private fun assembleSystemText(preset: String, project: ProjectText): String {
        val memoryBlock = if (project.memory.isEmpty()) {
            ""
        } else {
            "### What you know about me\n" + project.memory
        }
        val keptTokens = TokenEstimate.of(preset) + TokenEstimate.of(memoryBlock)
        val budget = (MAX_SYSTEM_TOKENS - keptTokens).coerceAtLeast(0)
        val instructionsBlock = if (project.instructions.isEmpty()) {
            ""
        } else {
            "### Project instructions\n" + TokenEstimate.clamp(project.instructions, budget)
        }
        val assembled = listOf(preset, instructionsBlock, memoryBlock)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return TokenEstimate.clamp(assembled, MAX_SYSTEM_TOKENS)
    }

    private suspend fun buildMessageList(
        conversation: ConversationEntity,
        /** The model about to answer - it decides whether photos can go back out at all. */
        model: String
    ): List<Message> {
        val entities = messageDao.getMessagesForConversationOnce(conversation.id)
        val documents = documentDao.getForConversationOnce(conversation.id)
        val carried = photosToCarry(conversation, model, entities, documents)
        val messages = entities.map { entity ->
            val base64 = entity.imageBase64
            if (entity.id in carried && base64 != null) {
                Message(
                    role = entity.role,
                    content = MessageContent.WithImage(
                        text = entity.content,
                        imageBase64 = base64,
                        mimeType = entity.imageMimeType ?: "image/jpeg"
                    )
                )
            } else {
                Message(role = entity.role, text = entity.content)
            }
        }

        val systemMessages = messages.filter { it.role == "system" }
        val nonSystemMessages = messages.filter { it.role != "system" }
        val recentMessages = nonSystemMessages.takeLast(MAX_CONTEXT_MESSAGES)

        val project = projectText(conversation)

        // The question the document evidence is chosen for is the turn being sent: the user row
        // is written before the request is built, so the last user message IS the question.
        val question = entities.lastOrNull { it.role == "user" }?.content.orEmpty()
        val isLocal = ProviderRegistry.getProvider(conversation.providerId)?.isLocal == true
        val documentText = documentBlock(documents, question, isLocal)

        // A chat outside a folder, in a folder with both files empty, and with no document must
        // send EXACTLY what 2.5.0 sent. Not nearly - exactly: this is the line that keeps the
        // new features from quietly changing every existing conversation in the app.
        if (project == null && documentText == null) {
            val plain = systemMessages + recentMessages
            logContext(conversation, plain, "2.5.0-shape")
            return plain
        }

        // One system message, always. The preset's row stays in the database (it is the user's
        // record of what this chat was set up as) but it is REPLACED in the outgoing request by
        // the assembled text: Anthropic and Gemini fold multiple system messages into one field
        // anyway, and an OpenAI-shaped provider would otherwise receive two, which is the 2.5
        // trap that rejected calls outright.
        val preset = systemMessages.joinToString("\n") { it.content.textContent() }.trim()
        // The document block is appended AFTER the project text and outside its ceiling: the
        // folder's instructions and the file the user just attached are two different budgets,
        // and a long style guide must not be able to squeeze out the contract being asked about.
        val assembled = listOfNotNull(
            if (project != null) assembleSystemText(preset, project) else preset.ifBlank { null },
            documentText
        ).joinToString("\n\n")

        val outgoing = listOf(Message(role = "system", text = assembled)) + recentMessages
        logContext(conversation, outgoing, if (documentText != null) "document" else "assembled")
        return outgoing
    }

    /**
     * Which stored photos go back out with this request.
     *
     * The last user photo is what a follow-up is almost always about ("which ones are
     * vegetarian?"), and the one before it is what a comparison needs. Older ones are the
     * conversation's history, not its subject, and re-uploading them every turn costs the user
     * real money on a metered key.
     */
    private fun photosToCarry(
        conversation: ConversationEntity,
        model: String,
        entities: List<MessageEntity>,
        documents: List<DocumentEntity>
    ): Set<Long> {
        val blind = modelKnownBlind(conversation, model)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "MaskanCtx",
                "carry? model=" + model + " knownBlind=" + blind +
                    " visionModels=" + preferenceRepository.getVisionModels(conversation.providerId).size +
                    " rowsWithImage=" + entities.count { it.role == "user" && it.imageBase64 != null }
            )
        }
        if (blind) return emptySet()

        // A scanned PDF went as three pictures, and the ordinary rule below would keep the last
        // two of them - dropping page 1, which is the page a letter or an invoice says
        // everything on. So while the newest picture in this chat is one of that document's
        // pages, the whole set travels together and REPLACES the two-photo carry rather than
        // adding to it; the moment the user attaches a newer photo, the ordinary rule resumes.
        val pageIds = documents.lastOrNull { it.isPages }?.pageIds().orEmpty()
        if (pageIds.isNotEmpty()) {
            val newestPicture = entities
                .lastOrNull { it.role == "user" && it.imageBase64 != null }?.id
            if (newestPicture != null && newestPicture in pageIds) return pageIds.toSet()
        }

        return entities
            .filter { it.role != "system" }
            .takeLast(MAX_CONTEXT_MESSAGES)
            .filter {
                it.role == "user" &&
                    it.imageBase64 != null &&
                    // A photo-to-video row keeps its source photo here too; a rendered clip is
                    // an assistant row and never reaches this filter.
                    it.imageMimeType?.startsWith("image/") != false
            }
            .takeLast(MAX_CARRIED_PHOTOS)
            .map { it.id }
            .toSet()
    }

    /**
     * Whether this app KNOWS the model about to answer cannot see - which is not the same as
     * not knowing it can.
     *
     * Only OpenRouter, Venice and a local server publish per-model modalities; Together and most
     * others publish nothing, and their provider-level flag is a guess about a catalogue of
     * hundreds of models. Treating "no data" as "blind" is what silently dropped the photo from
     * every follow-up on Together while the same model was answering about it (device, session
     * 3). The picture was sent once already; withholding it now cannot protect anyone.
     *
     * Where there IS data and it says this model takes text only, the photo stays behind: that
     * is a chat switched to a text model, and re-sending an image every turn would turn one
     * failed message into a chat that can no longer be used at all.
     */
    private fun modelKnownBlind(conversation: ConversationEntity, model: String): Boolean {
        val visionModels = preferenceRepository.getVisionModels(conversation.providerId)
        if (visionModels.isEmpty()) return false
        return model.trim() !in visionModels
    }

    /**
     * What is actually about to go on the wire, in the debug log: how many messages, in what
     * roles, and the whole system text.
     *
     * The alternative - turning the OkHttp interceptor up to BODY - would write the
     * Authorization header, i.e. real API keys, into logcat. This says everything the folder
     * work needs checking against and nothing secret: the system text is the user's own
     * instructions on the user's own phone.
     */
    private fun logContext(conversation: ConversationEntity, messages: List<Message>, shape: String) {
        if (!BuildConfig.DEBUG) return
        val system = messages.filter { it.role == "system" }
        val systemText = system.joinToString(" | ") { it.content.textContent() }
        android.util.Log.d(
            "MaskanCtx",
            "conv=" + conversation.id + " folder=" + conversation.folderId +
                " provider=" + conversation.providerId + " shape=" + shape +
                " msgs=" + messages.size + " systems=" + system.size +
                " imgs=" + messages.count { it.content is MessageContent.WithImage } +
                " sysTokens=" + TokenEstimate.of(systemText) +
                " roles=" + messages.joinToString(",") { it.role }
        )
        if (systemText.isNotEmpty()) {
            android.util.Log.d("MaskanCtx", "system<<" + systemText + ">>")
        }
    }

    companion object {
        const val MAX_CONTEXT_MESSAGES = 50

        /**
         * The ceiling on assembled system text. An Ollama model with a 4k window given 3,000
         * tokens of instructions has its HISTORY silently truncated by the server instead, and
         * the user sees a model that forgot the conversation rather than one that was told too
         * much. The editor's red meter warns long before this; this is the backstop.
         */
        const val MAX_SYSTEM_TOKENS = 6000

        /**
         * How many stored photos travel with a request. Every one of them is re-uploaded on
         * every following turn, so this is a cost ceiling as much as a context choice.
         */
        const val MAX_CARRIED_PHOTOS = 2

        /**
         * The ceiling on the document half of the system message - notes plus excerpts - and a
         * budget of its own rather than a share of MAX_SYSTEM_TOKENS. A folder with a long
         * instructions file and a long contract attached are two separate things the user
         * wants; neither should be able to erase the other.
         */
        const val MAX_DOCUMENT_TOKENS = 3000

        /** The same, for a 4k-context model on the user's own machine. */
        const val MAX_DOCUMENT_TOKENS_LOCAL = 2000

        /** How many times one chunk is retried after a 429 before the pass stops and says so. */
        const val NOTES_MAX_RETRIES = 4

        /** First wait after a rate limit; it doubles per attempt (2, 4, 8, 16 seconds). */
        const val NOTES_BACKOFF_SECONDS = 2
    }
}

/*
 * Maskan — Private AI chat
 * Copyright (C) 2025 Humam Malhas and Maskan contributors
 *
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 *
 * See LICENSE file for full terms.
 */
package app.maskan.chat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import app.maskan.chat.BuildConfig
import app.maskan.chat.data.repository.createEncryptedPrefsOrFallback
import java.io.File
import java.security.SecureRandom
import android.app.LocaleManager
import android.os.Build
import android.content.Context
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.maskan.chat.data.local.AppDatabase
import app.maskan.chat.data.remote.AnthropicService
import app.maskan.chat.data.remote.GeminiService
import app.maskan.chat.data.remote.OpenAiCompatibleService
import app.maskan.chat.data.remote.TogetherVideoClient
import app.maskan.chat.data.remote.VeniceVideoClient
import app.maskan.chat.data.remote.VeoVideoClient
import app.maskan.chat.data.remote.VideoBackend
import app.maskan.chat.data.remote.VideoJobClient
import app.maskan.chat.video.VideoJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.maskan.chat.data.remote.providers.AnthropicProvider
import app.maskan.chat.data.remote.providers.GeminiProvider
import app.maskan.chat.data.remote.providers.LocalProvider
import app.maskan.chat.data.remote.providers.OpenAiCompatibleProvider
import app.maskan.chat.data.remote.providers.ProviderConfigs
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import app.maskan.chat.data.repository.KeyRepository
import app.maskan.chat.data.repository.LocaleRepository
import app.maskan.chat.data.repository.PreferenceRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.maskan.chat.ui.viewmodel.ChatViewModel
import app.maskan.chat.ui.viewmodel.ProjectFilesViewModel
import app.maskan.chat.ui.viewmodel.ConversationListViewModel
import app.maskan.chat.ui.viewmodel.SettingsViewModel
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class MaskanApplication : Application() {

    // ── Localization ───────────────────────────────────────────────────

    val localeRepository: LocaleRepository by lazy { LocaleRepository(this) }

    // ── Network ────────────────────────────────────────────────────────

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val sharedOkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    val loggingInterceptor = HttpLoggingInterceptor { message ->
                        Log.d("OkHttp", message.replace(Regex("key=[^&\\s]+"), "key=REDACTED"))
                    }.apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    }
                    addInterceptor(loggingInterceptor)
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun createOpenAiService(baseUrl: String, readTimeoutSeconds: Long = 60): OpenAiCompatibleService {
        val client = if (readTimeoutSeconds == 60L) sharedOkHttpClient
        else sharedOkHttpClient.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiCompatibleService::class.java)
    }

    // ── Local Database ─────────────────────────────────────────────────

    private lateinit var dbPassphrase: ByteArray

    private val database by lazy { AppDatabase.getInstance(this, dbPassphrase) }

    /** For the video worker, which runs with no ViewModel or repository in sight. */
    val messageDao by lazy { database.messageDao() }

    // ── Repositories ───────────────────────────────────────────────────

    val keyRepository by lazy { KeyRepository(this) }

    val preferenceRepository by lazy { PreferenceRepository(this) }

    val imageStore by lazy { app.maskan.chat.util.ImageStore(this) }


    // ── Video ─────────────────────────────────────────────────────────

    val videoJobClient by lazy { VideoJobClient(sharedOkHttpClient, json) }

    val veoVideoClient by lazy { VeoVideoClient(sharedOkHttpClient, json) }

    /** Which wire shape a provider's video jobs use. Gemini is Veo; everything else is Sora-shaped. */
    val veniceVideoClient by lazy { VeniceVideoClient(sharedOkHttpClient, json) }

    val togetherVideoClient by lazy { TogetherVideoClient(sharedOkHttpClient, json) }

    fun videoBackendFor(providerId: String): VideoBackend = when (providerId) {
        "gemini" -> veoVideoClient
        "venice" -> veniceVideoClient
        "together" -> togetherVideoClient
        else -> videoJobClient
    }

    // The second context is where its STRINGS come from - see localizedContext. A lambda and
    // not a value: the user can change the language while the app is running.
    val videoJobs by lazy { VideoJobs(this) { localizedContext } }

    val chatRepository by lazy {
        ChatRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao(),
            folderDao = database.folderDao(),
            documentDao = database.documentDao(),
            keyRepository = keyRepository,
            localeRepository = localeRepository,
            preferenceRepository = preferenceRepository,
            imageStore = imageStore,
            videoJobClient = videoJobClient,
            videoBackendFor = ::videoBackendFor,
            videoJobs = videoJobs
        )
    }

    // ── ViewModels ─────────────────────────────────────────────────────

    fun provideChatViewModel(): ChatViewModel {
        return ChatViewModel(this, chatRepository, keyRepository, preferenceRepository, imageStore)
    }

    /**
     * One per folder-editor visit, like the chat's. Not in MaskanViewModelFactory because which
     * folder it is looking at is a navigation argument, not something the factory can know.
     */
    fun provideProjectFilesViewModel(): ProjectFilesViewModel {
        return ProjectFilesViewModel(chatRepository, preferenceRepository)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    private var startedActivities = 0

    /**
     * Whether any screen of ours is on display right now.
     *
     * Decides whether a finished picture needs a notification at all - on screen, the bubble IS
     * the notification. Counted from ActivityLifecycleCallbacks rather than ProcessLifecycleOwner
     * so it costs no dependency: lifecycle-process is a separate artifact and the app stays
     * F-Droid-clean on the dependency list it already has.
     */
    val isInForeground: Boolean get() = startedActivities > 0

    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onCreate() {
        super.onCreate()
        trackForeground()
        initDatabaseEncryption()
        applySavedLocale()
        registerProviders()
        resumePendingVideos()
    }

    /**
     * Any message still holding a video job id gets its worker back. Cheap when there is
     * nothing pending (one indexed-free SELECT on a small table), and it is what makes "start
     * a clip, reboot the phone, open the app tomorrow" end with the clip in the chat.
     */
    private fun resumePendingVideos() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { videoJobs.resumePending(messageDao, database.conversationDao()) }
        }
    }

    private fun initDatabaseEncryption() {
        System.loadLibrary("sqlcipher")

        val prefs = createEncryptedPrefsOrFallback(this, "maskan_db_prefs")
        var hex = prefs.getString("db_encryption_key", null)
        if (hex == null) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            hex = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("db_encryption_key", hex).apply()
        }
        dbPassphrase = hex.toByteArray()

        val dbFile = getDatabasePath("privacyai_database")
        if (dbFile.exists() && isUnencryptedSqlite(dbFile)) {
            encryptDatabase(dbFile, dbPassphrase)
        }
    }

    private fun isUnencryptedSqlite(file: File): Boolean {
        if (file.length() < 16) return false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        return header.contentEquals("SQLite format 3 ".toByteArray())
    }

    private fun encryptDatabase(dbFile: File, passphrase: ByteArray) {
        val tempFile = File(dbFile.parentFile, "privacyai_database_encrypted.db")
        if (tempFile.exists()) tempFile.delete()

        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, "", null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null
        )
        db!!.execSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '${String(passphrase)}'")
        db.execSQL("SELECT sqlcipher_export('encrypted')")
        db.execSQL("DETACH DATABASE encrypted")
        db.close()

        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
        dbFile.delete()
        tempFile.renameTo(dbFile)
    }

    private fun registerProviders() {
        for (config in ProviderConfigs.ALL_OPENAI_COMPATIBLE) {
            val service = createOpenAiService(config.baseUrl)
            val provider = OpenAiCompatibleProvider(
                id = config.id,
                displayName = config.displayName,
                nameAr = config.nameAr,
                defaultBaseUrl = config.baseUrl,
                supportsCustomBaseUrl = config.supportsCustomBaseUrl,
                supportsVision = config.supportsVision,
                isLocal = config.isLocal,
                availableModels = config.models,
                defaultModel = config.defaultModel,
                keyAcquisitionUrl = config.keyAcquisitionUrl,
                pricingInfo = config.pricingInfo,
                apiService = service,
                imageService = createOpenAiService(config.baseUrl, readTimeoutSeconds = 300)
            )
            ProviderRegistry.register(provider)
        }
        registerAnthropicProvider()
        registerGeminiProvider()
        registerLocalProviders()
    }

    private fun registerAnthropicProvider() {
        val service = Retrofit.Builder()
            .baseUrl(ProviderConfigs.ANTHROPIC.baseUrl)
            .client(sharedOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnthropicService::class.java)
        val provider = AnthropicProvider(
            config = ProviderConfigs.ANTHROPIC,
            apiService = service
        )
        ProviderRegistry.register(provider)
    }

    private fun registerGeminiProvider() {
        val service = Retrofit.Builder()
            .baseUrl(ProviderConfigs.GEMINI.baseUrl)
            .client(sharedOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiService::class.java)
        val provider = GeminiProvider(
            config = ProviderConfigs.GEMINI,
            apiService = service
        )
        ProviderRegistry.register(provider)
    }

    private fun registerLocalProviders() {
        for (config in ProviderConfigs.ALL_LOCAL) {
            val provider = LocalProvider(
                config = config,
                okHttpClient = sharedOkHttpClient,
                json = json
            )
            ProviderRegistry.register(provider)
        }
    }

    private fun applySavedLocale() {
        applyLocale(uiLanguageTag())
    }

    /** The language Maskan itself is in, as a tag. Empty means "follow the system". */
    private fun uiLanguageTag(): String {
        val saved = localeRepository.getLocale()
        return when {
            saved.isNotEmpty() -> saved
            java.util.Locale.getDefault().language == "ar" -> "ar"
            else -> ""
        }
    }

    /**
     * A context whose resources speak the language the user chose for MASKAN.
     *
     * The Application's own resources do not. A per-app language is applied when an ACTIVITY's
     * base context is built, so anything rendered with no Activity behind it - which is every
     * notification this app posts - came out in the SYSTEM language instead. On a phone whose
     * system is English and whose Maskan is Arabic, the screens were Arabic and "Video ready"
     * was English, at the same moment, on the same phone.
     */
    val localizedContext: Context
        get() {
            val tag = uiLanguageTag()
            if (tag.isEmpty()) return this
            val config = android.content.res.Configuration(resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tag))
            return createConfigurationContext(config)
        }

    fun applyLocale(languageTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = if (languageTag.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (languageTag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(languageTag)
                }
            )
        }
        // The channel names are part of the language change. Changing the language recreates the
        // Activity but not the process, so without this Settings -> Notifications went on showing
        // the old language until the app was next started cold.
        videoJobs.ensureChannel()
        videoJobs.ensureDoneChannel()
    }
}

class MaskanViewModelFactory(private val app: MaskanApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ConversationListViewModel::class.java) ->
                ConversationListViewModel(app.chatRepository, app.keyRepository) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(app, app.keyRepository, app.localeRepository, app.preferenceRepository, app.chatRepository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}

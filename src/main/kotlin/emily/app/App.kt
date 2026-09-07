package emily.app

import emily.bot.EmilyVirtualGirlBot
import emily.data.AdminRepository
import emily.data.BalanceRepository
import emily.data.ChatHistoryRepository
import emily.data.AnalyticsRepository
import emily.data.CustomStoryRepository
import emily.data.DataRetentionService
import emily.data.DialogRepository
import emily.data.GeneratedImageRepository
import emily.data.PromoRepository
import emily.data.ReferralRepository
import emily.data.SubscriptionRepository
import emily.data.UserActivityRepository
import emily.data.UserSettingsRepository
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.GifVideoService
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import emily.service.defaultSystemPrompt
import emily.firebase.FirebaseInitializer
import emily.miniapp.MiniAppConfig
import emily.miniapp.MiniAppServer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val ROLEPLAY_CHAT_MODEL = "deepseek-v4-flash"
private const val PREMIUM_CHAT_MODEL = "deepseek-v4-flash"
private const val DEFAULT_IMAGE_PROMPT_MODEL = "venice-uncensored-1-2"
private const val IMAGE_MODEL_ANIME = "wai-Illustrious"
private const val IMAGE_MODEL_REALISTIC = "lustify-v7"
private const val GIF_MODEL = "wan-2.5-preview-image-to-video"
private const val DEFAULT_MINI_APP_PORT = 8080

fun main() {
    SingleInstance.acquire(44609)
    BotRunGuard.tryLockOrExit()

    FirebaseInitializer(
        credentialsPath = Secrets.get("FIREBASE_CREDENTIALS_PATH"),
        databaseUrl = Secrets.get("FIREBASE_DATABASE_URL")
    ).init()

    val config = BotConfig(
        telegramToken = Secrets.get("TELEGRAM_BOT_TOKEN"),
        veniceToken = Secrets.get("VENICE_TOKEN")
    )

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                // BODY logs duplicate the complete conversation and expose sensitive chat data.
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(120))
        .writeTimeout(java.time.Duration.ofSeconds(30))
        .build()

    val translator = MyMemoryTranslator(okHttpClient)

    val balanceRepository = BalanceRepository()
    val subscriptionRepository = SubscriptionRepository()
    val adminRepository = AdminRepository()
    val analyticsRepository = AnalyticsRepository()
    val referralRepository = ReferralRepository()
    val retentionService = DataRetentionService()
    val chatHistoryRepository = ChatHistoryRepository()
    val dialogRepository = DialogRepository()
    val generatedImageRepository = GeneratedImageRepository()
    val customStoryRepository = CustomStoryRepository()
    val promoRepository = PromoRepository()
    val userActivityRepository = UserActivityRepository()
    val userSettingsRepository = UserSettingsRepository()
    val maxChatCompletionTokens = Secrets.getOrNull("CHAT_MAX_COMPLETION_TOKENS")
        ?.toIntOrNull()
        ?.coerceIn(64, 1_024)
        ?: 400
    val chatService = ChatService(
        okHttpClient,
        config.veniceToken,
        ROLEPLAY_CHAT_MODEL,
        maxCompletionTokens = maxChatCompletionTokens
    )
    val imagePromptModel = Secrets.getOrNull("IMAGE_PROMPT_CHAT_MODEL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_IMAGE_PROMPT_MODEL

    val animeImageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL_ANIME)
    val realisticImageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL_REALISTIC)
    val gifVideoService = GifVideoService(okHttpClient, config.veniceToken, GIF_MODEL)

    val memory = ConversationMemory { defaultSystemPrompt() }
    val miniAppPort = Secrets.getOrNull("MINI_APP_PORT")?.toIntOrNull() ?: DEFAULT_MINI_APP_PORT
    val configuredMiniAppUrl = Secrets.getOrNull("MINI_APP_URL")?.trim()?.takeIf { it.isNotBlank() }
    val miniAppPublicUrl = configuredMiniAppUrl ?: "http://localhost:$miniAppPort/miniapp/"
    val tributeStarsUrl = Secrets.getOrNull("TRIBUTE_STARS_URL")
        ?.trim()
        ?.takeIf { it.startsWith("https://t.me/tribute") || it.startsWith("https://web.tribute.tg/") }
    val miniAppEnabled = Secrets.getOrNull("MINI_APP_ENABLED")?.toBooleanStrictOrNull()
        ?: (configuredMiniAppUrl != null || Secrets.getOrNull("MINI_APP_DEV_USER_ID") != null)
    val adminPanelCode = Secrets.getOrNull("ADMIN_PANEL_CODE")?.trim()?.takeIf { it.isNotBlank() }
        ?: "EMILI_ADMIN_PANEL"
    val adminUserId = Secrets.getOrNull("ADMIN_USER_ID")?.toLongOrNull()

    val bot = EmilyVirtualGirlBot(
        config = config,
        repository = balanceRepository,
        subscriptionRepository = subscriptionRepository,
        adminRepository = adminRepository,
        analyticsRepository = analyticsRepository,
        referralRepository = referralRepository,
        chatHistoryRepository= chatHistoryRepository,
        dialogRepository = dialogRepository,
        generatedImageRepository = generatedImageRepository,
        customStoryRepository = customStoryRepository,
        promoRepository = promoRepository,
        userActivityRepository = userActivityRepository,
        userSettingsRepository = userSettingsRepository,
        chatService = chatService,
        animeImageService = animeImageService,
        realisticImageService = realisticImageService,
        gifVideoService = gifVideoService,
        memory = memory,
        translator = translator,
        subscriptionGroupUrl = "https://t.me/+_rSsi7FUDtYyM2Uy",
        premiumChatModel = PREMIUM_CHAT_MODEL,
        imagePromptModel = imagePromptModel,
        miniAppUrl = miniAppPublicUrl,
        adminPanelCode = adminPanelCode,
        adminUserId = adminUserId
    )

    if (miniAppEnabled) {
        runCatching {
            MiniAppServer(
                config = MiniAppConfig(
                    port = miniAppPort,
                    publicUrl = miniAppPublicUrl,
                    botToken = config.telegramToken,
                    botUsername = bot.getBotUsername(),
                    tributeStarsUrl = tributeStarsUrl,
                    devUserId = Secrets.getOrNull("MINI_APP_DEV_USER_ID")?.toLongOrNull()
                ),
                balanceRepository = balanceRepository,
                subscriptionRepository = subscriptionRepository,
                adminRepository = adminRepository,
                userActivityRepository = userActivityRepository,
                chatHistoryRepository = chatHistoryRepository,
                dialogRepository = dialogRepository,
                generatedImageRepository = generatedImageRepository,
                customStoryRepository = customStoryRepository,
                userSettingsRepository = userSettingsRepository,
                referralRepository = referralRepository,
                promoRepository = promoRepository,
                memory = memory,
                chatService = chatService
            ).start()
        }.onFailure { error ->
            println("MiniAppServer: failed to start: ${error.message}")
        }
    } else {
        println(
            "MiniAppServer: disabled. Add MINI_APP_ENABLED=true and MINI_APP_DEV_USER_ID for local testing, " +
                "or set MINI_APP_URL for Telegram WebApp mode."
        )
    }

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching { retentionService.cleanupOlderThanDays(days = 60) }
    }
    runCatching { bot.execute(DeleteWebhook()) }
    botsApi.registerBot(bot)
    bot.registerBotMenu()
}

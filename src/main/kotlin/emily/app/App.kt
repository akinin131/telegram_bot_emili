package emily.app

import emily.bot.EmilyVirtualGirlBot
import emily.data.BalanceRepository
import emily.data.ChatHistoryRepository
import emily.data.AnalyticsRepository
import emily.data.DataRetentionService
import emily.data.UserActivityRepository
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import emily.service.defaultSystemPrompt
import emily.firebase.FirebaseInitializer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val CHAT_MODEL = "venice-uncensored"
private const val IMAGE_MODEL_ANIME = "wai-Illustrious"
private const val IMAGE_MODEL_REALISTIC = "lustify-v7"

fun main() {
    SingleInstance.acquire(44609)
    BotRunGuard.tryLockOrExit()

    FirebaseInitializer(
        credentialsPath = Secrets.get("FIREBASE_CREDENTIALS_PATH"),
        databaseUrl = Secrets.get("FIREBASE_DATABASE_URL")
    ).init()

    val config = BotConfig(
        telegramToken = Secrets.get("TELEGRAM_BOT_TOKEN"),
        providerToken = Secrets.get("PROVIDER_TOKEN"),
        veniceToken = Secrets.get("VENICE_TOKEN")
    )

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(120))
        .writeTimeout(java.time.Duration.ofSeconds(30))
        .build()

    val translator = MyMemoryTranslator(okHttpClient)

    val balanceRepository = BalanceRepository()
    val analyticsRepository = AnalyticsRepository()
    val retentionService = DataRetentionService()
    val chatHistoryRepository = ChatHistoryRepository()
    val userActivityRepository = UserActivityRepository()
    val chatService = ChatService(okHttpClient, config.veniceToken, CHAT_MODEL)

    val animeImageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL_ANIME)
    val realisticImageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL_REALISTIC)

    val memory = ConversationMemory { defaultSystemPrompt() }

    val bot = EmilyVirtualGirlBot(
        config = config,
        repository = balanceRepository,
        analyticsRepository = analyticsRepository,
        chatHistoryRepository= chatHistoryRepository,
        userActivityRepository = userActivityRepository,
        chatService = chatService,
        animeImageService = animeImageService,
        realisticImageService = realisticImageService,
        memory = memory,
        translator = translator,
        subscriptionGroupUrl = "https://t.me/+_rSsi7FUDtYyM2Uy"
    )

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        runCatching { retentionService.cleanupOlderThanDays(days = 60) }
    }
    runCatching { bot.execute(DeleteWebhook()) }
    botsApi.registerBot(bot)
    bot.registerBotMenu()
}

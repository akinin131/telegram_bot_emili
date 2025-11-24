package emily.app

data class BotConfig(
    val telegramToken: String,
    val providerToken: String,
    val veniceToken: String,
    val deeplToken: String
)

data class WebAppStory(
    val characterName: String,
    val storyTitle: String,
    val style: Int?,
    val characterPersonality: String?,
    val storyDescription: String?,
    val fullStoryText: String
)


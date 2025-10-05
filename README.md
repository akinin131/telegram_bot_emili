# Emily Virtual Girl Telegram Bot

Emily Virtual Girl is a Kotlin-based Telegram bot that combines large language model chat and
image generation through the Venice AI platform. The bot keeps lightweight conversational
memory per chat, offers prepaid and subscription-based billing, and integrates with Firebase to
track user balances.

## Key Features

- **Conversational AI** powered by Venice `venice-uncensored` with persona-aware system prompts and
  a 20-message rolling history for context-rich replies.
- **Image generation** via Venice `wai-Illustrious`, enriched with Emily's appearance persona for
  consistent renders.
- **Balance and monetisation** with daily image caps per plan and optional packs handled through
  Telegram invoices and Firebase persistence.
- **DeepL translation** (optional) to translate Russian prompts to English before sending them to
  Venice services.
- **Telegram-first UX** with ephemeral hints, protected service messages, and a `/pic` command for
  image requests.

## Architecture Overview

```
src/main/kotlin/emily
├── app/                 # Application entry point, configuration, single-instance guard
├── bot/                 # Telegram bot implementation and command handling
├── data/                # Models and Firebase-backed balance repository
├── firebase/            # Firebase initialisation helpers
├── http/                # Coroutine-friendly OkHttp extensions
└── service/             # Chat, image, and memory services
```

The entry point `App.kt` wires together the HTTP client, services, Firebase, and the Telegram bot.
The bot delegates conversational logic to `ChatService`, image generation to `ImageService`, and
state management to `ConversationMemory`. Balances are stored per-user in Firebase using
`BalanceRepository`.

## Requirements

- JDK 17+
- Gradle 8 (wrapper provided)
- Access tokens for Telegram Bot API, Venice AI, DeepL (optional), and Firebase service account
- Network access to `api.venice.ai`, Telegram Bot API, and Firebase Realtime Database

## Configuration

Create environment variables (recommended) or another secure secret store before running locally:

| Variable | Description |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token from @BotFather. |
| `VENICE_API_TOKEN` | Venice API token for both chat and image endpoints. |
| `DEEPL_API_TOKEN` | Optional DeepL API key for automatic RU→EN translation. Leave empty to disable. |
| `FIREBASE_CREDENTIALS_PATH` | Path to a Firebase service-account JSON file. |
| `FIREBASE_DATABASE_URL` | Firebase Realtime Database URL (e.g. `https://<project>.firebaseio.com`). |

Update `BotConfig` instantiation inside `App.kt` to read values from the environment instead of hard
coding them (for example, using `System.getenv("TELEGRAM_BOT_TOKEN")`).

> **Security note:** never commit real tokens into the repository. Store credentials outside the
> codebase and provide them at runtime via environment variables or a secrets manager.

## Running Locally

1. Install the Firebase service account JSON referenced in `FIREBASE_CREDENTIALS_PATH`.
2. Export the required environment variables.
3. Launch the bot:

   ```bash
   ./gradlew run
   ```

   The entry point will initialise Firebase, configure the HTTP client, and register the bot with
   Telegram.

4. Stop the process with `Ctrl+C`. The coroutine scope in `EmilyVirtualGirlBot` will close the bot
   session gracefully.

## Telegram Commands

| Command | Purpose |
| --- | --- |
| `/start` | Initialise conversation and show usage hints. |
| `/buy` | Display plan and image pack options. |
| `/balance` | Show remaining credits, plan, and expiry. |
| `/reset` | Drop conversation memory for the current chat. |
| `/pic` | Show instructions for image generation requests. |
| `#pic …` | Request an image with the described scene. |

Commands are automatically deleted after execution to keep the chat clean.

## Image Prompting Quick Start

Use the `/pic` command or start a message with `#pic` followed by an English or Russian description
of the desired scene. Emily's visual persona is automatically prepended to the prompt, so focus on
pose, environment, mood, and styling details. Refer to
[`docs/IMAGE_PROMPTING.md`](docs/IMAGE_PROMPTING.md) for advanced guidance on prompt structure,
safety filters, and examples tailored for the `wai-Illustrious` model.

## Persistence and Quotas

User balances, plan expirations, and daily image counters are stored per Telegram user ID in
Firebase. Daily image allowances differ per plan and reset every calendar day.

## Error Handling & Retries

- Chat and image calls fall back to friendly error messages when Venice responds with an error.
- The bot sends typing/upload indicators while requests are in-flight to improve user experience.
- `ConversationMemory` automatically trims noise and resets when `/reset` is triggered, ensuring the
  context stays relevant.

## Testing & Tooling

- Run static checks: `./gradlew check`
- Use your preferred IDE (IntelliJ IDEA or Android Studio) for Kotlin linting and debugging.

## Further Reading

- [docs/IMAGE_PROMPTING.md](docs/IMAGE_PROMPTING.md)
- Venice API documentation (account required)
- Telegram Bot API reference
- DeepL API documentation


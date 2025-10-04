package emily.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.FileInputStream

class FirebaseInitializer(
    private val credentialsPath: String,
    private val databaseUrl: String
) {
    fun init() {
        FileInputStream(credentialsPath).use { stream ->
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(stream))
                .setDatabaseUrl(databaseUrl)
                .build()
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
            }
        }
    }
}

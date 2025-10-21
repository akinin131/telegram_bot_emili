package emily.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DEFAULT_DB_TIMEOUT_MS = 10_000L

suspend fun DatabaseReference.awaitSingle(timeoutMs: Long = DEFAULT_DB_TIMEOUT_MS): DataSnapshot =
    withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cont.resume(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            }
            addListenerForSingleValueEvent(listener)
            cont.invokeOnCancellation { removeEventListener(listener) }
        }
    }

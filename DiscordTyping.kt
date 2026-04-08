// DiscordTyping.kt

class DiscordTyping {
    // ... other code

    fun someFunction() {
        val result: Result<Unit> = // ... some logic that returns a Result
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            // Handle error
            val failureResult = Result.failure(e)
        }
    }
}
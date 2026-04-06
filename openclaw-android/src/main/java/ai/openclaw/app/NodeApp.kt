package ai.openclaw.app

import android.app.Application
import android.os.StrictMode

open class NodeApp : Application() {
  val prefs: SecurePrefs by lazy { SecurePrefs(this) }

  @Volatile private var runtimeInstance: NodeRuntime? = null

  /** Subclasses can override to provide local process channel (bypass WebSocket). */
  open fun provideLocalChatChannel(): com.xiaomo.base.IGatewayChannel? = null

  fun ensureRuntime(): NodeRuntime {
    runtimeInstance?.let { return it }
    return synchronized(this) {
      runtimeInstance ?: NodeRuntime(this, prefs, provideLocalChatChannel()).also { runtimeInstance = it }
    }
  }

  fun peekRuntime(): NodeRuntime? = runtimeInstance

  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
      StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
    }
  }
}

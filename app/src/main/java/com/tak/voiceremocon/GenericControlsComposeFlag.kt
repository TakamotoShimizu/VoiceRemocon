package com.tak.voiceremocon

import java.util.concurrent.atomic.AtomicBoolean

internal object GenericControlsComposeFlag {
  private val enabled = AtomicBoolean(false)

  fun isEnabled(): Boolean = enabled.get()

  fun setEnabledForTesting(value: Boolean) {
    enabled.set(value)
  }
}

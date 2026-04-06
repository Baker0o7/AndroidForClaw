/**
 * OpenClaw Source Reference:
 * - No OpenClaw counterpart (android-only)
 */
package com.xiaomo.androidforclaw

import androidx.core.content.Fileprovider

/** Thin subclass so the manifest merger can distinguish this from OpenClaw's Fileprovider. */
class ClawFileprovider : Fileprovider()

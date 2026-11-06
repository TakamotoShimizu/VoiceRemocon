// Top-level build file where you can add configuration options common to all sub-projects/modules.
layout.buildDirectory.set(file("/tmp/VoiceRemocon_build/root"))

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
}
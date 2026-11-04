package dev.ktchecker.fixture

import android.app.NotificationChannel
import android.app.NotificationManager

// NewApi must diagnose API 26 use against minSdk 24 without an SDK guard.
fun unguardedNotificationChannel(): NotificationChannel =
    NotificationChannel("fixture", "Fixture", NotificationManager.IMPORTANCE_DEFAULT)

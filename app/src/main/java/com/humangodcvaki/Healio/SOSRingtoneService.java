package com.humangodcvaki.Healio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * Foreground Service that plays ringtone and vibrates when SOS alert is received
 * Fixed to handle multiple doctors without crashing
 */
public class SOSRingtoneService extends Service {

    private static final String TAG = "SOSRingtoneService";
    private static final String CHANNEL_ID = "SOS_ALERT_CHANNEL";
    private static final String CHANNEL_NAME = "Emergency SOS Alerts";
    private static final int NOTIFICATION_ID = 1001;

    // Action constants
    public static final String ACTION_DISMISS = "ACTION_DISMISS";
    public static final String ACTION_ACCEPT = "ACTION_ACCEPT";
    public static final String ACTION_STOP = "ACTION_STOP";

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;

    // Alert data
    private String alertId;
    private String patientName;
    private String patientPhone;
    private double latitude;
    private double longitude;

    // Singleton-like behavior to prevent multiple instances
    private static boolean isRunning = false;
    private static String currentAlertId = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SOSRingtoneService created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "SOSRingtoneService started");

        if (intent != null) {
            String action = intent.getAction();

            // Handle stop/dismiss actions - stop immediately without starting foreground
            if (ACTION_DISMISS.equals(action) || ACTION_STOP.equals(action)) {
                Log.d(TAG, "Received stop/dismiss action");
                stopRingtoneAndVibration();
                return START_NOT_STICKY;
            }

            // Only start foreground for actual alerts, not for stop actions
            // Get alert data from intent
            alertId = intent.getStringExtra("alertId");

            if (alertId == null || alertId.isEmpty()) {
                Log.w(TAG, "No alert ID provided, stopping service");
                stopSelf();
                return START_NOT_STICKY;
            }


            patientName = intent.getStringExtra("patientName");
            patientPhone = intent.getStringExtra("patientPhone");
            latitude = intent.getDoubleExtra("latitude", 0.0);
            longitude = intent.getDoubleExtra("longitude", 0.0);

            // Check if already running for the same alert
            if (isRunning && alertId != null && alertId.equals(currentAlertId)) {
                Log.d(TAG, "Service already running for this alert, updating notification");
                updateNotification();
                return START_STICKY;
            }

            // Check if running for different alert - stop previous and start new
            if (isRunning && currentAlertId != null && !currentAlertId.equals(alertId)) {
                Log.d(TAG, "New alert received, stopping previous alert");
                // Stop previous alert sounds
                stopMediaAndVibration();
            }

            // Mark as running
            isRunning = true;
            currentAlertId = alertId;

            Log.d(TAG, "Alert received for patient: " + patientName);

            // Start foreground service with notification
            try {
                Notification notification = createNotification();
                startForeground(NOTIFICATION_ID, notification);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground: " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }

            // Play ringtone and vibrate
            playRingtone();
            startVibration();
        }

        return START_STICKY;
    }

    /**
     * Stop only media and vibration without stopping the service
     */
    private void stopMediaAndVibration() {
        // Stop media player
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                Log.d(TAG, "MediaPlayer released");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping media player: " + e.getMessage());
            }
            mediaPlayer = null;
        }

        // Stop vibration
        if (vibrator != null) {
            try {
                vibrator.cancel();
                Log.d(TAG, "Vibration cancelled");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping vibration: " + e.getMessage());
            }
            vibrator = null;
        }
    }

    /**
     * Create notification channel for Android O and above
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );

            channel.setDescription("Emergency SOS alerts from patients needing immediate medical assistance");
            channel.enableVibration(true);
            channel.enableLights(true);
            channel.setLightColor(android.graphics.Color.RED);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setSound(null, null); // We handle sound manually

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created");
            }
        }
    }

    /**
     * Create the persistent notification shown during the alert
     */
    private Notification createNotification() {
        // Intent to open incoming SOS activity when tapped
        Intent fullScreenIntent = new Intent(this, IncomingSOSActivity.class);
        fullScreenIntent.putExtra("alertId", alertId);
        fullScreenIntent.putExtra("patientName", patientName);
        fullScreenIntent.putExtra("patientPhone", patientPhone);
        fullScreenIntent.putExtra("latitude", latitude);
        fullScreenIntent.putExtra("longitude", longitude);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Dismiss action
        Intent dismissIntent = new Intent(this, SOSRingtoneService.class);
        dismissIntent.setAction(ACTION_DISMISS);
        PendingIntent dismissPendingIntent = PendingIntent.getService(
                this,
                1,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Build notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚨 EMERGENCY SOS ALERT")
                .setContentText(patientName + " needs immediate medical assistance!")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(getResources().getColor(android.R.color.holo_red_dark))
                .addAction(
                        android.R.drawable.ic_menu_call,
                        "OPEN",
                        fullScreenPendingIntent
                )
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "DISMISS",
                        dismissPendingIntent
                );

        // Set style for expanded notification
        builder.setStyle(new NotificationCompat.BigTextStyle()
                .bigText(patientName + " has triggered an emergency SOS alert.\n\n" +
                        "Tap to respond or dismiss if unable to help.")
                .setBigContentTitle("🚨 EMERGENCY SOS ALERT"));

        return builder.build();
    }

    /**
     * Update notification if service is already running
     */
    private void updateNotification() {
        try {
            Notification notification = createNotification();
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification: " + e.getMessage());
        }
    }

    /**
     * Play ringtone sound
     */
    private void playRingtone() {
        // Don't start new player if already playing
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            Log.d(TAG, "Ringtone already playing");
            return;
        }

        try {
            // Release any existing player first
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing old media player: " + e.getMessage());
                }
                mediaPlayer = null;
            }

            // Get default ringtone URI
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);

            // Fallback to notification sound if ringtone not available
            if (ringtoneUri == null) {
                ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }

            // Last fallback to alarm sound
            if (ringtoneUri == null) {
                ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }

            // If still null, we have a problem
            if (ringtoneUri == null) {
                Log.e(TAG, "No ringtone URI available on this device");
                return;
            }

            Log.d(TAG, "Playing ringtone: " + ringtoneUri);

            // Initialize MediaPlayer
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, ringtoneUri);

            // Set audio attributes based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .build();
                mediaPlayer.setAudioAttributes(attributes);
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);
            }

            // Set volume to maximum
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
                Log.d(TAG, "Current volume: " + currentVolume + "/" + maxVolume);
            }

            // Loop the ringtone
            mediaPlayer.setLooping(true);

            // Set error listener to prevent crashes
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                return true; // Return true to handle error
            });

            // Prepare and start playback
            mediaPlayer.prepare();
            mediaPlayer.start();

            Log.d(TAG, "Ringtone playing successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error playing ringtone: " + e.getMessage(), e);
            // Clean up on error
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (Exception ex) {
                    Log.e(TAG, "Error releasing media player after error: " + ex.getMessage());
                }
                mediaPlayer = null;
            }
        }
    }

    /**
     * Start vibration pattern
     */
    private void startVibration() {
        // Don't start if already vibrating
        if (vibrator != null) {
            Log.d(TAG, "Vibration already active");
            return;
        }

        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

            if (vibrator != null && vibrator.hasVibrator()) {
                // Vibration pattern: [delay, vibrate, sleep, vibrate, ...]
                // Pattern: wait 0ms, vibrate 1000ms, wait 1000ms, repeat
                long[] pattern = {0, 1000, 1000};

                Log.d(TAG, "Starting vibration pattern");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android O and above
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();

                    vibrator.vibrate(
                            android.os.VibrationEffect.createWaveform(pattern, 0),
                            audioAttributes
                    );
                } else {
                    // Below Android O
                    vibrator.vibrate(pattern, 0);
                }

                Log.d(TAG, "Vibration started");
            } else {
                Log.w(TAG, "Device does not support vibration");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting vibration: " + e.getMessage(), e);
            vibrator = null;
        }
    }

    /**
     * Stop ringtone and vibration
     */
    public void stopRingtoneAndVibration() {
        Log.d(TAG, "Stopping ringtone and vibration");

        // Stop media player
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                Log.d(TAG, "MediaPlayer released");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping media player: " + e.getMessage());
            }
            mediaPlayer = null;
        }

        // Stop vibration
        if (vibrator != null) {
            try {
                vibrator.cancel();
                Log.d(TAG, "Vibration cancelled");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping vibration: " + e.getMessage());
            }
            vibrator = null;
        }

        // Reset running state
        isRunning = false;
        currentAlertId = null;

        // Stop foreground service and remove notification
        stopForeground(true);
        stopSelf();

        Log.d(TAG, "Service stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SOSRingtoneService destroyed");
        stopRingtoneAndVibration();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed, but service continues");
    }
}
package com.humangodcvaki.Healio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashSet;
import java.util.Set;

/**
 * Background service that listens for SOS alerts for doctors
 * Fixed to handle multiple doctors without crashes
 */
public class SOSListenerService extends Service {

    private static final String TAG = "SOSListenerService";
    private static final String CHANNEL_ID = "SOS_LISTENER_CHANNEL";
    private static final int NOTIFICATION_ID = 2001;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private ChildEventListener notificationListener;
    private String userId;

    // Track processed alerts to prevent duplicates
    private Set<String> processedAlerts = new HashSet<>();
    private static String currentActiveAlert = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SOSListenerService created");

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Create notification channel first
        createNotificationChannel();

        // Start foreground immediately (REQUIRED for Android O+)
        startForeground(NOTIFICATION_ID, createNotification());

        if (mAuth.getCurrentUser() != null) {
            userId = mAuth.getCurrentUser().getUid();
            startListeningForSOSAlerts();
        } else {
            Log.e(TAG, "No user logged in, stopping service");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "SOSListenerService onStartCommand");
        return START_STICKY;
    }

    /**
     * Create notification channel for the foreground service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SOS Alert Monitoring",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors for emergency SOS alerts");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Create persistent notification for foreground service
     */
    private Notification createNotification() {
        Intent intent = new Intent(this, DoctorDashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📡 SOS Alert System Active")
                .setContentText("Listening for emergency alerts...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * Start listening for SOS notifications in Firebase
     */
    private void startListeningForSOSAlerts() {
        notificationListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildName) {
                if (snapshot.exists()) {
                    String type = snapshot.child("type").getValue(String.class);
                    Boolean read = snapshot.child("read").getValue(Boolean.class);

                    Log.d(TAG, "Notification received - Type: " + type + ", Read: " + read);

                    // Check if it's an unread emergency notification
                    if ("emergency".equals(type) && (read == null || !read)) {
                        String alertId = snapshot.child("alertId").getValue(String.class);

                        // Check if we've already processed this alert
                        if (alertId != null && processedAlerts.contains(alertId)) {
                            Log.d(TAG, "Alert " + alertId + " already processed, skipping");
                            return;
                        }

                        Log.d(TAG, "Emergency SOS detected! Alert ID: " + alertId);

                        // Mark as read immediately
                        snapshot.getRef().child("read").setValue(true);

                        // Add to processed set
                        if (alertId != null) {
                            processedAlerts.add(alertId);
                        }

                        // Load full alert details and show incoming screen
                        loadAlertAndShowIncomingScreen(alertId);
                    }
                }
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
                // Handle updates if needed
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
                // Handle removals if needed
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
                // Not used
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to listen for notifications: " + error.getMessage());
            }
        };

        // Only listen for unread emergency notifications to avoid replaying history
        // BUG FIX: removed .orderByChild("read").equalTo(false) — requires a Firebase
        // index that silently returns zero results if missing. Filter in Java instead.
        mDatabase.child("notifications").child(userId)
                .addChildEventListener(notificationListener);

        Log.d(TAG, "Started listening for SOS alerts for user: " + userId);
    }

    /**
     * Load alert details and trigger ringtone + incoming screen
     */
    private void loadAlertAndShowIncomingScreen(String alertId) {
        if (alertId == null) {
            Log.e(TAG, "Alert ID is null");
            return;
        }

        mDatabase.child("emergencyAlerts").child(alertId)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            String status = snapshot.child("status").getValue(String.class);

                            Log.d(TAG, "Alert status: " + status);

                            // Only show if alert is still active
                            if ("active".equals(status)) {
                                String patientName = snapshot.child("patientName").getValue(String.class);
                                String patientPhone = snapshot.child("patientPhone").getValue(String.class);
                                Double latitude = snapshot.child("latitude").getValue(Double.class);
                                Double longitude = snapshot.child("longitude").getValue(Double.class);

                                // Check if we're already showing this alert
                                if (alertId.equals(currentActiveAlert)) {
                                    Log.d(TAG, "Alert " + alertId + " already showing, skipping");
                                    return;
                                }

                                currentActiveAlert = alertId;

                                Log.d(TAG, "Starting ringtone and showing incoming screen for: " + patientName);

                                // Start ringtone service
                                Intent ringtoneIntent = new Intent(SOSListenerService.this, SOSRingtoneService.class);
                                ringtoneIntent.putExtra("alertId", alertId);
                                ringtoneIntent.putExtra("patientName", patientName);
                                ringtoneIntent.putExtra("patientPhone", patientPhone);
                                ringtoneIntent.putExtra("latitude", latitude != null ? latitude : 0.0);
                                ringtoneIntent.putExtra("longitude", longitude != null ? longitude : 0.0);

                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(ringtoneIntent);
                                    } else {
                                        startService(ringtoneIntent);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error starting ringtone service: " + e.getMessage());
                                }

                                // Show incoming SOS screen with delay to prevent race condition
                                new android.os.Handler().postDelayed(() -> {
                                    try {
                                        Intent incomingIntent = new Intent(SOSListenerService.this, IncomingSOSActivity.class);
                                        incomingIntent.putExtra("alertId", alertId);
                                        incomingIntent.putExtra("patientName", patientName);
                                        incomingIntent.putExtra("patientPhone", patientPhone);
                                        incomingIntent.putExtra("latitude", latitude != null ? latitude : 0.0);
                                        incomingIntent.putExtra("longitude", longitude != null ? longitude : 0.0);
                                        incomingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                        startActivity(incomingIntent);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error starting incoming activity: " + e.getMessage());
                                    }
                                }, 500); // 500ms delay

                            } else {
                                Log.d(TAG, "Alert is not active, ignoring");
                                // Remove from processed if not active so it can be processed later if reactivated
                                processedAlerts.remove(alertId);
                            }
                        } else {
                            Log.w(TAG, "Alert " + alertId + " does not exist");
                            processedAlerts.remove(alertId);
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "Failed to load alert: " + error.getMessage());
                        processedAlerts.remove(alertId);
                    }
                });
    }

    /**
     * Clear the current active alert (call this when alert is dismissed/accepted)
     */
    public static void clearCurrentAlert() {
        currentActiveAlert = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Remove Firebase listener
        if (notificationListener != null && userId != null) {
            mDatabase.child("notifications").child(userId)
                    .removeEventListener(notificationListener);
        }

        // Clear processed alerts
        processedAlerts.clear();
        currentActiveAlert = null;

        Log.d(TAG, "SOS Listener Service destroyed");
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
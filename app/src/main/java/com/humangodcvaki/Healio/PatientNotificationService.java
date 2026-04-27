package com.humangodcvaki.Healio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * Background foreground-service for patients.
 * Listens for incoming notifications such as emergency_video_call.
 *
 * BUG FIXES applied vs original:
 *
 *  1. Removed .orderByChild("read").equalTo(false) from the query.
 *     Firebase requires a database index for orderByChild queries. Without
 *     the index the SDK silently delivers zero results — onChildAdded never
 *     fires. We filter read==false in Java instead, which is always reliable.
 *     (Same fix applied to AmbulanceSOSListenerService & SOSListenerService.)
 *
 *  2. Mark notification read=true immediately on receipt, before acting on it.
 *     Previously nothing set read=true, so every service restart replayed
 *     the entire notification history. The log showed 13 replayed
 *     emergency_video_call entries on a single startup.
 *
 *  3. Added processedNotifications Set to deduplicate within a single session,
 *     guarding against the service receiving the same notification twice (e.g.
 *     if onChildAdded fires before the read=true write completes in Firebase).
 */
public class PatientNotificationService extends Service {

    private static final String TAG        = "PatientNotifService";
    private static final String CHANNEL_ID = "PATIENT_NOTIF_CHANNEL";
    private static final int    NOTIF_ID   = 4001;

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private ChildEventListener notifListener;
    private String             userId;

    // In-session deduplication — same pattern as SOSListenerService
    private final Set<String> processedNotifications = new HashSet<>();

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PatientNotificationService created");

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildForegroundNotification());

        if (mAuth.getCurrentUser() != null) {
            userId = mAuth.getCurrentUser().getUid();
            startListening();
        } else {
            Log.e(TAG, "No user logged in — stopping service");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (notifListener != null && userId != null) {
            // BUG FIX: must match the query used to attach — plain reference, no orderByChild
            mDatabase.child("notifications").child(userId)
                    .removeEventListener(notifListener);
        }
        processedNotifications.clear();
        Log.d(TAG, "PatientNotificationService destroyed");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed, but service continues");
    }

    // -----------------------------------------------------------------------
    // Notification channel + foreground notification
    // -----------------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Patient Notifications",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Monitors incoming doctor/ambulance responses");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildForegroundNotification() {
        Intent tapIntent = new Intent(this, PatientDashboardActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Healio — Monitoring Active")
                .setContentText("Listening for doctor and ambulance responses…")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    // -----------------------------------------------------------------------
    // Firebase listener
    // BUG FIX 1: listen to ALL notifications, filter read==false in Java.
    // -----------------------------------------------------------------------
    private void startListening() {
        notifListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String prev) {
                if (!snapshot.exists()) return;

                String  type = snapshot.child("type").getValue(String.class);
                Boolean read = snapshot.child("read").getValue(Boolean.class);

                Log.d(TAG, "Notification received - Type: " + type);

                // BUG FIX 1: filter unread in Java (not in the Firebase query)
                if (read != null && read) return;

                String notifKey = snapshot.getKey();

                // BUG FIX 3: deduplicate within this session
                if (notifKey != null && processedNotifications.contains(notifKey)) {
                    Log.d(TAG, "Notification " + notifKey + " already processed, skipping");
                    return;
                }
                if (notifKey != null) processedNotifications.add(notifKey);

                // BUG FIX 2: mark read=true IMMEDIATELY so restarts never replay this
                snapshot.getRef().child("read").setValue(true);

                Log.d(TAG, "Processing notification type: " + type + " key: " + notifKey);

                if ("emergency_video_call".equals(type)) {
                    handleVideoCallNotification(snapshot);
                }
                // Add other notification types here as needed
            }

            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {
                Log.e(TAG, "Listener cancelled: " + e.getMessage());
            }
        };

        // BUG FIX 1: plain reference — no orderByChild, no index required
        mDatabase.child("notifications").child(userId)
                .addChildEventListener(notifListener);

        Log.d(TAG, "Started listening for notifications for user: " + userId);
    }

    // -----------------------------------------------------------------------
    // Handle emergency_video_call — open VideoCallActivity for the patient
    // -----------------------------------------------------------------------
    private void handleVideoCallNotification(DataSnapshot snapshot) {
        String callId    = snapshot.child("callId").getValue(String.class);
        String doctorId  = snapshot.child("doctorId").getValue(String.class);
        String doctorName = snapshot.child("doctorName").getValue(String.class);

        if (callId == null) {
            Log.e(TAG, "video_call notification missing callId");
            return;
        }

        Log.d(TAG, "Incoming emergency video call — callId: " + callId
                + " from Dr. " + doctorName);

        // 500 ms delay matches the pattern used in SOSListenerService /
        // AmbulanceSOSListenerService to avoid race conditions on startup.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent intent = new Intent(PatientNotificationService.this,
                        VideoCallActivity.class);
                intent.putExtra("callId",      callId);
                intent.putExtra("doctorId",    doctorId);
                intent.putExtra("doctorName",  doctorName != null ? doctorName : "Doctor");
                intent.putExtra("isInitiator", false);   // patient is NOT the WebRTC initiator
                intent.putExtra("isEmergency", true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK  |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP  |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch VideoCallActivity: " + e.getMessage());
            }
        }, 500);
    }
}
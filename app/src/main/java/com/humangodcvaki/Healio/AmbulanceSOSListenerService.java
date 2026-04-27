package com.humangodcvaki.Healio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================
 * DIAGNOSTIC BUILD — filter Logcat by tag: AmbulanceSOSListener
 *
 * Expected log sequence when an SOS fires successfully:
 *   DIAG-1   service created
 *   DIAG-2   user authenticated, userId=<uid>
 *   DIAG-3   listener attached to notifications/<uid>
 *   DIAG-3a  shows how many notifications exist right now
 *   DIAG-4   onChildAdded fires for the new notification
 *   DIAG-5   type + read filter passed — alertId=<id>
 *   DIAG-6   marked read=true
 *   DIAG-7   fetching emergencyAlerts/<id>
 *   DIAG-8   alert status=active
 *   DIAG-9   activity launched!
 *
 * STOP AT whichever step is missing — that is the bug.
 * ============================================================
 */
public class AmbulanceSOSListenerService extends Service {

    private static final String TAG        = "AmbulanceSOSListener";
    private static final String CHANNEL_ID = "AMBULANCE_SOS_CHANNEL";
    private static final int    NOTIF_ID   = 3001;
    private static final long   LOCATION_INTERVAL_MS = 60_000L;

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private ChildEventListener notifListener;
    private String             userId;

    private final Set<String> processedAlerts    = new HashSet<>();
    private static String     currentActiveAlert = null;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DIAG-1 ✅ Service created");

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildForegroundNotification());
        Log.d(TAG, "DIAG-1b ✅ startForeground() done");

        if (mAuth.getCurrentUser() != null) {
            userId = mAuth.getCurrentUser().getUid();
            Log.d(TAG, "DIAG-2 ✅ Authenticated — userId=" + userId);
            startListening();
            setupAndStartLocationUpdates();
        } else {
            Log.e(TAG, "DIAG-2 ❌ No user logged in — stopping service");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "DIAG-1c onStartCommand startId=" + startId);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (notifListener != null && userId != null) {
            mDatabase.child("notifications").child(userId).removeEventListener(notifListener);
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        processedAlerts.clear();
        currentActiveAlert = null;
        Log.d(TAG, "DIAG ⚠️ Service DESTROYED");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "DIAG ⚠️ onTaskRemoved — will restart via START_STICKY");
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Ambulance SOS Monitoring", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Listens for emergency dispatch alerts");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildForegroundNotification() {
        Intent tap = new Intent(this, AmbulanceDriverDashboardActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚑 Ambulance Dispatch Active")
                .setContentText("Listening for emergency calls…")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true).setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build();
    }

    // -----------------------------------------------------------------------
    // Firebase listener
    // -----------------------------------------------------------------------
    private void startListening() {
        final String path = "notifications/" + userId;
        Log.d(TAG, "DIAG-3 ✅ Attaching listener to: " + path);

        // One-time read: print everything currently in notifications/<uid>
        // so you can see what Firebase has right now before any live event
        mDatabase.child("notifications").child(userId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        if (!snap.hasChildren()) {
                            Log.d(TAG, "DIAG-3a ℹ️ " + path + " is EMPTY right now");
                        } else {
                            Log.d(TAG, "DIAG-3a ✅ " + path + " has "
                                    + snap.getChildrenCount() + " notifications:");
                            for (DataSnapshot c : snap.getChildren()) {
                                Log.d(TAG, "   key=" + c.getKey()
                                        + " type="    + c.child("type").getValue()
                                        + " read="    + c.child("read").getValue()
                                        + " alertId=" + c.child("alertId").getValue());
                            }
                        }
                    }
                    @Override
                    public void onCancelled(DatabaseError err) {
                        Log.e(TAG, "DIAG-3a ❌ One-time read BLOCKED by security rules: "
                                + err.getCode() + " " + err.getMessage()
                                + "\n>>> Fix: check database.rules.json read permission"
                                + " for notifications/$uid");
                    }
                });

        notifListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snap, String prev) {
                String  type = snap.child("type").getValue(String.class);
                Boolean read = snap.child("read").getValue(Boolean.class);
                Log.d(TAG, "DIAG-4 🔔 onChildAdded — key=" + snap.getKey()
                        + " type=" + type + " read=" + read);

                if (!"emergency_ambulance".equals(type)) {
                    Log.d(TAG, "DIAG-4a ⏭️ Skip — type is not emergency_ambulance (got: " + type + ")");
                    return;
                }
                if (read != null && read) {
                    Log.d(TAG, "DIAG-4b ⏭️ Skip — already read=true");
                    return;
                }

                String alertId = snap.child("alertId").getValue(String.class);
                Log.d(TAG, "DIAG-5 ✅ Matched — alertId=" + alertId);

                if (alertId == null) {
                    Log.e(TAG, "DIAG-5 ❌ alertId is NULL in the notification node!"
                            + " The EmergencySOSActivity did not write alertId correctly.");
                    return;
                }
                if (processedAlerts.contains(alertId)) {
                    Log.d(TAG, "DIAG-5a ⏭️ Already processed — skipping");
                    return;
                }
                processedAlerts.add(alertId);

                snap.getRef().child("read").setValue(true);
                Log.d(TAG, "DIAG-6 ✅ Marked read=true — key=" + snap.getKey());

                loadAlertAndShowIncomingScreen(alertId, snap.getKey());
            }

            @Override
            public void onChildChanged(DataSnapshot s, String p) {
                Log.d(TAG, "DIAG onChildChanged key=" + s.getKey()
                        + " read=" + s.child("read").getValue());
            }

            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}

            @Override
            public void onCancelled(DatabaseError err) {
                Log.e(TAG, "DIAG ❌ ChildEventListener CANCELLED: "
                        + err.getCode() + " " + err.getMessage()
                        + "\n>>> This is usually a Firebase rules block."
                        + " Confirm notifications/$uid has .read: auth.uid == $uid");
            }
        };

        mDatabase.child("notifications").child(userId).addChildEventListener(notifListener);
        Log.d(TAG, "DIAG-3b ✅ ChildEventListener attached");
    }

    // -----------------------------------------------------------------------
    // Load alert + show incoming screen
    // -----------------------------------------------------------------------
    private void loadAlertAndShowIncomingScreen(String alertId, String notifKey) {
        Log.d(TAG, "DIAG-7 🔍 Fetching emergencyAlerts/" + alertId);

        mDatabase.child("emergencyAlerts").child(alertId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        if (!snap.exists()) {
                            Log.e(TAG, "DIAG-7a ❌ emergencyAlerts/" + alertId
                                    + " does NOT exist."
                                    + " The patient SOS write may have failed.");
                            processedAlerts.remove(alertId);
                            return;
                        }

                        String status = snap.child("status").getValue(String.class);
                        Log.d(TAG, "DIAG-8 ✅ Alert found — status=" + status
                                + " patientName=" + snap.child("patientName").getValue()
                                + " lat=" + snap.child("latitude").getValue()
                                + " lng=" + snap.child("longitude").getValue());

                        if (!"active".equals(status)) {
                            Log.w(TAG, "DIAG-8b ⚠️ Status is '" + status + "' not 'active'."
                                    + " Another driver may have already accepted it.");
                            processedAlerts.remove(alertId);
                            if (alertId.equals(currentActiveAlert)) currentActiveAlert = null;
                            return;
                        }

                        if (alertId.equals(currentActiveAlert)) {
                            Log.d(TAG, "DIAG-8c ⏭️ Already showing this alert — skip");
                            return;
                        }
                        currentActiveAlert = alertId;

                        String patientName  = snap.child("patientName").getValue(String.class);
                        String patientPhone = snap.child("patientPhone").getValue(String.class);
                        Double lat      = snap.child("latitude").getValue(Double.class);
                        Double lng      = snap.child("longitude").getValue(Double.class);
                        Double distance = snap.child("distance").getValue(Double.class);
                        double latVal  = lat      != null ? lat      : 0.0;
                        double lngVal  = lng      != null ? lng      : 0.0;
                        double distVal = distance != null ? distance : 0.0;

                        // 1. Ringtone
                        try {
                            Intent r = new Intent(AmbulanceSOSListenerService.this,
                                    SOSRingtoneService.class);
                            r.putExtra("alertId", alertId);
                            r.putExtra("patientName", patientName);
                            r.putExtra("latitude", latVal);
                            r.putExtra("longitude", lngVal);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(r);
                            else startService(r);
                            Log.d(TAG, "DIAG-9a ✅ SOSRingtoneService started");
                        } catch (Exception e) {
                            Log.e(TAG, "DIAG-9a ❌ Ringtone FAILED: " + e.getMessage());
                        }

                        // 2. Launch activity after 500 ms
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                Intent ui = new Intent(AmbulanceSOSListenerService.this,
                                        IncomingAmbulanceSOSActivity.class);
                                ui.putExtra("alertId",         alertId);
                                ui.putExtra("notificationKey", notifKey);
                                ui.putExtra("patientName",     patientName);
                                ui.putExtra("patientPhone",    patientPhone);
                                ui.putExtra("latitude",        latVal);
                                ui.putExtra("longitude",       lngVal);
                                ui.putExtra("distance",        distVal);
                                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                startActivity(ui);
                                Log.d(TAG, "DIAG-9b ✅ IncomingAmbulanceSOSActivity LAUNCHED!");
                            } catch (Exception e) {
                                Log.e(TAG, "DIAG-9b ❌ Activity launch FAILED: " + e.getMessage());
                            }
                        }, 500);
                    }

                    @Override
                    public void onCancelled(DatabaseError err) {
                        Log.e(TAG, "DIAG-7 ❌ emergencyAlerts read CANCELLED: "
                                + err.getCode() + " " + err.getMessage());
                        processedAlerts.remove(alertId);
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Background location
    // -----------------------------------------------------------------------
    private void setupAndStartLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "DIAG ⚠️ Location permission not granted");
            return;
        }
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult r) {
                if (r.getLastLocation() == null) return;
                saveLocationToFirebase(
                        r.getLastLocation().getLatitude(),
                        r.getLastLocation().getLongitude());
            }
        };
        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2).build();
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        Log.d(TAG, "DIAG ✅ Background location updates started");
    }

    private void saveLocationToFirebase(double lat, double lng) {
        if (userId == null || (lat == 0.0 && lng == 0.0)) return;
        Map<String, Object> u = new HashMap<>();
        u.put("latitude", lat); u.put("longitude", lng);
        u.put("lastLocationUpdate", System.currentTimeMillis());
        mDatabase.child("users").child(userId).updateChildren(u)
                .addOnSuccessListener(v -> Log.d(TAG, "BG location saved: " + lat + ", " + lng))
                .addOnFailureListener(e -> Log.e(TAG, "BG location FAILED: " + e.getMessage()));
    }

    public static void clearCurrentAlert() { currentActiveAlert = null; }
}
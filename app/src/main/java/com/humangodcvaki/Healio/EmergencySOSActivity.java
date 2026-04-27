package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class EmergencySOSActivity extends AppCompatActivity {

    private static final String TAG = "EmergencySOS";

    // -----------------------------------------------------------------------
    // 🧪 TEST MODE — flip to false before releasing to production!
    // When true: distance check is skipped, nearest available driver is
    // notified regardless of location. Shows "[TEST]" badge on screen.
    // -----------------------------------------------------------------------
    static final boolean TEST_MODE = true;  // ← change to false for production

    private static final int    INITIAL_RADIUS_KM = 20;
    private static final int    RADIUS_INCREMENT  = 10;
    private static final int    MAX_RADIUS_KM     = 100;
    private static final double EARTH_RADIUS_KM   = 6371.0;

    private Button               btnEmergency, btnHighEmergency, btnFindNearbyAmbulance;
    private TextView             tvEmergencyInfo;
    private BottomNavigationView bottomNavigation;

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;

    private double latitude;
    private double longitude;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_sos);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        latitude  = getIntent().getDoubleExtra("latitude",  0.0);
        longitude = getIntent().getDoubleExtra("longitude", 0.0);

        Log.d(TAG, "Activity started with location: " + latitude + ", " + longitude);

        initializeViews();
        setupListeners();

        // ── If location was 0,0 when this screen opened, fetch it fresh from Firebase ──
        if (latitude == 0.0 && longitude == 0.0) {
            refreshLocationFromFirebase();
        }
    }

    // -----------------------------------------------------------------------
    // Refresh location from Firebase in case GPS hadn't fixed when launched
    // -----------------------------------------------------------------------
    private void refreshLocationFromFirebase() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        Double savedLat = snapshot.child("latitude").getValue(Double.class);
                        Double savedLng = snapshot.child("longitude").getValue(Double.class);

                        if (savedLat != null && savedLng != null
                                && !(savedLat == 0.0 && savedLng == 0.0)) {
                            latitude  = savedLat;
                            longitude = savedLng;
                            Log.d(TAG, "Refreshed location from Firebase: " + latitude + ", " + longitude);
                        } else {
                            Log.w(TAG, "Firebase also has no valid location for this patient");
                            Toast.makeText(EmergencySOSActivity.this,
                                    "⚠️ Could not get your location. Please enable GPS before sending SOS.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "Failed to fetch patient location: " + error.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------
    private void initializeViews() {
        btnEmergency           = findViewById(R.id.btnEmergency);
        btnHighEmergency       = findViewById(R.id.btnHighEmergency);
        btnFindNearbyAmbulance = findViewById(R.id.btnFindNearbyAmbulance);
        tvEmergencyInfo        = findViewById(R.id.tvEmergencyInfo);
        bottomNavigation       = findViewById(R.id.bottomNavigation);

        tvEmergencyInfo.setText(
                (TEST_MODE ? "🧪 [TEST MODE — distance check OFF]\n\n" : "") +
                        "⚠️ EMERGENCY SERVICES\n\n" +
                        "🚑  EMERGENCY  — Alerts the nearest available ambulance driver.\n\n" +
                        "🚨  HIGH EMERGENCY  — Instantly alerts the nearest ambulance AND all doctors simultaneously.");
    }

    private void setupListeners() {
        btnEmergency.setOnClickListener(v     -> showConfirmationDialog(false));
        btnHighEmergency.setOnClickListener(v -> showConfirmationDialog(true));

        btnFindNearbyAmbulance.setOnClickListener(v -> {
            Intent intent = new Intent(this, NearbyAmbulanceActivity.class);
            intent.putExtra("latitude",  latitude);
            intent.putExtra("longitude", longitude);
            startActivity(intent);
        });

        bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                startActivity(new Intent(this, PatientDashboardActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_ambulance) {
                return true;
            } else if (id == R.id.nav_doctor) {
                startActivity(new Intent(this, DoctorConsultationActivity.class));
                return true;
            }
            return false;
        });
    }

    // -----------------------------------------------------------------------
    // Confirmation dialog
    // -----------------------------------------------------------------------
    private void showConfirmationDialog(boolean isHighEmergency) {
        // Block SOS if we still have no location
        if (latitude == 0.0 && longitude == 0.0) {
            Toast.makeText(this,
                    "Cannot send SOS — your location is not available yet. Please enable GPS and wait a moment.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String title   = isHighEmergency ? "🚨 HIGH EMERGENCY SOS" : "🚑 EMERGENCY SOS";
        String message = isHighEmergency
                ? "This will IMMEDIATELY alert:\n\n• Nearest available ambulance driver\n• ALL registered doctors\n\nBoth will be notified at the same time.\n\nAre you sure?"
                : "This will alert the NEAREST available ambulance driver.\n\nAre you sure?";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("YES, SEND SOS", (dialog, which) -> sendSOSAlert(isHighEmergency))
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show();
    }

    // -----------------------------------------------------------------------
    // Main SOS dispatch
    // -----------------------------------------------------------------------
    private void sendSOSAlert(boolean isHighEmergency) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = user.getUid();

        mDatabase.child("users").child(userId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        String patientName  = snapshot.child("name").getValue(String.class);
                        String patientPhone = snapshot.child("phone").getValue(String.class);

                        // Final location check — use Firebase-saved coords as fallback
                        double finalLat = latitude;
                        double finalLng = longitude;

                        if (finalLat == 0.0 && finalLng == 0.0) {
                            Double savedLat = snapshot.child("latitude").getValue(Double.class);
                            Double savedLng = snapshot.child("longitude").getValue(Double.class);
                            if (savedLat != null && savedLng != null
                                    && !(savedLat == 0.0 && savedLng == 0.0)) {
                                finalLat = savedLat;
                                finalLng = savedLng;
                                Log.d(TAG, "Using Firebase fallback location: " + finalLat + ", " + finalLng);
                            } else {
                                Toast.makeText(EmergencySOSActivity.this,
                                        "Cannot send SOS — location unavailable. Enable GPS and try again.",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                        }

                        final double alertLat = finalLat;
                        final double alertLng = finalLng;

                        Log.d(TAG, "Sending SOS from: " + alertLat + ", " + alertLng
                                + " | patient: " + patientName
                                + " | highEmergency: " + isHighEmergency);

                        Map<String, Object> alert = new HashMap<>();
                        alert.put("patientId",    userId);
                        alert.put("patientName",  patientName);
                        alert.put("patientPhone", patientPhone);
                        alert.put("latitude",     alertLat);
                        alert.put("longitude",    alertLng);
                        alert.put("timestamp",    System.currentTimeMillis());
                        alert.put("status",       "active");
                        alert.put("priority",     isHighEmergency ? "high" : "normal");

                        String alertId = mDatabase.child("emergencyAlerts").push().getKey();
                        if (alertId == null) return;

                        mDatabase.child("emergencyAlerts").child(alertId).setValue(alert)
                                .addOnSuccessListener(aVoid -> {

                                    // ── Always alert nearest ambulance ──
                                    findAndNotifyNearestAmbulance(
                                            alertId, patientName, patientPhone,
                                            alertLat, alertLng, INITIAL_RADIUS_KM);

                                    // ── HIGH EMERGENCY: ALSO alert all doctors simultaneously ──
                                    if (isHighEmergency) {
                                        sendNotificationsToDoctors(
                                                alertId, patientName, patientPhone,
                                                alertLat, alertLng);
                                    }

                                    showSuccessDialog(isHighEmergency);
                                })
                                .addOnFailureListener(e ->
                                        Toast.makeText(EmergencySOSActivity.this,
                                                "Failed to send SOS: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Toast.makeText(EmergencySOSActivity.this,
                                "Failed to retrieve user data", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Nearest-ambulance search  ← KEY FIX: scan ALL users, no index filter
    // -----------------------------------------------------------------------
    /**
     * WHY we scan all users instead of filtering by userType in the query:
     *
     * Firebase's orderByChild("userType").equalTo("ambulance_driver") requires a
     * manually created index in database.rules.json. Without it the SDK falls
     * back to client-side filtering but the query itself can return empty on some
     * SDK versions. Scanning all users and filtering in Java is slightly slower
     * but 100% reliable for a small user base.
     */
    private void findAndNotifyNearestAmbulance(String alertId, String patientName,
                                               String patientPhone,
                                               double patientLat, double patientLng,
                                               int radiusKm) {
        if (radiusKm > MAX_RADIUS_KM) {
            Log.w(TAG, "❌ No ambulance found within " + MAX_RADIUS_KM + " km");
            runOnUiThread(() -> Toast.makeText(this,
                    "No ambulance found within " + MAX_RADIUS_KM + " km. Please call 112.",
                    Toast.LENGTH_LONG).show());
            return;
        }

        Log.d(TAG, "🔍 Searching radius=" + radiusKm + " km from "
                + patientLat + ", " + patientLng);

        mDatabase.child("users")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {

                        int totalUsers        = 0;
                        int ambulanceDrivers  = 0;
                        int availableDrivers  = 0;
                        int locatedDrivers    = 0;

                        String bestDriverId = null;
                        double bestDistance = Double.MAX_VALUE;

                        for (DataSnapshot userSnap : snapshot.getChildren()) {
                            totalUsers++;
                            String userType = userSnap.child("userType").getValue(String.class);

                            if (!"ambulance_driver".equals(userType)) continue;
                            ambulanceDrivers++;

                            String driverId = userSnap.getKey();
                            Boolean available = userSnap.child("available").getValue(Boolean.class);

                            if (available == null || !available) {
                                Log.d(TAG, "  Driver " + driverId
                                        + " → SKIPPED (available=" + available + ")");
                                continue;
                            }
                            availableDrivers++;

                            Double dLat = userSnap.child("latitude").getValue(Double.class);
                            Double dLng = userSnap.child("longitude").getValue(Double.class);

                            if (dLat == null || dLng == null) {
                                Log.w(TAG, "  Driver " + driverId
                                        + " → SKIPPED (no lat/lng in Firebase)");
                                continue;
                            }
                            if (dLat == 0.0 && dLng == 0.0) {
                                Log.w(TAG, "  Driver " + driverId
                                        + " → SKIPPED (lat/lng is still 0,0 — GPS not fixed)");
                                continue;
                            }
                            locatedDrivers++;

                            double dist = calculateDistance(patientLat, patientLng, dLat, dLng);
                            Log.d(TAG, "  Driver " + driverId
                                    + " → dist=" + String.format("%.2f", dist) + " km"
                                    + " location=(" + dLat + ", " + dLng + ")");

                            if ((TEST_MODE || dist <= radiusKm) && dist < bestDistance) {
                                bestDistance = dist;
                                bestDriverId = driverId;
                            }
                        }

                        Log.d(TAG, "Search done (radius=" + radiusKm + " km): "
                                + "totalUsers=" + totalUsers
                                + " | ambulanceDrivers=" + ambulanceDrivers
                                + " | available=" + availableDrivers
                                + " | hasValidGPS=" + locatedDrivers
                                + " | winner=" + (bestDriverId != null ? bestDriverId : "NONE"));

                        if (bestDriverId != null) {
                            sendAmbulanceNotification(alertId, bestDriverId, patientName,
                                    patientPhone, patientLat, patientLng, bestDistance, radiusKm);
                        } else {
                            int nextRadius = radiusKm + RADIUS_INCREMENT;
                            Log.d(TAG, "Expanding search to " + nextRadius + " km");
                            findAndNotifyNearestAmbulance(alertId, patientName, patientPhone,
                                    patientLat, patientLng, nextRadius);
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "User scan cancelled: " + error.getMessage());
                        Toast.makeText(EmergencySOSActivity.this,
                                "Failed to search for ambulances", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void sendAmbulanceNotification(String alertId, String driverId,
                                           String patientName, String patientPhone,
                                           double patientLat, double patientLng,
                                           double distance, int radiusKm) {
        Map<String, Object> notif = new HashMap<>();
        notif.put("type",         "emergency_ambulance");
        notif.put("alertId",      alertId);
        notif.put("patientName",  patientName);
        notif.put("patientPhone", patientPhone);
        notif.put("latitude",     patientLat);
        notif.put("longitude",    patientLng);
        notif.put("distance",     distance);
        notif.put("timestamp",    System.currentTimeMillis());
        notif.put("accepted",     false);
        notif.put("read",         false);

        mDatabase.child("notifications").child(driverId).push()
                .setValue(notif)
                .addOnSuccessListener(v ->
                        Log.d(TAG, "✅ Ambulance notified: driver=" + driverId
                                + " dist=" + String.format("%.2f", distance) + " km"
                                + " radius=" + radiusKm + " km"))
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ Failed to notify driver: " + e.getMessage()));
    }

    // -----------------------------------------------------------------------
    // Doctor notifications (high-emergency) — NOW includes full patient info
    // -----------------------------------------------------------------------
    /**
     * Sends an "emergency" notification to every registered doctor simultaneously.
     * Includes patientName, patientPhone, and coordinates so IncomingSOSActivity
     * can display all details without an extra Firebase lookup.
     */
    private void sendNotificationsToDoctors(String alertId, String patientName,
                                            String patientPhone,
                                            double patientLat, double patientLng) {
        mDatabase.child("users")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        int count = 0;
                        for (DataSnapshot userSnap : snapshot.getChildren()) {
                            String userType = userSnap.child("userType").getValue(String.class);
                            if (!"doctor".equals(userType)) continue;

                            String doctorId = userSnap.getKey();

                            Map<String, Object> notif = new HashMap<>();
                            notif.put("type",         "emergency");
                            notif.put("alertId",      alertId);
                            // ── NEW: include full patient details ──
                            notif.put("patientName",  patientName  != null ? patientName  : "Unknown");
                            notif.put("patientPhone", patientPhone != null ? patientPhone : "N/A");
                            notif.put("latitude",     patientLat);
                            notif.put("longitude",    patientLng);
                            notif.put("timestamp",    System.currentTimeMillis());
                            notif.put("read",         false);

                            mDatabase.child("notifications").child(doctorId).push()
                                    .setValue(notif)
                                    .addOnSuccessListener(v ->
                                            Log.d(TAG, "✅ Doctor notified: " + doctorId))
                                    .addOnFailureListener(e ->
                                            Log.e(TAG, "❌ Failed to notify doctor " + doctorId
                                                    + ": " + e.getMessage()));
                            count++;
                        }
                        Log.d(TAG, "Notified " + count + " doctor(s) for HIGH EMERGENCY alertId=" + alertId);
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "Doctor notification failed: " + error.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------------
    // Haversine distance
    // -----------------------------------------------------------------------
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // -----------------------------------------------------------------------
    // Success dialog
    // -----------------------------------------------------------------------
    private void showSuccessDialog(boolean isHighEmergency) {
        String msg = isHighEmergency
                ? "🚨 HIGH EMERGENCY alerts sent to:\n\n• Nearest ambulance driver\n• All registered doctors\n\nBoth have been notified simultaneously.\nHelp is on the way!"
                : "🚑 Your nearest available ambulance driver has been alerted.\n\nHelp is on the way!";

        new AlertDialog.Builder(this)
                .setTitle("✅ SOS Alert Sent!")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNavigation.setSelectedItemId(R.id.nav_ambulance);
    }
}
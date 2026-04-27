package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Activity shown to an ambulance driver when they receive an emergency SOS.
 *
 * ACCEPT  -> marks alert as "ambulance_accepted", marks driver unavailable,
 *            opens EmergencyMapActivity so the driver can navigate to the patient.
 *
 * DECLINE -> logs the decline and dismisses.
 */
public class IncomingAmbulanceSOSActivity extends AppCompatActivity {

    private static final String TAG = "IncomingAmbulanceSOS";

    private TextView tvPatientInfo, tvAlertInfo;
    private Button   btnAccept, btnDecline;

    private String alertId;
    private String patientName;
    private String patientPhone;
    private double latitude;
    private double longitude;
    private double distanceKm;
    private String notificationKey;

    private DatabaseReference mDatabase;
    private FirebaseAuth      mAuth;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show on lock screen — same pattern as IncomingSOSActivity
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            // Keep screen on (API 27+ uses WindowManager directly for this flag)
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   |
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(R.layout.activity_incoming_ambulance_sos);

        mDatabase = FirebaseDatabase.getInstance().getReference();
        mAuth     = FirebaseAuth.getInstance();

        extractExtras(getIntent());
        initializeViews();
    }

    // -----------------------------------------------------------------------
    // Extract intent extras (shared between onCreate and onNewIntent)
    // -----------------------------------------------------------------------
    private void extractExtras(Intent intent) {
        alertId         = intent.getStringExtra("alertId");
        patientName     = intent.getStringExtra("patientName");
        patientPhone    = intent.getStringExtra("patientPhone");
        latitude        = intent.getDoubleExtra("latitude",   0.0);
        longitude       = intent.getDoubleExtra("longitude",  0.0);
        distanceKm      = intent.getDoubleExtra("distance",   0.0);
        notificationKey = intent.getStringExtra("notificationKey");
    }

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private void initializeViews() {
        tvPatientInfo = findViewById(R.id.tvPatientInfo);
        tvAlertInfo   = findViewById(R.id.tvAlertInfo);
        btnAccept     = findViewById(R.id.btnAccept);
        btnDecline    = findViewById(R.id.btnDecline);

        tvPatientInfo.setText(patientName != null ? patientName : "Unknown Patient");

        String distText = distanceKm > 0
                ? String.format(Locale.US, "\nDistance: %.2f km", distanceKm)
                : "";

        tvAlertInfo.setText("🚨 EMERGENCY SOS ALERT\n\n" +
                "A patient requires immediate assistance!\n\n" +
                "Phone: "     + (patientPhone != null ? patientPhone : "N/A") +
                "\nLocation: " + String.format(Locale.US, "%.6f, %.6f", latitude, longitude) +
                distText);

        btnAccept.setOnClickListener(v  -> acceptAlert());
        btnDecline.setOnClickListener(v -> declineAlert());
    }

    // -----------------------------------------------------------------------
    // ACCEPT
    // -----------------------------------------------------------------------
    private void acceptAlert() {
        if (mAuth.getCurrentUser() == null || alertId == null) {
            Toast.makeText(this, "Error: cannot accept alert", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        btnAccept.setEnabled(false);
        btnDecline.setEnabled(false);

        String driverId = mAuth.getCurrentUser().getUid();

        Map<String, Object> updates = new HashMap<>();
        updates.put("ambulanceStatus",    "accepted");
        updates.put("ambulanceDriverId",  driverId);
        updates.put("ambulanceAcceptedAt",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

        mDatabase.child("emergencyAlerts").child(alertId)
                .updateChildren(updates)
                .addOnSuccessListener(aVoid -> {

                    // Mark notification read + unblock future alerts
                    markNotificationRead();
                    AmbulanceSOSListenerService.clearCurrentAlert();

                    // Mark driver unavailable while on a job
                    mDatabase.child("users").child(driverId)
                            .child("available").setValue(false);

                    stopRingtoneService();

                    // Open EmergencyMapActivity with patient coordinates
                    Intent mapIntent = new Intent(IncomingAmbulanceSOSActivity.this,
                            EmergencyMapActivity.class);
                    mapIntent.putExtra("alertId",      alertId);
                    mapIntent.putExtra("patientName",  patientName);
                    mapIntent.putExtra("patientPhone", patientPhone);
                    mapIntent.putExtra("latitude",     latitude);
                    mapIntent.putExtra("longitude",    longitude);
                    mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(mapIntent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to accept: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    btnAccept.setEnabled(true);
                    btnDecline.setEnabled(true);
                });
    }

    // -----------------------------------------------------------------------
    // DECLINE
    // -----------------------------------------------------------------------
    private void declineAlert() {
        if (mAuth.getCurrentUser() == null || alertId == null) {
            finish();
            return;
        }

        String driverId = mAuth.getCurrentUser().getUid();

        Map<String, Object> declineData = new HashMap<>();
        declineData.put("driverId",   driverId);
        declineData.put("declinedAt",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

        mDatabase.child("emergencyAlerts").child(alertId)
                .child("ambulanceDeclinedBy").child(driverId)
                .setValue(declineData);

        markNotificationRead();
        AmbulanceSOSListenerService.clearCurrentAlert();
        Toast.makeText(this, "Alert declined", Toast.LENGTH_SHORT).show();
        stopRingtoneService();
        finish();
    }

    // -----------------------------------------------------------------------
    // Handle a new alert arriving while this screen is already open
    // — same pattern as IncomingSOSActivity#onNewIntent
    // -----------------------------------------------------------------------
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String newAlertId = intent.getStringExtra("alertId");
        if (newAlertId != null && !newAlertId.equals(alertId)) {
            extractExtras(intent);
            initializeViews();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private void markNotificationRead() {
        if (notificationKey == null || mAuth.getCurrentUser() == null) return;
        mDatabase.child("notifications")
                .child(mAuth.getCurrentUser().getUid())
                .child(notificationKey)
                .child("read").setValue(true);
    }

    private void stopRingtoneService() {
        try {
            Intent i = new Intent(this, SOSRingtoneService.class);
            i.setAction(SOSRingtoneService.ACTION_STOP);
            stopService(i);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error stopping ringtone: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "Please accept or decline the alert", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingtoneService();
    }
}
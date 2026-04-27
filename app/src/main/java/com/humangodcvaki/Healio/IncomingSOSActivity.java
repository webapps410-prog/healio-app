package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Activity shown when doctor receives an SOS alert.
 * Accept  → creates a video call session and connects doctor + patient via VideoCallActivity.
 * Decline → logs decline and dismisses.
 */
public class IncomingSOSActivity extends AppCompatActivity {

    private static final String TAG = "IncomingSOSActivity";

    private TextView tvPatientName, tvAlertInfo;
    private Button btnAccept, btnDecline;

    private String alertId;
    private String patientName;
    private String patientPhone;
    private double latitude;
    private double longitude;

    private DatabaseReference mDatabase;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show on lock screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        setContentView(R.layout.activity_incoming_sos);

        mDatabase = FirebaseDatabase.getInstance().getReference();
        mAuth = FirebaseAuth.getInstance();

        alertId     = getIntent().getStringExtra("alertId");
        patientName = getIntent().getStringExtra("patientName");
        patientPhone = getIntent().getStringExtra("patientPhone");
        latitude    = getIntent().getDoubleExtra("latitude", 0.0);
        longitude   = getIntent().getDoubleExtra("longitude", 0.0);

        initializeViews();
    }

    private void initializeViews() {
        tvPatientName = findViewById(R.id.tvPatientName);
        tvAlertInfo   = findViewById(R.id.tvAlertInfo);
        btnAccept     = findViewById(R.id.btnAccept);
        btnDecline    = findViewById(R.id.btnDecline);

        tvPatientName.setText(patientName != null ? patientName : "Unknown Patient");
        tvAlertInfo.setText("🚨 EMERGENCY SOS ALERT\n\n" +
                "This patient needs immediate medical assistance!\n\n" +
                "Phone: " + (patientPhone != null ? patientPhone : "N/A") +
                "\nLocation: " + String.format(Locale.US, "%.6f, %.6f", latitude, longitude));

        btnAccept.setOnClickListener(v -> acceptAlert());
        btnDecline.setOnClickListener(v -> declineAlert());
    }

    // -----------------------------------------------------------------------
    // ACCEPT — creates video call, notifies patient, opens VideoCallActivity
    // -----------------------------------------------------------------------
    private void acceptAlert() {
        if (mAuth.getCurrentUser() == null || alertId == null) {
            Toast.makeText(this, "Error: Unable to accept alert", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        btnAccept.setEnabled(false);
        btnDecline.setEnabled(false);

        String doctorId = mAuth.getCurrentUser().getUid();

        // 1. Mark the emergency alert as accepted
        Map<String, Object> alertUpdates = new HashMap<>();
        alertUpdates.put("status",      "accepted");
        alertUpdates.put("acceptedBy",  doctorId);
        alertUpdates.put("acceptedAt",  new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date()));

        mDatabase.child("emergencyAlerts").child(alertId)
                .updateChildren(alertUpdates)
                .addOnSuccessListener(aVoid -> {

                    stopRingtoneService();
                    SOSListenerService.clearCurrentAlert();

                    // 2. Fetch doctor name + patient ID from Firebase
                    mDatabase.child("emergencyAlerts").child(alertId)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(DataSnapshot alertSnapshot) {
                                    String patientId = alertSnapshot.child("patientId")
                                            .getValue(String.class);

                                    // Fetch doctor's name
                                    mDatabase.child("users").child(doctorId)
                                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                                @Override
                                                public void onDataChange(DataSnapshot doctorSnapshot) {
                                                    String dName = doctorSnapshot.child("name")
                                                            .getValue(String.class);
                                                    if (dName == null) dName = "Doctor";

                                                    // 3. Create video call record in Firebase
                                                    String callId = mDatabase.child("videoCalls").push().getKey();
                                                    if (callId == null) {
                                                        Toast.makeText(IncomingSOSActivity.this,
                                                                "Failed to create call", Toast.LENGTH_SHORT).show();
                                                        return;
                                                    }

                                                    Map<String, Object> callData = new HashMap<>();
                                                    callData.put("callId",    callId);
                                                    callData.put("doctorId",  doctorId);
                                                    callData.put("alertId",   alertId);
                                                    callData.put("status",    "ringing");
                                                    callData.put("timestamp", System.currentTimeMillis());

                                                    final String finalDName = dName;

                                                    mDatabase.child("videoCalls").child(callId)
                                                            .setValue(callData)
                                                            .addOnSuccessListener(unused -> {

                                                                // 4. Notify patient to open VideoCallActivity
                                                                if (patientId != null) {
                                                                    Map<String, Object> notif = new HashMap<>();
                                                                    notif.put("type",       "emergency_video_call");
                                                                    notif.put("callId",     callId);
                                                                    notif.put("doctorId",   doctorId);
                                                                    notif.put("doctorName", finalDName);
                                                                    notif.put("timestamp",  System.currentTimeMillis());
                                                                    notif.put("read",       false);

                                                                    mDatabase.child("notifications")
                                                                            .child(patientId).push()
                                                                            .setValue(notif);
                                                                }

                                                                // 5. Open VideoCallActivity — doctor is the WebRTC initiator
                                                                Intent videoIntent = new Intent(
                                                                        IncomingSOSActivity.this,
                                                                        VideoCallActivity.class);
                                                                videoIntent.putExtra("callId",      callId);
                                                                videoIntent.putExtra("doctorId",    doctorId);
                                                                videoIntent.putExtra("doctorName",  finalDName);
                                                                videoIntent.putExtra("isInitiator", true);
                                                                videoIntent.putExtra("isEmergency", true);
                                                                videoIntent.addFlags(
                                                                        Intent.FLAG_ACTIVITY_NEW_TASK |
                                                                                Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                                startActivity(videoIntent);
                                                                finish();
                                                            })
                                                            .addOnFailureListener(e -> {
                                                                Toast.makeText(IncomingSOSActivity.this,
                                                                        "Failed to start call: " + e.getMessage(),
                                                                        Toast.LENGTH_SHORT).show();
                                                                btnAccept.setEnabled(true);
                                                                btnDecline.setEnabled(true);
                                                            });
                                                }

                                                @Override
                                                public void onCancelled(DatabaseError error) {
                                                    Toast.makeText(IncomingSOSActivity.this,
                                                            "Failed to load doctor data", Toast.LENGTH_SHORT).show();
                                                    btnAccept.setEnabled(true);
                                                    btnDecline.setEnabled(true);
                                                }
                                            });
                                }

                                @Override
                                public void onCancelled(DatabaseError error) {
                                    Toast.makeText(IncomingSOSActivity.this,
                                            "Failed to load alert data", Toast.LENGTH_SHORT).show();
                                    btnAccept.setEnabled(true);
                                    btnDecline.setEnabled(true);
                                }
                            });
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to accept alert: " + e.getMessage(),
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

        String doctorId = mAuth.getCurrentUser().getUid();

        Map<String, Object> declineData = new HashMap<>();
        declineData.put("doctorId",   doctorId);
        declineData.put("declinedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date()));

        mDatabase.child("emergencyAlerts").child(alertId)
                .child("declinedBy").child(doctorId)
                .setValue(declineData);

        Toast.makeText(this, "Alert declined", Toast.LENGTH_SHORT).show();

        stopRingtoneService();
        SOSListenerService.clearCurrentAlert();

        finish();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private void stopRingtoneService() {
        try {
            Intent serviceIntent = new Intent(this, SOSRingtoneService.class);
            serviceIntent.setAction(SOSRingtoneService.ACTION_STOP);
            stopService(serviceIntent);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error stopping ringtone service: " + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String newAlertId = intent.getStringExtra("alertId");
        if (newAlertId != null && !newAlertId.equals(alertId)) {
            alertId     = newAlertId;
            patientName = intent.getStringExtra("patientName");
            patientPhone = intent.getStringExtra("patientPhone");
            latitude    = intent.getDoubleExtra("latitude", 0.0);
            longitude   = intent.getDoubleExtra("longitude", 0.0);
            initializeViews();
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
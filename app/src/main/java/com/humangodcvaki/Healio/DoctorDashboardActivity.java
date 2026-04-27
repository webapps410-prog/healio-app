package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class DoctorDashboardActivity extends AppCompatActivity {

    private TextView tvWelcome, tvUserInfo;
    private Button btnAppointments, btnPatients, btnProfile, btnLogout;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doctor_dashboard);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        tvWelcome = findViewById(R.id.tvWelcome);
        tvUserInfo = findViewById(R.id.tvUserInfo);
        btnAppointments = findViewById(R.id.btnAppointments);
        btnPatients = findViewById(R.id.btnPatients);
        btnProfile = findViewById(R.id.btnProfile);
        btnLogout = findViewById(R.id.btnLogout);

        loadUserData();
        startSOSListenerService();

        btnAppointments.setOnClickListener(v -> {
            // Navigate to doctor's appointment view
            Intent intent = new Intent(this, AppointmentActivity.class);
            startActivity(intent);
        });

        btnPatients.setOnClickListener(v ->
                Toast.makeText(this, "Patients list feature coming soon!", Toast.LENGTH_SHORT).show());

        btnProfile.setOnClickListener(v ->
                Toast.makeText(this, "Profile feature coming soon!", Toast.LENGTH_SHORT).show());

        btnLogout.setOnClickListener(v -> logout());
    }

    private void loadUserData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            String userId = user.getUid();
            mDatabase.child("users").child(userId).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    if (dataSnapshot.exists()) {
                        String name = dataSnapshot.child("name").getValue(String.class);
                        String specialization = dataSnapshot.child("specialization").getValue(String.class);
                        String hospital = dataSnapshot.child("hospitalName").getValue(String.class);
                        String experience = dataSnapshot.child("yearsExperience").getValue(String.class);

                        tvWelcome.setText("Dr. " + name);
                        tvUserInfo.setText("Specialization: " + specialization +
                                "\nHospital: " + hospital +
                                "\nExperience: " + experience + " years" +
                                "\n\n🔔 SOS Alerts: Active");
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    Toast.makeText(DoctorDashboardActivity.this,
                            "Failed to load data", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Start background service to listen for SOS alerts
     */
    private void startSOSListenerService() {
        Intent serviceIntent = new Intent(this, SOSListenerService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "SOS Alert System Activated", Toast.LENGTH_SHORT).show();
    }

    private void logout() {
        // Stop SOS listener service
        Intent serviceIntent = new Intent(this, SOSListenerService.class);
        stopService(serviceIntent);

        mAuth.signOut();
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, SignInActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Don't stop service here - it should keep running
    }
}
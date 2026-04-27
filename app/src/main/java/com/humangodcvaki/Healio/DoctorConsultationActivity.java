package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DoctorConsultationActivity extends AppCompatActivity {

    private RecyclerView recyclerViewDoctors;
    private ProgressBar progressBar;
    private TextView tvNoData;
    private BottomNavigationView bottomNavigation;
    private DoctorConsultAdapter adapter;
    private List<Doctor> doctorList;
    private DatabaseReference mDatabase;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doctor_consultation);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        recyclerViewDoctors = findViewById(R.id.recyclerViewDoctors);
        progressBar = findViewById(R.id.progressBar);
        tvNoData = findViewById(R.id.tvNoData);
        bottomNavigation = findViewById(R.id.bottomNavigation);

        doctorList = new ArrayList<>();
        recyclerViewDoctors.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DoctorConsultAdapter(doctorList);
        recyclerViewDoctors.setAdapter(adapter);

        setupBottomNavigation();
        loadDoctors();
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_home) {
                startActivity(new Intent(this, PatientDashboardActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_ambulance) {
                startActivity(new Intent(this, EmergencySOSActivity.class));
                return true;
            } else if (id == R.id.nav_doctor) {
                // Already on doctor page
                return true;
            }
            return false;
        });
    }

    private void loadDoctors() {
        progressBar.setVisibility(View.VISIBLE);

        mDatabase.child("users").orderByChild("userType").equalTo("doctor")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        progressBar.setVisibility(View.GONE);
                        doctorList.clear();

                        for (DataSnapshot doctorSnapshot : snapshot.getChildren()) {
                            String doctorId = doctorSnapshot.getKey();
                            String name = doctorSnapshot.child("name").getValue(String.class);
                            String specialization = doctorSnapshot.child("specialization").getValue(String.class);
                            String hospital = doctorSnapshot.child("hospitalName").getValue(String.class);
                            String experience = doctorSnapshot.child("yearsExperience").getValue(String.class);
                            Boolean verified = doctorSnapshot.child("verified").getValue(Boolean.class);

                            if (verified != null && verified) {
                                Doctor doctor = new Doctor(doctorId, name, specialization,
                                        hospital, experience);
                                doctorList.add(doctor);
                            }
                        }

                        if (doctorList.isEmpty()) {
                            tvNoData.setVisibility(View.VISIBLE);
                        } else {
                            tvNoData.setVisibility(View.GONE);
                        }

                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(DoctorConsultationActivity.this,
                                "Failed to load doctors", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // Doctor Model
    static class Doctor {
        String id;
        String name;
        String specialization;
        String hospital;
        String experience;

        Doctor(String id, String name, String specialization, String hospital, String experience) {
            this.id = id;
            this.name = name;
            this.specialization = specialization;
            this.hospital = hospital;
            this.experience = experience;
        }
    }

    // Adapter
    class DoctorConsultAdapter extends RecyclerView.Adapter<DoctorConsultAdapter.DoctorViewHolder> {
        private List<Doctor> doctors;

        DoctorConsultAdapter(List<Doctor> doctors) {
            this.doctors = doctors;
        }

        @NonNull
        @Override
        public DoctorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_doctor_consult, parent, false);
            return new DoctorViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull DoctorViewHolder holder, int position) {
            holder.bind(doctors.get(position));
        }

        @Override
        public int getItemCount() {
            return doctors.size();
        }

        class DoctorViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvSpecialization, tvHospital, tvExperience;
            Button btnVideoCall, btnChat;

            DoctorViewHolder(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tvDoctorName);
                tvSpecialization = itemView.findViewById(R.id.tvSpecialization);
                tvHospital = itemView.findViewById(R.id.tvHospital);
                tvExperience = itemView.findViewById(R.id.tvExperience);
                btnVideoCall = itemView.findViewById(R.id.btnVideoCall);
                btnChat = itemView.findViewById(R.id.btnChat);
            }

            void bind(Doctor doctor) {
                tvName.setText("Dr. " + doctor.name);
                tvSpecialization.setText("Specialization: " + doctor.specialization);
                tvHospital.setText("Hospital: " + doctor.hospital);
                tvExperience.setText("Experience: " + doctor.experience + " years");

                // Video Call button - will use WebRTC
                btnVideoCall.setOnClickListener(v -> {
                    startVideoCall(doctor);
                });

                // Chat button
                btnChat.setOnClickListener(v -> {
                    startChat(doctor);
                });
            }
        }
    }

    private void startVideoCall(Doctor doctor) {
        // Create a call request
        String patientId = mAuth.getCurrentUser().getUid();
        String callId = mDatabase.child("calls").push().getKey();

        if (callId != null) {
            Map<String, Object> callRequest = new HashMap<>();
            callRequest.put("patientId", patientId);
            callRequest.put("doctorId", doctor.id);
            callRequest.put("doctorName", doctor.name);
            callRequest.put("type", "video");
            callRequest.put("status", "calling");
            callRequest.put("timestamp", System.currentTimeMillis());

            mDatabase.child("calls").child(callId).setValue(callRequest)
                    .addOnSuccessListener(aVoid -> {
                        // Navigate to WebRTC video call activity
                        Intent intent = new Intent(this, VideoCallActivity.class);
                        intent.putExtra("callId", callId);
                        intent.putExtra("doctorId", doctor.id);
                        intent.putExtra("doctorName", doctor.name);
                        intent.putExtra("isInitiator", true);
                        startActivity(intent);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Failed to initiate call", Toast.LENGTH_SHORT).show();
                    });
        }
    }

    private void startChat(Doctor doctor) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("doctorId", doctor.id);
        intent.putExtra("doctorName", doctor.name);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNavigation.setSelectedItemId(R.id.nav_doctor);
    }
}
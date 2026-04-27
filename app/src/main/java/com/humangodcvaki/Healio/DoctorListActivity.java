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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DoctorListActivity extends AppCompatActivity {

    private RecyclerView    recyclerViewDoctors;
    private ProgressBar     progressBar;
    private TextView        tvNoDoctors;
    private DoctorAdapter   adapter;
    private final List<Doctor> doctorList = new ArrayList<>();

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;

    // All possible time slots 10am–5pm, 1 hour each
    private static final String[] ALL_SLOTS = {
            "10:00 AM", "11:00 AM", "12:00 PM",
            "01:00 PM", "02:00 PM", "03:00 PM",
            "04:00 PM", "05:00 PM"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doctor_list);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        recyclerViewDoctors = findViewById(R.id.recyclerViewDoctors);
        progressBar         = findViewById(R.id.progressBar);
        tvNoDoctors         = findViewById(R.id.tvNoDoctors);

        adapter = new DoctorAdapter(doctorList);
        recyclerViewDoctors.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewDoctors.setAdapter(adapter);

        loadDoctors();
    }

    // ── Load all verified doctors ──────────────────────────────────────────
    private void loadDoctors() {
        progressBar.setVisibility(View.VISIBLE);

        mDatabase.child("users")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        progressBar.setVisibility(View.GONE);
                        doctorList.clear();

                        for (DataSnapshot ds : snapshot.getChildren()) {
                            String userType = ds.child("userType").getValue(String.class);
                            if (!"doctor".equals(userType)) continue;

                            Boolean verified = ds.child("verified").getValue(Boolean.class);
                            if (!Boolean.TRUE.equals(verified)) continue;

                            doctorList.add(new Doctor(
                                    ds.getKey(),
                                    ds.child("name").getValue(String.class),
                                    ds.child("specialization").getValue(String.class),
                                    ds.child("hospitalName").getValue(String.class),
                                    ds.child("yearsExperience").getValue(String.class),
                                    ds.child("phone").getValue(String.class)
                            ));
                        }

                        if (doctorList.isEmpty()) {
                            tvNoDoctors.setVisibility(View.VISIBLE);
                        } else {
                            tvNoDoctors.setVisibility(View.GONE);
                        }
                        adapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(DoctorListActivity.this,
                                "Failed to load doctors", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Step 1: Pick a date (today + next 6 days) ─────────────────────────
    private void showDatePicker(Doctor doctor) {
        SimpleDateFormat sdf       = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
        SimpleDateFormat storeFmt  = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        Calendar         cal       = Calendar.getInstance();

        String[] dateLabels = new String[7];
        String[] dateKeys   = new String[7];   // "yyyy-MM-dd" used as Firebase key

        for (int i = 0; i < 7; i++) {
            dateLabels[i] = sdf.format(cal.getTime());
            dateKeys[i]   = storeFmt.format(cal.getTime());
            cal.add(Calendar.DATE, 1);
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Appointment Date")
                .setItems(dateLabels, (dialog, which) ->
                        showSlotPicker(doctor, dateLabels[which], dateKeys[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Step 2: Load booked slots for that date, show available ones ──────
    private void showSlotPicker(Doctor doctor, String dateLabel, String dateKey) {
        // Show loading while we check Firebase
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
                .setTitle("Loading available slots...")
                .setMessage("Please wait...")
                .setCancelable(false)
                .create();
        loadingDialog.show();

        // Query all appointments for this doctor on this date
        mDatabase.child("appointments")
                .orderByChild("doctorId")
                .equalTo(doctor.id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        loadingDialog.dismiss();

                        // Collect booked slots for this date
                        Set<String> bookedSlots = new HashSet<>();
                        for (DataSnapshot ds : snapshot.getChildren()) {
                            String apptDate   = ds.child("date").getValue(String.class);
                            String apptTime   = ds.child("time").getValue(String.class);
                            String apptStatus = ds.child("status").getValue(String.class);

                            // Only count active bookings (not cancelled)
                            if (dateKey.equals(apptDate)
                                    && apptTime != null
                                    && !"cancelled".equals(apptStatus)) {
                                bookedSlots.add(apptTime);
                            }
                        }

                        // Build display list — show which slots are taken
                        String[] displaySlots = new String[ALL_SLOTS.length];
                        boolean[] available   = new boolean[ALL_SLOTS.length];
                        int availableCount    = 0;

                        for (int i = 0; i < ALL_SLOTS.length; i++) {
                            if (bookedSlots.contains(ALL_SLOTS[i])) {
                                displaySlots[i] = ALL_SLOTS[i] + "  — Booked";
                                available[i]    = false;
                            } else {
                                displaySlots[i] = ALL_SLOTS[i] + "  ✓ Available";
                                available[i]    = true;
                                availableCount++;
                            }
                        }

                        if (availableCount == 0) {
                            new AlertDialog.Builder(DoctorListActivity.this)
                                    .setTitle("No Slots Available")
                                    .setMessage("All slots for " + dateLabel
                                            + " are booked.\n\nPlease choose a different date.")
                                    .setPositiveButton("Choose Another Date",
                                            (d, w) -> showDatePicker(doctor))
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            return;
                        }

                        // Show slot dialog — only available slots are clickable
                        new AlertDialog.Builder(DoctorListActivity.this)
                                .setTitle("Available Slots — " + dateLabel)
                                .setItems(displaySlots, (dialog, which) -> {
                                    if (!available[which]) {
                                        Toast.makeText(DoctorListActivity.this,
                                                "This slot is already booked. Please choose another.",
                                                Toast.LENGTH_SHORT).show();
                                        // Re-show the same dialog
                                        showSlotPicker(doctor, dateLabel, dateKey);
                                        return;
                                    }
                                    confirmBooking(doctor, dateLabel, dateKey,
                                            ALL_SLOTS[which]);
                                })
                                .setNegativeButton("Back", (d, w) -> showDatePicker(doctor))
                                .show();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        loadingDialog.dismiss();
                        Toast.makeText(DoctorListActivity.this,
                                "Failed to load slots", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Step 3: Confirm booking ───────────────────────────────────────────
    private void confirmBooking(Doctor doctor, String dateLabel,
                                String dateKey, String slot) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm Appointment")
                .setMessage("Book appointment with:\n\n"
                        + "Doctor: Dr. " + doctor.name + "\n"
                        + "Specialization: " + doctor.specialization + "\n"
                        + "Hospital: " + doctor.hospital + "\n"
                        + "Date: " + dateLabel + "\n"
                        + "Time: " + slot + "\n\n"
                        + "Confirm booking?")
                .setPositiveButton("Confirm", (d, w) ->
                        saveBooking(doctor, dateLabel, dateKey, slot))
                .setNegativeButton("Back", (d, w) ->
                        showSlotPicker(doctor, dateLabel, dateKey))
                .setCancelable(false)
                .show();
    }

    // ── Step 4: Save to Firebase ──────────────────────────────────────────
    private void saveBooking(Doctor doctor, String dateLabel,
                             String dateKey, String slot) {
        if (mAuth.getCurrentUser() == null) {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        String patientId = mAuth.getCurrentUser().getUid();

        // Fetch patient name before saving
        mDatabase.child("users").child(patientId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String patientName  = snapshot.child("name").getValue(String.class);
                        String patientPhone = snapshot.child("phone").getValue(String.class);

                        String appointmentId = mDatabase.child("appointments").push().getKey();
                        if (appointmentId == null) return;

                        Map<String, Object> appt = new HashMap<>();
                        appt.put("appointmentId", appointmentId);
                        appt.put("doctorId",       doctor.id);
                        appt.put("doctorName",     doctor.name);
                        appt.put("specialization", doctor.specialization);
                        appt.put("hospital",       doctor.hospital);
                        appt.put("patientId",      patientId);
                        appt.put("patientName",    patientName  != null ? patientName  : "Patient");
                        appt.put("patientPhone",   patientPhone != null ? patientPhone : "N/A");
                        appt.put("date",           dateKey);       // "yyyy-MM-dd" for filtering
                        appt.put("dateLabel",      dateLabel);     // human-readable
                        appt.put("time",           slot);
                        appt.put("status",         "pending");
                        appt.put("timestamp",      System.currentTimeMillis());
                        appt.put("notes",          "");

                        mDatabase.child("appointments").child(appointmentId)
                                .setValue(appt)
                                .addOnSuccessListener(v -> showBookingSuccess(
                                        doctor, dateLabel, slot, appointmentId))
                                .addOnFailureListener(e ->
                                        Toast.makeText(DoctorListActivity.this,
                                                "Booking failed: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(DoctorListActivity.this,
                                "Failed to get patient data", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Step 5: Success ───────────────────────────────────────────────────
    private void showBookingSuccess(Doctor doctor, String dateLabel,
                                    String slot, String appointmentId) {
        new AlertDialog.Builder(this)
                .setTitle("Appointment Booked!")
                .setMessage("Your appointment has been confirmed!\n\n"
                        + "Doctor: Dr. " + doctor.name + "\n"
                        + "Date: " + dateLabel + "\n"
                        + "Time: " + slot + "\n"
                        + "Status: Pending confirmation\n\n"
                        + "You can view this in My Appointments.")
                .setPositiveButton("View My Appointments", (d, w) -> {
                    Intent intent = new Intent(this, AppointmentActivity.class);
                    intent.putExtra("viewMode", "patient");
                    startActivity(intent);
                })
                .setNegativeButton("OK", null)
                .setCancelable(false)
                .show();
    }

    // ── Model ─────────────────────────────────────────────────────────────
    static class Doctor {
        String id, name, specialization, hospital, experience, phone;

        Doctor(String id, String name, String specialization,
               String hospital, String experience, String phone) {
            this.id             = id;
            this.name           = name != null ? name : "Unknown";
            this.specialization = specialization != null ? specialization : "General";
            this.hospital       = hospital != null ? hospital : "N/A";
            this.experience     = experience != null ? experience : "0";
            this.phone          = phone;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────
    class DoctorAdapter extends RecyclerView.Adapter<DoctorAdapter.VH> {

        private final List<Doctor> list;
        DoctorAdapter(List<Doctor> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_doctor, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.bind(list.get(pos));
        }
        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDoctorName, tvSpecialization, tvHospital, tvExperience;
            Button   btnBookDoctor;

            VH(@NonNull View v) {
                super(v);
                tvDoctorName     = v.findViewById(R.id.tvDoctorName);
                tvSpecialization = v.findViewById(R.id.tvSpecialization);
                tvHospital       = v.findViewById(R.id.tvHospital);
                tvExperience     = v.findViewById(R.id.tvExperience);
                btnBookDoctor    = v.findViewById(R.id.btnBookDoctor);
            }

            void bind(Doctor doctor) {
                tvDoctorName.setText("Dr. " + doctor.name);
                tvSpecialization.setText("Specialization: " + doctor.specialization);
                tvHospital.setText("Hospital: " + doctor.hospital);
                tvExperience.setText("Experience: " + doctor.experience + " years");

                btnBookDoctor.setOnClickListener(v -> showDatePicker(doctor));
            }
        }
    }
}
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

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppointmentActivity extends AppCompatActivity {

    private TextView             tvScreenTitle, tvNoAppointments;
    private RecyclerView         recyclerViewAppointments;
    private ProgressBar          progressBar;
    private FloatingActionButton fabNewAppointment;
    private TabLayout            tabLayout;

    private AppointmentAdapter      adapter;
    private final List<Appointment> allAppointments      = new ArrayList<>();
    private final List<Appointment> filteredAppointments = new ArrayList<>();

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private String            userId;
    private boolean           isPatientView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appointment);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        if (mAuth.getCurrentUser() == null) { finish(); return; }
        userId = mAuth.getCurrentUser().getUid();

        String mode = getIntent().getStringExtra("viewMode");
        isPatientView = "patient".equals(mode);

        initializeViews();
        setupTabs();
        loadAppointments();
    }

    private void initializeViews() {
        tvScreenTitle            = findViewById(R.id.tvScreenTitle);
        recyclerViewAppointments = findViewById(R.id.recyclerViewAppointments);
        progressBar              = findViewById(R.id.progressBar);
        tvNoAppointments         = findViewById(R.id.tvNoAppointments);
        fabNewAppointment        = findViewById(R.id.fabNewAppointment);
        tabLayout                = findViewById(R.id.tabLayout);

        tvScreenTitle.setText(isPatientView ? "My Appointments" : "Patient Appointments");

        adapter = new AppointmentAdapter(filteredAppointments);
        recyclerViewAppointments.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewAppointments.setAdapter(adapter);

        if (isPatientView) {
            fabNewAppointment.setVisibility(View.VISIBLE);
            fabNewAppointment.setOnClickListener(v ->
                    startActivity(new Intent(this, DoctorListActivity.class)));
        } else {
            fabNewAppointment.setVisibility(View.GONE);
        }
    }

    private void setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("All"));
        tabLayout.addTab(tabLayout.newTab().setText("Upcoming"));
        tabLayout.addTab(tabLayout.newTab().setText("Past"));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { applyFilter(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void applyFilter(int tabIndex) {
        filteredAppointments.clear();
        long now = System.currentTimeMillis();

        for (Appointment a : allAppointments) {
            switch (tabIndex) {
                case 0:
                    filteredAppointments.add(a);
                    break;
                case 1: // Upcoming
                    if (("pending".equals(a.status) || "confirmed".equals(a.status))
                            && (a.timestamp == null || a.timestamp >= now)) {
                        filteredAppointments.add(a);
                    }
                    break;
                case 2: // Past
                    if ("completed".equals(a.status) || "cancelled".equals(a.status)
                            || (a.timestamp != null && a.timestamp < now)) {
                        filteredAppointments.add(a);
                    }
                    break;
            }
        }
        updateEmptyState();
        adapter.notifyDataSetChanged();
    }

    private void updateEmptyState() {
        if (filteredAppointments.isEmpty()) {
            tvNoAppointments.setVisibility(View.VISIBLE);
            tvNoAppointments.setText(isPatientView
                    ? "No appointments yet.\nTap + to book a doctor."
                    : "No patient appointments found.");
            recyclerViewAppointments.setVisibility(View.GONE);
        } else {
            tvNoAppointments.setVisibility(View.GONE);
            recyclerViewAppointments.setVisibility(View.VISIBLE);
        }
    }

    private void loadAppointments() {
        progressBar.setVisibility(View.VISIBLE);
        String filterField = isPatientView ? "patientId" : "doctorId";

        mDatabase.child("appointments")
                .orderByChild(filterField)
                .equalTo(userId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        progressBar.setVisibility(View.GONE);
                        allAppointments.clear();

                        for (DataSnapshot ds : snapshot.getChildren()) {
                            // Prefer dateLabel (human-readable) over raw date key
                            String dateLabel = ds.child("dateLabel").getValue(String.class);
                            String date      = ds.child("date").getValue(String.class);

                            allAppointments.add(new Appointment(
                                    ds.getKey(),
                                    ds.child("doctorId").getValue(String.class),
                                    ds.child("doctorName").getValue(String.class),
                                    ds.child("patientId").getValue(String.class),
                                    ds.child("patientName").getValue(String.class),
                                    ds.child("specialization").getValue(String.class),
                                    ds.child("hospital").getValue(String.class),
                                    dateLabel != null ? dateLabel : date,
                                    ds.child("time").getValue(String.class),
                                    ds.child("status").getValue(String.class),
                                    ds.child("timestamp").getValue(Long.class),
                                    ds.child("notes").getValue(String.class)
                            ));
                        }

                        int sel = tabLayout.getSelectedTabPosition();
                        applyFilter(sel >= 0 ? sel : 0);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(AppointmentActivity.this,
                                "Failed to load appointments", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Model ─────────────────────────────────────────────────────────────
    static class Appointment {
        String id, doctorId, doctorName, patientId, patientName,
                specialization, hospital, date, time, status, notes;
        Long   timestamp;

        Appointment(String id, String doctorId, String doctorName,
                    String patientId, String patientName,
                    String specialization, String hospital,
                    String date, String time,
                    String status, Long timestamp, String notes) {
            this.id             = id;
            this.doctorId       = doctorId;
            this.doctorName     = doctorName;
            this.patientId      = patientId;
            this.patientName    = patientName;
            this.specialization = specialization;
            this.hospital       = hospital;
            this.date           = date;
            this.time           = time;
            this.status         = status != null ? status : "pending";
            this.timestamp      = timestamp;
            this.notes          = notes;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────
    class AppointmentAdapter extends RecyclerView.Adapter<AppointmentAdapter.VH> {

        private final List<Appointment> list;
        AppointmentAdapter(List<Appointment> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_appointment, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(list.get(pos)); }
        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDoctorName, tvSpecialization, tvDateTime, tvStatus, tvNotes;
            Button   btnCancel, btnReschedule, btnViewDetails;

            VH(@NonNull View v) {
                super(v);
                tvDoctorName     = v.findViewById(R.id.tvDoctorName);
                tvSpecialization = v.findViewById(R.id.tvSpecialization);
                tvDateTime       = v.findViewById(R.id.tvDateTime);
                tvStatus         = v.findViewById(R.id.tvStatus);
                tvNotes          = v.findViewById(R.id.tvNotes);
                btnCancel        = v.findViewById(R.id.btnCancel);
                btnReschedule    = v.findViewById(R.id.btnReschedule);
                btnViewDetails   = v.findViewById(R.id.btnViewDetails);
            }

            void bind(Appointment a) {
                if (isPatientView) {
                    tvDoctorName.setText("Dr. " + (a.doctorName != null ? a.doctorName : "—"));
                } else {
                    tvDoctorName.setText("Patient: " + (a.patientName != null ? a.patientName : "—"));
                }

                tvSpecialization.setText(a.specialization != null ? a.specialization : "");
                tvDateTime.setText((a.date != null ? a.date : "—")
                        + "  |  " + (a.time != null ? a.time : "—"));

                tvStatus.setText(a.status.toUpperCase(Locale.getDefault()));
                int color;
                switch (a.status.toLowerCase()) {
                    case "confirmed": color = android.R.color.holo_green_dark;  break;
                    case "cancelled": color = android.R.color.holo_red_dark;    break;
                    case "completed": color = android.R.color.holo_blue_dark;   break;
                    default:          color = android.R.color.holo_orange_dark; break;
                }
                tvStatus.setTextColor(getResources().getColor(color, getTheme()));

                boolean terminal = "cancelled".equals(a.status) || "completed".equals(a.status);
                if (terminal) {
                    btnCancel.setVisibility(View.GONE);
                    btnReschedule.setVisibility(View.GONE);
                } else {
                    btnCancel.setVisibility(View.VISIBLE);
                    if (isPatientView) {
                        btnReschedule.setVisibility(View.VISIBLE);
                        btnCancel.setText("Cancel");
                        btnCancel.setBackgroundTintList(
                                getColorStateList(android.R.color.holo_red_light));
                        btnCancel.setOnClickListener(v -> showCancelDialog(a));
                        btnReschedule.setText("Reschedule");
                        btnReschedule.setOnClickListener(v ->
                                Toast.makeText(itemView.getContext(),
                                        "Reschedule coming soon!", Toast.LENGTH_SHORT).show());
                    } else {
                        // Doctor confirms
                        btnReschedule.setVisibility(View.GONE);
                        btnCancel.setText("Confirm");
                        btnCancel.setBackgroundTintList(
                                getColorStateList(android.R.color.holo_green_dark));
                        btnCancel.setOnClickListener(v -> confirmAppointment(a));
                    }
                }

                btnViewDetails.setOnClickListener(v -> showDetails(a));

                if (a.notes != null && !a.notes.isEmpty()) {
                    tvNotes.setVisibility(View.VISIBLE);
                    tvNotes.setText("Notes: " + a.notes);
                } else {
                    tvNotes.setVisibility(View.GONE);
                }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────
    private void showCancelDialog(Appointment a) {
        new AlertDialog.Builder(this)
                .setTitle("Cancel Appointment")
                .setMessage("Cancel your appointment with Dr. " + a.doctorName
                        + "\non " + a.date + " at " + a.time + "?")
                .setPositiveButton("Yes, Cancel", (d, w) -> updateStatus(a, "cancelled"))
                .setNegativeButton("No", null)
                .show();
    }

    private void confirmAppointment(Appointment a) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm Appointment")
                .setMessage("Confirm appointment with "
                        + (a.patientName != null ? a.patientName : "patient")
                        + "\non " + a.date + " at " + a.time + "?")
                .setPositiveButton("Confirm", (d, w) -> updateStatus(a, "confirmed"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateStatus(Appointment a, String newStatus) {
        mDatabase.child("appointments").child(a.id).child("status")
                .setValue(newStatus)
                .addOnSuccessListener(v ->
                        Toast.makeText(this,
                                "Appointment " + newStatus, Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Failed to update", Toast.LENGTH_SHORT).show());
    }

    private void showDetails(Appointment a) {
        String msg = (isPatientView
                ? "Doctor: Dr. " + (a.doctorName != null ? a.doctorName : "—")
                : "Patient: " + (a.patientName != null ? a.patientName : "—"))
                + "\nSpecialization: " + (a.specialization != null ? a.specialization : "—")
                + "\nHospital: " + (a.hospital != null ? a.hospital : "—")
                + "\nDate: " + (a.date != null ? a.date : "—")
                + "\nTime: " + (a.time != null ? a.time : "—")
                + "\nStatus: " + a.status.toUpperCase(Locale.getDefault())
                + (a.notes != null && !a.notes.isEmpty() ? "\nNotes: " + a.notes : "");

        new AlertDialog.Builder(this)
                .setTitle("Appointment Details")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Chat", (d, w) -> {
                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.putExtra("doctorId",   a.doctorId);
                    intent.putExtra("doctorName", a.doctorName);
                    startActivity(intent);
                })
                .show();
    }
}
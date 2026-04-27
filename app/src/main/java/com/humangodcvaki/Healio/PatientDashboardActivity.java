package com.humangodcvaki.Healio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class PatientDashboardActivity extends AppCompatActivity {

    private static final String TAG = "PatientDashboard";
    private static final int LOCATION_PERMISSION_REQUEST = 100;
    private static final long LOCATION_UPDATE_INTERVAL_MS = 30_000L;

    private TextView tvWelcome, tvUserInfo;
    private Button btnBookDoctor, btnFirstAid, btnMyAppointments;
    private Switch switchNeedAmbulance;
    private BottomNavigationView bottomNavigation;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private FusedLocationProviderClient fusedLocationClient;

    private LocationCallback locationCallback;
    private CancellationTokenSource cancellationTokenSource;
    private boolean locationUpdatesStarted = false;

    private double currentLatitude  = 0.0;
    private double currentLongitude = 0.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_dashboard);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initializeViews();
        setupLocationCallback();
        requestLocationPermission();
        loadUserData();
        setupListeners();
        startPatientNotificationService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNavigation.setSelectedItemId(R.id.nav_home);
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cancellationTokenSource != null) cancellationTokenSource.cancel();
        stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initializeViews() {
        tvWelcome           = findViewById(R.id.tvWelcome);
        tvUserInfo          = findViewById(R.id.tvUserInfo);
        btnBookDoctor       = findViewById(R.id.btnBookDoctor);
        btnFirstAid         = findViewById(R.id.btnFirstAid);
        btnMyAppointments   = findViewById(R.id.btnMyAppointments);
        switchNeedAmbulance = findViewById(R.id.switchNeedAmbulance);
        bottomNavigation    = findViewById(R.id.bottomNavigation);
    }

    private void setupListeners() {
        btnBookDoctor.setOnClickListener(v ->
                startActivity(new Intent(this, DoctorListActivity.class)));

        btnFirstAid.setOnClickListener(v ->
                startActivity(new Intent(this, FirstAidVideosActivity.class)));

        // ── NEW: My Appointments button ──
        btnMyAppointments.setOnClickListener(v -> {
            Intent intent = new Intent(this, AppointmentActivity.class);
            intent.putExtra("viewMode", "patient");
            startActivity(intent);
        });


        switchNeedAmbulance.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) return;
            switchNeedAmbulance.setChecked(false);

            if (currentLatitude != 0.0 || currentLongitude != 0.0) {
                Intent intent = new Intent(this, NearbyAmbulanceActivity.class);
                intent.putExtra("latitude",  currentLatitude);
                intent.putExtra("longitude", currentLongitude);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Getting your location…", Toast.LENGTH_SHORT).show();
            }
        });

        bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                return true;
            } else if (id == R.id.nav_ambulance) {
                Intent intent = new Intent(this, EmergencySOSActivity.class);
                intent.putExtra("latitude",  currentLatitude);
                intent.putExtra("longitude", currentLongitude);
                startActivity(intent);
                return true;
            } else if (id == R.id.nav_doctor) {
                startActivity(new Intent(this, DoctorConsultationActivity.class));
                return true;
            }
            return false;
        });
    }

    private void startPatientNotificationService() {
        Intent serviceIntent = new Intent(this, PatientNotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this,
                        "Location permission is required for emergency features",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result.getLastLocation() == null) return;
                onLocationReceived(
                        result.getLastLocation().getLatitude(),
                        result.getLastLocation().getLongitude());
            }
        };
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        if (!isLocationEnabled()) {
            Toast.makeText(this,
                    "Please enable Location so emergency services can find you.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        cancellationTokenSource = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.getToken()
        ).addOnSuccessListener(location -> {
            if (location != null) {
                onLocationReceived(location.getLatitude(), location.getLongitude());
            } else {
                tryGpsCurrentLocation();
            }
        }).addOnFailureListener(e -> tryGpsCurrentLocation());

        if (!locationUpdatesStarted) {
            LocationRequest request = new LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MS / 2)
                    .setMaxUpdateDelayMillis(60_000L)
                    .build();
            fusedLocationClient.requestLocationUpdates(
                    request, locationCallback, getMainLooper());
            locationUpdatesStarted = true;
        }
    }

    private void tryGpsCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        CancellationTokenSource gpsCts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                gpsCts.getToken()
        ).addOnSuccessListener(location -> {
            if (location != null) {
                onLocationReceived(location.getLatitude(), location.getLongitude());
            } else {
                Toast.makeText(this,
                        "Can't get your location. Make sure GPS is on and you're outdoors.",
                        Toast.LENGTH_LONG).show();
            }
        }).addOnFailureListener(e ->
                Log.e(TAG, "GPS getCurrentLocation failed: " + e.getMessage()));
    }

    private void stopLocationUpdates() {
        if (!locationUpdatesStarted) return;
        fusedLocationClient.removeLocationUpdates(locationCallback);
        locationUpdatesStarted = false;
    }

    private void onLocationReceived(double lat, double lng) {
        if (lat == 0.0 && lng == 0.0) return;
        currentLatitude  = lat;
        currentLongitude = lng;
        saveLocationToFirebase(lat, lng);
    }

    private void saveLocationToFirebase(double latitude, double longitude) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        mDatabase.child("users").child(user.getUid()).child("latitude").setValue(latitude);
        mDatabase.child("users").child(user.getUid()).child("longitude").setValue(longitude);
    }

    private void loadUserData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (!dataSnapshot.exists()) return;
                        String name  = dataSnapshot.child("name").getValue(String.class);
                        String age   = dataSnapshot.child("age").getValue(String.class);
                        String phone = dataSnapshot.child("phone").getValue(String.class);

                        tvWelcome.setText("Welcome, " + name + "!");
                        tvUserInfo.setText("Age: " + age
                                + "\nPhone: " + phone
                                + "\n\nEmergency Monitoring: Active");
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Toast.makeText(PatientDashboardActivity.this,
                                "Failed to load data", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}
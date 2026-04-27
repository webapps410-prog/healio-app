package com.humangodcvaki.Healio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.location.LocationManager;
import android.provider.Settings;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class AmbulanceDriverDashboardActivity extends AppCompatActivity {

    private static final String TAG = "AmbulanceDashboard";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    // Update interval: every 30 seconds while the app is in the foreground
    private static final long LOCATION_UPDATE_INTERVAL_MS = 30_000L;

    private TextView tvWelcome, tvUserInfo, tvAmbulanceInfo;
    private Switch   switchAvailable;
    private Button   btnEmergencyCalls, btnProfile, btnLogout;

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;

    // --- Location ---
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;
    private CancellationTokenSource     cancellationTokenSource;
    private boolean                     locationUpdatesStarted = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ambulance_driver_dashboard);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        tvWelcome        = findViewById(R.id.tvWelcome);
        tvUserInfo       = findViewById(R.id.tvUserInfo);
        tvAmbulanceInfo  = findViewById(R.id.tvAmbulanceInfo);
        switchAvailable  = findViewById(R.id.switchAvailable);
        btnEmergencyCalls = findViewById(R.id.btnEmergencyCalls);
        btnProfile       = findViewById(R.id.btnProfile);
        btnLogout        = findViewById(R.id.btnLogout);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        loadUserData();
        startAmbulanceSOSListenerService();
        setupLocationCallback();

        // Permission check — actual location start happens in onResume or after grant
        requestLocationPermission();

        // BUG FIX: only react to user touches, not programmatic setChecked() calls.
        // Without isPressed() guard, loadUserData()'s setChecked() fires the listener
        // which writes available=false to Firebase, making the driver invisible.
        switchAvailable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            updateAvailability(isChecked);
        });

        btnEmergencyCalls.setOnClickListener(v ->
                Toast.makeText(this, "Emergency calls feature coming soon!", Toast.LENGTH_SHORT).show());

        btnProfile.setOnClickListener(v ->
                Toast.makeText(this, "Profile feature coming soon!", Toast.LENGTH_SHORT).show());

        btnLogout.setOnClickListener(v -> logout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Cancel any in-flight getCurrentLocation call
        if (cancellationTokenSource != null) {
            cancellationTokenSource.cancel();
        }
        // Stop foreground updates; the background service handles polling separately
        stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Service is intentionally kept alive after activity destruction
    }

    // -----------------------------------------------------------------------
    // Start / stop listener service
    // -----------------------------------------------------------------------
    private void startAmbulanceSOSListenerService() {
        Intent serviceIntent = new Intent(this, AmbulanceSOSListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Emergency Dispatch System Activated", Toast.LENGTH_SHORT).show();
    }

    // -----------------------------------------------------------------------
    // Location permission
    // -----------------------------------------------------------------------
    private void requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
        // If already granted, onResume() will call startLocationUpdates()
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this,
                        "Location permission is required for SOS dispatch to find you.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Location tracking
    // -----------------------------------------------------------------------
    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult.getLastLocation() == null) return;
                double lat = locationResult.getLastLocation().getLatitude();
                double lng = locationResult.getLastLocation().getLongitude();
                Log.d(TAG, "Periodic location fix: " + lat + ", " + lng);
                saveLocationToFirebase(lat, lng);
            }
        };
    }

    /**
     * Returns true if the device has at least one location provider enabled
     * (GPS or Network). If neither is on, the user must go to Settings.
     */
    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        boolean gpsOn     = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return gpsOn || networkOn;
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startLocationUpdates skipped — no permission yet");
            return;
        }

        // ── Guard: check that location is actually enabled on the device ──
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services are disabled — prompting user");
            Toast.makeText(this,
                    "📍 Please enable Location in Settings so dispatch can find you.",
                    Toast.LENGTH_LONG).show();
            // Open Location Settings so the driver can turn it on
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        // ── Step 1: Immediate fix using getCurrentLocation() ──
        // Use BALANCED_POWER (network/WiFi) as primary so it works even when
        // GPS takes time to warm up. BALANCED returns quickly via cell/WiFi.
        // The periodic request below uses HIGH_ACCURACY for precise ongoing fixes.
        cancellationTokenSource = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,   // works without GPS
                cancellationTokenSource.getToken()
        ).addOnSuccessListener(location -> {
            if (location != null) {
                Log.d(TAG, "Quick network fix: " + location.getLatitude() + ", " + location.getLongitude());
                saveLocationToFirebase(location.getLatitude(), location.getLongitude());
            } else {
                // Network fix also failed — GPS cold start, try HIGH_ACCURACY as last resort
                Log.w(TAG, "Network fix null — falling back to GPS getCurrentLocation");
                tryGpsCurrentLocation();
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "getCurrentLocation (balanced) failed: " + e.getMessage());
            tryGpsCurrentLocation();
        });

        // ── Step 2: Periodic HIGH_ACCURACY updates every 30 s ──
        if (!locationUpdatesStarted) {
            LocationRequest request = new LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MS / 2)
                    .setMaxUpdateDelayMillis(60_000L)
                    .build();

            fusedLocationClient.requestLocationUpdates(request, locationCallback, getMainLooper());
            locationUpdatesStarted = true;
            Log.d(TAG, "Periodic location updates started");
        }
    }

    /**
     * Last-resort: ask for a HIGH_ACCURACY fix (full GPS).
     * This may take 30–60 s on cold start but will eventually resolve.
     */
    private void tryGpsCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        CancellationTokenSource gpsCts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                gpsCts.getToken()
        ).addOnSuccessListener(location -> {
            if (location != null) {
                Log.d(TAG, "GPS cold-start fix: " + location.getLatitude() + ", " + location.getLongitude());
                saveLocationToFirebase(location.getLatitude(), location.getLongitude());
            } else {
                Log.w(TAG, "GPS cold-start also returned null — will retry on next periodic callback");
                Toast.makeText(this,
                        "⚠️ Can't get your location yet. Make sure GPS is on and you're outdoors.",
                        Toast.LENGTH_LONG).show();
            }
        }).addOnFailureListener(e ->
                Log.e(TAG, "GPS getCurrentLocation failed: " + e.getMessage())
        );
    }

    private void stopLocationUpdates() {
        if (!locationUpdatesStarted) return;
        fusedLocationClient.removeLocationUpdates(locationCallback);
        locationUpdatesStarted = false;
        Log.d(TAG, "Location updates stopped");
    }

    /**
     * Saves latitude + longitude + lastLocationUpdate timestamp to
     * users/{driverId}/ in Firebase.
     *
     * GUARD: never writes 0.0 / 0.0 — that indicates a failed/missing fix
     * and would corrupt the SOS radius search.
     */
    private void saveLocationToFirebase(double latitude, double longitude) {
        // ── Critical guard: reject obviously invalid coordinates ──
        if (latitude == 0.0 && longitude == 0.0) {
            Log.w(TAG, "Skipping Firebase write — location is 0.0, 0.0 (no fix yet)");
            return;
        }

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        Map<String, Object> locationUpdate = new HashMap<>();
        locationUpdate.put("latitude", latitude);
        locationUpdate.put("longitude", longitude);
        locationUpdate.put("lastLocationUpdate", System.currentTimeMillis());

        mDatabase.child("users").child(user.getUid())
                .updateChildren(locationUpdate)
                .addOnSuccessListener(aVoid ->
                        Log.d(TAG, "✅ Location saved to Firebase: " + latitude + ", " + longitude))
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ Failed to save location: " + e.getMessage()));
    }

    // -----------------------------------------------------------------------
    // Firebase helpers
    // -----------------------------------------------------------------------
    private void loadUserData() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (!dataSnapshot.exists()) return;

                        String  name            = dataSnapshot.child("name").getValue(String.class);
                        String  phone           = dataSnapshot.child("phone").getValue(String.class);
                        String  ambulanceNumber = dataSnapshot.child("ambulanceNumber").getValue(String.class);
                        String  place           = dataSnapshot.child("place").getValue(String.class);
                        Boolean available       = dataSnapshot.child("available").getValue(Boolean.class);

                        tvWelcome.setText("Welcome, " + name + "!");
                        tvUserInfo.setText("Phone: " + phone + "\nLocation: " + place);
                        tvAmbulanceInfo.setText("Ambulance: " + ambulanceNumber +
                                "\n\n🔔 Emergency Dispatch: Active");

                        // BUG FIX: if available is null (new account), default to true
                        boolean isAvailable = (available != null) ? available : true;
                        switchAvailable.setChecked(isAvailable);
                        // Write default true to Firebase so SOS search can find this driver
                        if (available == null) {
                            mDatabase.child("users").child(user.getUid())
                                    .child("available").setValue(true);
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Toast.makeText(AmbulanceDriverDashboardActivity.this,
                                "Failed to load data", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void updateAvailability(boolean isAvailable) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;

        mDatabase.child("users").child(user.getUid()).child("available").setValue(isAvailable)
                .addOnSuccessListener(aVoid ->
                        Toast.makeText(this,
                                "Status: " + (isAvailable ? "Available ✅" : "Unavailable ❌"),
                                Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to update status", Toast.LENGTH_SHORT).show());
    }

    private void logout() {
        if (cancellationTokenSource != null) cancellationTokenSource.cancel();
        stopLocationUpdates();
        stopService(new Intent(this, AmbulanceSOSListenerService.class));

        mAuth.signOut();
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, SignInActivity.class));
        finish();
    }
}
package com.humangodcvaki.Healio;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NearbyAmbulanceActivity extends AppCompatActivity {

    private static final String TAG             = "NearbyAmbulance";
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double AVG_SPEED_KMH   = 40.0;

    private MapView              mapView;
    private ProgressBar          progressBar;
    private TextView             tvLocationInfo, tvDriverCount;
    private LinearLayout         bottomSheet;
    private TextView             tvBSName, tvBSAmbulance, tvBSPlace,
            tvBSDistance, tvBSETA, tvBSPhone, tvBSStatus;
    private Button               btnBSCall, btnBSBook, btnBSClose;

    private DatabaseReference    mDatabase;
    private FirebaseAuth         mAuth;

    private double               userLatitude  = 0.0;
    private double               userLongitude = 0.0;
    private AmbulanceDriver      selectedDriver;
    private MyLocationNewOverlay locationOverlay;
    private final List<AmbulanceDriver> driverList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_nearby_ambulance);

        mAuth     = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        userLatitude  = getIntent().getDoubleExtra("latitude",  0.0);
        userLongitude = getIntent().getDoubleExtra("longitude", 0.0);

        bindViews();
        setupMap();
        updateLocationLabel();
        loadAmbulanceDrivers();
    }

    @Override protected void onResume()  { super.onResume();  mapView.onResume(); }
    @Override protected void onPause()   { super.onPause();   mapView.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); mapView.onDetach(); }

    private void bindViews() {
        mapView        = findViewById(R.id.mapView);
        progressBar    = findViewById(R.id.progressBar);
        tvLocationInfo = findViewById(R.id.tvLocationInfo);
        tvDriverCount  = findViewById(R.id.tvDriverCount);
        bottomSheet    = findViewById(R.id.bottomSheet);
        tvBSName       = findViewById(R.id.tvBSName);
        tvBSAmbulance  = findViewById(R.id.tvBSAmbulance);
        tvBSPlace      = findViewById(R.id.tvBSPlace);
        tvBSDistance   = findViewById(R.id.tvBSDistance);
        tvBSETA        = findViewById(R.id.tvBSETA);
        tvBSPhone      = findViewById(R.id.tvBSPhone);
        tvBSStatus     = findViewById(R.id.tvBSStatus);
        btnBSCall      = findViewById(R.id.btnBSCall);
        btnBSBook      = findViewById(R.id.btnBSBook);
        btnBSClose     = findViewById(R.id.btnBSClose);
        btnBSClose.setOnClickListener(v -> hideBottomSheet());
    }

    private void updateLocationLabel() {
        if (userLatitude != 0.0 || userLongitude != 0.0) {
            tvLocationInfo.setText(String.format(Locale.US,
                    "📍 Your location: %.4f, %.4f", userLatitude, userLongitude));
        } else {
            tvLocationInfo.setText("📍 Location unavailable — enable GPS");
        }
    }

    // ── Map setup ────────────────────────────────────────────────────────────
    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);

        IMapController ctrl = mapView.getController();
        ctrl.setZoom(13.0);
        GeoPoint center = (userLatitude != 0.0 || userLongitude != 0.0)
                ? new GeoPoint(userLatitude, userLongitude)
                : new GeoPoint(20.5937, 78.9629);
        ctrl.setCenter(center);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationOverlay = new MyLocationNewOverlay(
                    new GpsMyLocationProvider(this), mapView);
            locationOverlay.enableMyLocation();
            mapView.getOverlays().add(locationOverlay);
        }
    }

    // ── Load all ambulance drivers from Firebase ──────────────────────────
    private void loadAmbulanceDrivers() {
        progressBar.setVisibility(View.VISIBLE);
        tvDriverCount.setText("Searching for ambulances...");

        // Scan all users; filter in Java (avoids Firebase index requirement)
        mDatabase.child("users")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        progressBar.setVisibility(View.GONE);
                        driverList.clear();
                        mapView.getOverlays().removeIf(o -> o instanceof Marker);

                        for (DataSnapshot ds : snapshot.getChildren()) {
                            String userType = ds.child("userType").getValue(String.class);
                            if (!"ambulance_driver".equals(userType)) continue;

                            String  id        = ds.getKey();
                            String  name      = ds.child("name").getValue(String.class);
                            String  phone     = ds.child("phone").getValue(String.class);
                            String  ambNum    = ds.child("ambulanceNumber").getValue(String.class);
                            String  place     = ds.child("place").getValue(String.class);
                            Boolean available = ds.child("available").getValue(Boolean.class);
                            Double  dLat      = ds.child("latitude").getValue(Double.class);
                            Double  dLng      = ds.child("longitude").getValue(Double.class);

                            // Skip drivers with no valid GPS fix
                            if (dLat == null || dLng == null
                                    || (dLat == 0.0 && dLng == 0.0)) continue;

                            boolean isAvail  = Boolean.TRUE.equals(available);
                            double  distance = calculateDistance(
                                    userLatitude, userLongitude, dLat, dLng);

                            AmbulanceDriver driver = new AmbulanceDriver(
                                    id, name, phone, ambNum, place,
                                    isAvail, distance, dLat, dLng);
                            driverList.add(driver);
                            addMarker(driver);
                        }

                        Collections.sort(driverList,
                                (a, b) -> Double.compare(a.distance, b.distance));

                        long avail = 0;
                        for (AmbulanceDriver d : driverList) if (d.available) avail++;

                        if (driverList.isEmpty()) {
                            tvDriverCount.setText("No ambulance drivers found near you.");
                        } else {
                            tvDriverCount.setText(String.format(Locale.US,
                                    "🚑 %d driver(s) found  |  %d available",
                                    driverList.size(), avail));
                        }

                        // Keep user location dot on top
                        if (locationOverlay != null) {
                            mapView.getOverlays().remove(locationOverlay);
                            mapView.getOverlays().add(locationOverlay);
                        }
                        mapView.invalidate();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(NearbyAmbulanceActivity.this,
                                "Failed to load ambulances", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Markers ──────────────────────────────────────────────────────────────
    private void addMarker(AmbulanceDriver driver) {
        Marker marker = new Marker(mapView);
        marker.setPosition(new GeoPoint(driver.latitude, driver.longitude));
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(driver.name != null ? driver.name : "Ambulance");
        marker.setSnippet(driver.available ? "Available" : "Busy");

        // Green dot = available, red dot = busy
        marker.setIcon(getResources().getDrawable(
                driver.available
                        ? android.R.drawable.presence_online
                        : android.R.drawable.presence_busy,
                getTheme()));

        marker.setOnMarkerClickListener((m, mv) -> {
            showBottomSheet(driver);
            return true;
        });
        mapView.getOverlays().add(marker);
    }

    // ── Bottom sheet ─────────────────────────────────────────────────────────
    private void showBottomSheet(AmbulanceDriver driver) {
        selectedDriver = driver;

        tvBSName.setText("🚑 " + (driver.name != null ? driver.name : "Unknown Driver"));
        tvBSAmbulance.setText("Ambulance No: "
                + (driver.ambulanceNumber != null ? driver.ambulanceNumber : "N/A"));
        tvBSPlace.setText("📍 " + (driver.place != null ? driver.place : "N/A"));
        tvBSPhone.setText("📞 " + (driver.phone != null ? driver.phone : "N/A"));
        tvBSStatus.setText(driver.available ? "✅ Available" : "🔴 Busy");
        tvBSStatus.setTextColor(getResources().getColor(
                driver.available
                        ? android.R.color.holo_green_dark
                        : android.R.color.holo_red_dark,
                getTheme()));

        if (userLatitude != 0.0 || userLongitude != 0.0) {
            double etaMin = (driver.distance / AVG_SPEED_KMH) * 60.0;
            tvBSDistance.setText(String.format(Locale.US, "📏 %.2f km away", driver.distance));
            tvBSETA.setText(String.format(Locale.US, "⏱ ETA ~%.0f min", etaMin));
        } else {
            tvBSDistance.setText("📏 Distance unavailable");
            tvBSETA.setText("⏱ ETA unavailable");
        }

        btnBSCall.setOnClickListener(v -> callDriver(driver));

        btnBSBook.setEnabled(driver.available);
        btnBSBook.setAlpha(driver.available ? 1.0f : 0.4f);
        btnBSBook.setText(driver.available ? "🚑 BOOK NOW" : "Unavailable");
        btnBSBook.setOnClickListener(v -> {
            if (!driver.available) {
                Toast.makeText(this, "This driver is currently busy", Toast.LENGTH_SHORT).show();
                return;
            }
            confirmAndBook(driver);
        });

        bottomSheet.setVisibility(View.VISIBLE);
        bottomSheet.setTranslationY(600);
        bottomSheet.animate().translationY(0).setDuration(280).start();
    }

    private void hideBottomSheet() {
        bottomSheet.animate()
                .translationY(600)
                .setDuration(220)
                .withEndAction(() -> bottomSheet.setVisibility(View.GONE))
                .start();
        selectedDriver = null;
    }

    // ── Call driver ───────────────────────────────────────────────────────────
    private void callDriver(AmbulanceDriver driver) {
        if (driver.phone == null || driver.phone.isEmpty()) {
            Toast.makeText(this, "No phone number available", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                ? new Intent(Intent.ACTION_CALL)
                : new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:" + driver.phone));
        startActivity(intent);
    }

    // ── Book (targeted SOS) ───────────────────────────────────────────────────
    private void confirmAndBook(AmbulanceDriver driver) {
        double etaMin = (driver.distance / AVG_SPEED_KMH) * 60.0;
        new AlertDialog.Builder(this)
                .setTitle("🚑 Book This Ambulance?")
                .setMessage(String.format(Locale.US,
                        "Send emergency request to:\n\n"
                                + "Driver: %s\nAmbulance: %s\n"
                                + "Distance: %.2f km\nETA: ~%.0f min\n\n"
                                + "The driver will be notified immediately.",
                        driver.name != null ? driver.name : "Driver",
                        driver.ambulanceNumber != null ? driver.ambulanceNumber : "N/A",
                        driver.distance, etaMin))
                .setPositiveButton("YES, BOOK NOW", (d, w) -> sendTargetedSOS(driver))
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show();
    }

    private void sendTargetedSOS(AmbulanceDriver driver) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        mDatabase.child("users").child(user.getUid())
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String patientName  = snapshot.child("name").getValue(String.class);
                        String patientPhone = snapshot.child("phone").getValue(String.class);

                        double finalLat = userLatitude;
                        double finalLng = userLongitude;

                        if (finalLat == 0.0 && finalLng == 0.0) {
                            Double sLat = snapshot.child("latitude").getValue(Double.class);
                            Double sLng = snapshot.child("longitude").getValue(Double.class);
                            if (sLat != null && sLng != null
                                    && !(sLat == 0.0 && sLng == 0.0)) {
                                finalLat = sLat;
                                finalLng = sLng;
                            } else {
                                Toast.makeText(NearbyAmbulanceActivity.this,
                                        "Cannot book — GPS unavailable. Enable location and try again.",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                        }

                        final double alertLat = finalLat;
                        final double alertLng = finalLng;

                        // 1. Create emergencyAlert record
                        Map<String, Object> alert = new HashMap<>();
                        alert.put("patientId",        user.getUid());
                        alert.put("patientName",      patientName);
                        alert.put("patientPhone",     patientPhone);
                        alert.put("latitude",         alertLat);
                        alert.put("longitude",        alertLng);
                        alert.put("timestamp",        System.currentTimeMillis());
                        alert.put("status",           "active");
                        alert.put("priority",         "normal");
                        alert.put("targetedDriverId", driver.id);

                        String alertId = mDatabase.child("emergencyAlerts").push().getKey();
                        if (alertId == null) return;

                        mDatabase.child("emergencyAlerts").child(alertId).setValue(alert)
                                .addOnSuccessListener(v -> {

                                    // 2. Send notification to this specific driver only
                                    Map<String, Object> notif = new HashMap<>();
                                    notif.put("type",         "emergency_ambulance");
                                    notif.put("alertId",      alertId);
                                    notif.put("patientName",  patientName  != null ? patientName  : "Patient");
                                    notif.put("patientPhone", patientPhone != null ? patientPhone : "N/A");
                                    notif.put("latitude",     alertLat);
                                    notif.put("longitude",    alertLng);
                                    notif.put("distance",     driver.distance);
                                    notif.put("timestamp",    System.currentTimeMillis());
                                    notif.put("accepted",     false);
                                    notif.put("read",         false);

                                    mDatabase.child("notifications").child(driver.id).push()
                                            .setValue(notif)
                                            .addOnSuccessListener(v2 -> {
                                                Log.d(TAG, "Booking sent to driver: " + driver.id);
                                                hideBottomSheet();
                                                showBookingSuccess(driver);
                                            })
                                            .addOnFailureListener(e ->
                                                    Toast.makeText(NearbyAmbulanceActivity.this,
                                                            "Failed to notify driver: " + e.getMessage(),
                                                            Toast.LENGTH_SHORT).show());
                                })
                                .addOnFailureListener(e ->
                                        Toast.makeText(NearbyAmbulanceActivity.this,
                                                "Failed to create alert: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(NearbyAmbulanceActivity.this,
                                "Failed to load your data", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showBookingSuccess(AmbulanceDriver driver) {
        double etaMin = (driver.distance / AVG_SPEED_KMH) * 60.0;
        new AlertDialog.Builder(this)
                .setTitle("✅ Ambulance Booked!")
                .setMessage(String.format(Locale.US,
                        "Request sent to %s (%s)\n\n"
                                + "Distance: %.2f km\n"
                                + "Estimated arrival: ~%.0f minutes\n\n"
                                + "The driver has been notified and will head to your location.",
                        driver.name != null ? driver.name : "Driver",
                        driver.ambulanceNumber != null ? driver.ambulanceNumber : "N/A",
                        driver.distance, etaMin))
                .setPositiveButton("OK", null)
                .setNeutralButton("📞 Call Driver", (d, w) -> callDriver(driver))
                .setCancelable(false)
                .show();
    }

    // ── Haversine ─────────────────────────────────────────────────────────────
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        if (lat1 == 0.0 && lon1 == 0.0) return 0.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ── Model ────────────────────────────────────────────────────────────────
    static class AmbulanceDriver {
        String  id, name, phone, ambulanceNumber, place;
        boolean available;
        double  distance, latitude, longitude;

        AmbulanceDriver(String id, String name, String phone, String ambulanceNumber,
                        String place, boolean available, double distance,
                        double latitude, double longitude) {
            this.id              = id;
            this.name            = name;
            this.phone           = phone;
            this.ambulanceNumber = ambulanceNumber;
            this.place           = place;
            this.available       = available;
            this.distance        = distance;
            this.latitude        = latitude;
            this.longitude       = longitude;
        }
    }
}
package com.humangodcvaki.Healio;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.Locale;

/**
 * Emergency map shown to the ambulance driver after accepting an SOS.
 *
 * Uses OpenStreetMap (OSMDroid) — no API key required.
 *
 * Navigate button opens Google Maps navigation directly (no app chooser).
 */
public class EmergencyMapActivity extends AppCompatActivity {

    private MapView  mapView;
    private TextView tvPatientInfo, tvLocationInfo;
    private Button   btnNavigate, btnCallPatient, btnBackToDashboard;

    private String alertId;
    private String patientName;
    private String patientPhone;
    private double latitude;
    private double longitude;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_emergency_map);

        alertId      = getIntent().getStringExtra("alertId");
        patientName  = getIntent().getStringExtra("patientName");
        patientPhone = getIntent().getStringExtra("patientPhone");
        latitude     = getIntent().getDoubleExtra("latitude",  0.0);
        longitude    = getIntent().getDoubleExtra("longitude", 0.0);

        initializeViews();
        setupMap();
    }

    @Override protected void onResume()  { super.onResume();  mapView.onResume();  }
    @Override protected void onPause()   { super.onPause();   mapView.onPause();   }
    @Override protected void onDestroy() { super.onDestroy(); mapView.onDetach();  }

    // -----------------------------------------------------------------------
    // Map
    // -----------------------------------------------------------------------
    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(16.0);

        if (latitude == 0.0 && longitude == 0.0) {
            Toast.makeText(this, "Patient location unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        GeoPoint patientPoint = new GeoPoint(latitude, longitude);
        mapView.getController().animateTo(patientPoint);

        // Red pin
        Marker marker = new Marker(mapView);
        marker.setPosition(patientPoint);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle("🚨 " + (patientName != null ? patientName : "Patient"));
        marker.setSnippet("Emergency location");
        marker.setIcon(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation));
        mapView.getOverlays().add(marker);

        // Semi-transparent red circle (~100 m radius)
        Polygon circle = new Polygon();
        circle.setPoints(Polygon.pointsAsCircle(patientPoint, 100));
        circle.getFillPaint().setColor(0x22FF0000);
        circle.getOutlinePaint().setColor(0xFFB71C1C);
        circle.getOutlinePaint().setStrokeWidth(3f);
        mapView.getOverlays().add(circle);

        mapView.invalidate();
    }

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------
    private void initializeViews() {
        mapView            = findViewById(R.id.mapView);
        tvPatientInfo      = findViewById(R.id.tvPatientInfo);
        tvLocationInfo     = findViewById(R.id.tvLocationInfo);
        btnNavigate        = findViewById(R.id.btnNavigate);
        btnCallPatient     = findViewById(R.id.btnCallPatient);
        btnBackToDashboard = findViewById(R.id.btnBackToDashboard);

        tvPatientInfo.setText(
                "Patient: " + (patientName  != null ? patientName  : "Unknown") +
                        "\nPhone:   " + (patientPhone != null ? patientPhone : "N/A"));

        tvLocationInfo.setText(String.format(Locale.US,
                "📍 %.6f,  %.6f", latitude, longitude));

        btnNavigate.setOnClickListener(v        -> navigateWithGoogleMaps());
        btnCallPatient.setOnClickListener(v     -> callPatient());
        btnBackToDashboard.setOnClickListener(v -> backToDashboard());
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    /**
     * Opens Google Maps turn-by-turn navigation directly to the patient's location.
     * Falls back to the geo: URI (any navigation app) if Google Maps is not installed.
     */
    private void navigateWithGoogleMaps() {
        if (latitude == 0.0 && longitude == 0.0) {
            Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show();
            return;
        }

        // Google Maps navigation URI — opens navigation mode immediately
        String googleMapsNavUri = String.format(Locale.ENGLISH,
                "google.navigation:q=%f,%f&mode=d", latitude, longitude);

        Intent navIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsNavUri));
        navIntent.setPackage("com.google.android.apps.maps");

        if (navIntent.resolveActivity(getPackageManager()) != null) {
            // Google Maps is installed — launch directly
            startActivity(navIntent);
        } else {
            // Fallback: open coordinates in browser Google Maps
            String mapsUrl = String.format(Locale.ENGLISH,
                    "https://www.google.com/maps/dir/?api=1&destination=%f,%f&travelmode=driving",
                    latitude, longitude);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)));
        }
    }

    private void callPatient() {
        if (patientPhone != null && !patientPhone.isEmpty()) {
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:" + patientPhone));
            startActivity(intent);
        } else {
            Toast.makeText(this, "Patient phone number not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void backToDashboard() {
        // Route back to the appropriate dashboard depending on who opened this screen.
        // The ambulance driver lands here after accepting; the doctor dashboard also uses this.
        // Use CLEAR_TOP so we don't stack activities.
        Intent intent = new Intent(this, AmbulanceDriverDashboardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
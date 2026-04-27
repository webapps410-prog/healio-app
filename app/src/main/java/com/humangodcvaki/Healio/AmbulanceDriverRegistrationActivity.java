package com.humangodcvaki.Healio;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class AmbulanceDriverRegistrationActivity extends AppCompatActivity {

    private static final String TAG = "AmbulanceDriverReg";
    private EditText etName, etPhone, etAge, etAmbulanceNumber, etEmergencyNumber,
            etPlace, etAddress;
    private Button btnUploadLicense, btnSubmit;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private String licenseBase64 = "";
    private GeminiVerificationHelper geminiHelper;
    private ProgressDialog progressDialog;
    private boolean isVerifying = false;
    private FusedLocationProviderClient fusedLocationClient;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 2001;

    private final ActivityResultLauncher<Intent> licensePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    convertImageToBase64(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ambulance_driver_registration);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();
        geminiHelper = new GeminiVerificationHelper(this);

        progressDialog = new ProgressDialog(this);
        progressDialog.setCancelable(false);

        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        etAge = findViewById(R.id.etAge);
        etAmbulanceNumber = findViewById(R.id.etAmbulanceNumber);
        etEmergencyNumber = findViewById(R.id.etEmergencyNumber);
        etPlace = findViewById(R.id.etPlace);
        etAddress = findViewById(R.id.etAddress);
        btnUploadLicense = findViewById(R.id.btnUploadLicense);
        btnSubmit = findViewById(R.id.btnSubmit);

        btnUploadLicense.setOnClickListener(v -> pickLicense());
        btnSubmit.setOnClickListener(v -> submitDriverData());

        // Init location client and request permission early so GPS is warm by submit time
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void pickLicense() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        licensePicker.launch(intent);
    }

    private void convertImageToBase64(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, length);
            }
            byte[] imageBytes = byteArrayOutputStream.toByteArray();
            licenseBase64 = Base64.encodeToString(imageBytes, Base64.DEFAULT);
            btnUploadLicense.setText("License Uploaded ✓");
            Toast.makeText(this, "License uploaded successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to upload license", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitDriverData() {
        if (isVerifying) {
            Toast.makeText(this, "Verification in progress, please wait...", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String age = etAge.getText().toString().trim();
        String ambulanceNumber = etAmbulanceNumber.getText().toString().trim();
        String emergencyNumber = etEmergencyNumber.getText().toString().trim();
        String place = etPlace.getText().toString().trim();
        String address = etAddress.getText().toString().trim();

        if (name.isEmpty() || phone.isEmpty() || age.isEmpty() || ambulanceNumber.isEmpty()
                || emergencyNumber.isEmpty() || place.isEmpty() || address.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (licenseBase64.isEmpty()) {
            Toast.makeText(this, "Please upload your license", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if using mock verification (for testing)
        if (AppConfig.USE_MOCK_VERIFICATION) {
            Log.d(TAG, "Using MOCK verification (test mode)");
            progressDialog.setMessage("Verifying license... (Test Mode)");
            progressDialog.show();

            new Handler().postDelayed(() -> {
                progressDialog.dismiss();
                if (AppConfig.MOCK_VERIFICATION_RESULT) {
                    Toast.makeText(this, "✅ Mock Verification: VERIFIED", Toast.LENGTH_SHORT).show();
                    saveDriverData(user, name, phone, age, ambulanceNumber, emergencyNumber, place, address);
                } else {
                    Toast.makeText(this, "❌ Mock Verification: REJECTED", Toast.LENGTH_SHORT).show();
                }
            }, AppConfig.MOCK_VERIFICATION_DELAY);
            return;
        }

        // Real AI verification with improved error handling
        isVerifying = true;
        btnSubmit.setEnabled(false);
        progressDialog.setMessage("🔍 Verifying license with AI...\n\n⏱️ This may take 20-60 seconds.\n\nPlease be patient and do not close the app.");
        progressDialog.show();

        String verificationPrompt = "Verify this driving license. Check if it appears to be a valid driver's license. Look for: license number, name, photo, issue/expiry dates, and authenticity markers. Respond with 'VERIFIED' if it looks legitimate or 'REJECTED' with a brief reason if not.";

        geminiHelper.verifyDocument(licenseBase64, verificationPrompt,
                new GeminiVerificationHelper.VerificationCallback() {

                    @Override
                    public void onSuccess(boolean isVerified, String message) {
                        isVerifying = false;
                        btnSubmit.setEnabled(true);
                        progressDialog.dismiss();

                        if (AppConfig.DEBUG_MODE) {
                            Log.d(TAG, "Verification result: " + isVerified);
                            Log.d(TAG, "AI Response: " + message);
                        }

                        if (isVerified) {
                            showSuccessDialog(user, name, phone, age, ambulanceNumber, emergencyNumber, place, address, message);
                        } else {
                            showRejectionDialog(message);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        isVerifying = false;
                        btnSubmit.setEnabled(true);
                        progressDialog.dismiss();

                        if (AppConfig.DEBUG_MODE) {
                            Log.e(TAG, "Verification error: " + error);
                        }

                        showErrorDialog(error);
                    }

                    @Override
                    public void onRateLimitWait(long waitTimeMs) {
                        int seconds = (int) (waitTimeMs / 1000);
                        progressDialog.setMessage("⏰ Rate limit reached.\n\nWaiting " + seconds + " seconds before retry...\n\nPlease be patient.");
                    }
                });
    }

    private void showSuccessDialog(FirebaseUser user, String name, String phone, String age,
                                   String ambulanceNumber, String emergencyNumber, String place,
                                   String address, String aiMessage) {
        new AlertDialog.Builder(this)
                .setTitle("✅ License Verified!")
                .setMessage("Your driving license has been verified by AI.\n\nWould you like to proceed with registration?")
                .setPositiveButton("Yes, Register", (dialog, which) -> {
                    saveDriverData(user, name, phone, age, ambulanceNumber, emergencyNumber, place, address);
                })
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show();
    }

    private void showRejectionDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Verification Failed")
                .setMessage("The license could not be verified:\n\n" + message +
                        "\n\nPlease ensure you're uploading a clear, valid driving license.")
                .setPositiveButton("Try Again", (dialog, which) -> {
                    licenseBase64 = "";
                    btnUploadLicense.setText("Upload License");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showErrorDialog(String error) {
        String message;
        String title;

        if (error.contains("429") || error.toLowerCase().contains("rate limit")) {
            title = "⏰ Too Many Requests";
            message = "The verification service is currently busy.\n\n" +
                    "⚠️ IMPORTANT: Please wait 2-3 minutes before trying again.\n\n" +
                    "The Gemini API has strict rate limits. Multiple attempts within a short time will continue to fail.\n\n" +
                    "Tip: Make sure you're uploading a clear, well-lit photo of your license.";
        } else if (error.contains("timeout") || error.toLowerCase().contains("timed out")) {
            title = "⏱️ Connection Timeout";
            message = "The verification request took too long.\n\n" +
                    "This usually happens due to:\n" +
                    "• Slow internet connection\n" +
                    "• Large image file\n" +
                    "• Server overload\n\n" +
                    "Please try again with a better connection.";
        } else if (error.toLowerCase().contains("network")) {
            title = "🌐 Network Error";
            message = "Unable to connect to the verification service.\n\n" +
                    "Please check your internet connection and try again.";
        } else {
            title = "❌ Verification Error";
            message = error;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Retry", (dialog, which) -> submitDriverData())
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Use Test Mode", (dialog, which) -> {
                    showTestModeDialog();
                })
                .show();
    }

    private void showTestModeDialog() {
        new AlertDialog.Builder(this)
                .setTitle("🧪 Test Mode")
                .setMessage("Would you like to enable test mode?\n\n" +
                        "This will skip AI verification temporarily. " +
                        "Your registration will still be saved.\n\n" +
                        "⚠️ Only use this for testing purposes.")
                .setPositiveButton("Enable Test Mode", (dialog, which) -> {
                    Toast.makeText(this, "Test mode enabled for this session", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveDriverData(FirebaseUser user, String name, String phone, String age,
                                String ambulanceNumber, String emergencyNumber, String place, String address) {
        progressDialog.setMessage("📍 Getting your location...");
        progressDialog.show();

        // Try to get real GPS coordinates first; fall back to 0.0 if unavailable
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        double lat = (location != null) ? location.getLatitude() : 0.0;
                        double lng = (location != null) ? location.getLongitude() : 0.0;
                        Log.d(TAG, "Got location for registration: " + lat + ", " + lng);
                        writeDriverDataToFirebase(user, name, phone, age, ambulanceNumber,
                                emergencyNumber, place, address, lat, lng);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Could not get location, saving 0.0: " + e.getMessage());
                        writeDriverDataToFirebase(user, name, phone, age, ambulanceNumber,
                                emergencyNumber, place, address, 0.0, 0.0);
                    });
        } else {
            // No permission yet — save with 0.0; dashboard will update on first open
            writeDriverDataToFirebase(user, name, phone, age, ambulanceNumber,
                    emergencyNumber, place, address, 0.0, 0.0);
        }
    }

    private void writeDriverDataToFirebase(FirebaseUser user, String name, String phone, String age,
                                           String ambulanceNumber, String emergencyNumber,
                                           String place, String address,
                                           double latitude, double longitude) {
        progressDialog.setMessage("💾 Saving your data...");

        String userId = user.getUid();
        Map<String, Object> driverData = new HashMap<>();
        driverData.put("name", name);
        driverData.put("email", user.getEmail());
        driverData.put("phone", phone);
        driverData.put("age", age);
        driverData.put("ambulanceNumber", ambulanceNumber);
        driverData.put("emergencyNumber", emergencyNumber);
        driverData.put("place", place);
        driverData.put("address", address);
        driverData.put("userType", "ambulance_driver");
        driverData.put("verified", true);
        driverData.put("available", true);
        driverData.put("registrationDate", System.currentTimeMillis());
        driverData.put("latitude", latitude);
        driverData.put("longitude", longitude);
        driverData.put("lastLocationUpdate", latitude != 0.0 ? System.currentTimeMillis() : 0L);

        mDatabase.child("users").child(userId).updateChildren(driverData)
                .addOnSuccessListener(aVoid -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("🎉 Success!")
                            .setMessage("Your registration has been completed successfully!\n\n" +
                                    "Welcome to Healio, " + name + "!")
                            .setPositiveButton("Continue", (dialog, which) -> {
                                startActivity(new Intent(this, AmbulanceDriverDashboardActivity.class));
                                finish();
                            })
                            .setCancelable(false)
                            .show();
                })
                .addOnFailureListener(e -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("❌ Save Failed")
                            .setMessage("Failed to save registration data:\n\n" + e.getMessage())
                            .setPositiveButton("Retry", (dialog, which) ->
                                    writeDriverDataToFirebase(user, name, phone, age, ambulanceNumber, emergencyNumber, place, address, latitude, longitude))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}
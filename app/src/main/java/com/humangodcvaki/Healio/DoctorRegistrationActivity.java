package com.humangodcvaki.Healio;

import android.app.ProgressDialog;
import android.content.Intent;
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

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class DoctorRegistrationActivity extends AppCompatActivity {

    private static final String TAG = "DoctorRegistration";
    private EditText etName, etPhone, etAge, etYearsExperience, etHospitalName, etSpecialization;
    private Button btnUploadCertificate, btnSubmit;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private String certificateBase64 = "";
    private GeminiSDKVerificationHelper geminiHelper;
    private ProgressDialog progressDialog;
    private boolean isVerifying = false;

    private final ActivityResultLauncher<Intent> certificatePicker = registerForActivityResult(
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
        setContentView(R.layout.activity_doctor_registration);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Use new SDK helper
        geminiHelper = new GeminiSDKVerificationHelper(this);

        progressDialog = new ProgressDialog(this);
        progressDialog.setCancelable(false);

        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        etAge = findViewById(R.id.etAge);
        etYearsExperience = findViewById(R.id.etYearsExperience);
        etHospitalName = findViewById(R.id.etHospitalName);
        etSpecialization = findViewById(R.id.etSpecialization);
        btnUploadCertificate = findViewById(R.id.btnUploadCertificate);
        btnSubmit = findViewById(R.id.btnSubmit);

        btnUploadCertificate.setOnClickListener(v -> pickCertificate());
        btnSubmit.setOnClickListener(v -> submitDoctorData());
    }

    private void pickCertificate() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        certificatePicker.launch(intent);
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
            certificateBase64 = Base64.encodeToString(imageBytes, Base64.DEFAULT);
            btnUploadCertificate.setText("Certificate Uploaded ✓");
            Toast.makeText(this, "Certificate uploaded successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to upload certificate", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitDoctorData() {
        if (isVerifying) {
            Toast.makeText(this, "Verification in progress, please wait...", Toast.LENGTH_SHORT).show();
            return;
        }

        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String age = etAge.getText().toString().trim();
        String experience = etYearsExperience.getText().toString().trim();
        String hospital = etHospitalName.getText().toString().trim();
        String specialization = etSpecialization.getText().toString().trim();

        if (name.isEmpty() || phone.isEmpty() || age.isEmpty() || experience.isEmpty()
                || hospital.isEmpty() || specialization.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        if (certificateBase64.isEmpty()) {
            Toast.makeText(this, "Please upload your certificate", Toast.LENGTH_SHORT).show();
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
            progressDialog.setMessage("Verifying certificate... (Test Mode)");
            progressDialog.show();

            new Handler().postDelayed(() -> {
                progressDialog.dismiss();
                if (AppConfig.MOCK_VERIFICATION_RESULT) {
                    Toast.makeText(this, "✅ Mock Verification: VERIFIED", Toast.LENGTH_SHORT).show();
                    saveDoctorData(user, name, phone, age, experience, hospital, specialization);
                } else {
                    Toast.makeText(this, "❌ Mock Verification: REJECTED", Toast.LENGTH_SHORT).show();
                }
            }, AppConfig.MOCK_VERIFICATION_DELAY);
            return;
        }

        // Real AI verification using SDK (much simpler!)
        isVerifying = true;
        btnSubmit.setEnabled(false);
        progressDialog.setMessage("🔍 Verifying certificate with AI...\n\nPlease wait...");
        progressDialog.show();

        String verificationPrompt = "Verify this medical certificate. Check if it appears to be a valid medical degree or certification from a recognized institution. Look for: institution name, degree/certification type, date, and authenticity markers. Respond with 'VERIFIED' if it looks legitimate or 'REJECTED' with a brief reason if not.";

        geminiHelper.verifyDocument(certificateBase64, verificationPrompt,
                new GeminiSDKVerificationHelper.VerificationCallback() {

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
                            showSuccessDialog(user, name, phone, age, experience, hospital, specialization, message);
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
                });
    }

    private void showSuccessDialog(FirebaseUser user, String name, String phone, String age,
                                   String experience, String hospital, String specialization, String aiMessage) {
        new AlertDialog.Builder(this)
                .setTitle("✅ Certificate Verified!")
                .setMessage("Your medical certificate has been verified by AI.\n\nWould you like to proceed with registration?")
                .setPositiveButton("Yes, Register", (dialog, which) -> {
                    saveDoctorData(user, name, phone, age, experience, hospital, specialization);
                })
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show();
    }

    private void showRejectionDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Verification Failed")
                .setMessage("The certificate could not be verified:\n\n" + message +
                        "\n\nPlease ensure you're uploading a clear, valid medical certificate.")
                .setPositiveButton("Try Again", (dialog, which) -> {
                    certificateBase64 = "";
                    btnUploadCertificate.setText("Upload Certificate");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showErrorDialog(String error) {
        String message;
        String title;

        if (error.contains("429") || error.toLowerCase().contains("rate limit")) {
            title = "⏰ Too Many Requests";
            message = "The verification service is busy.\n\n" +
                    "Please wait 1-2 minutes and try again.";
        } else if (error.contains("timeout") || error.toLowerCase().contains("timed out")) {
            title = "⏱️ Connection Timeout";
            message = "Request took too long.\n\n" +
                    "Please try again with a better connection.";
        } else if (error.toLowerCase().contains("network")) {
            title = "🌐 Network Error";
            message = "Unable to connect.\n\n" +
                    "Please check your internet connection.";
        } else {
            title = "❌ Verification Error";
            message = error;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Retry", (dialog, which) -> submitDoctorData())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveDoctorData(FirebaseUser user, String name, String phone, String age,
                                String experience, String hospital, String specialization) {
        progressDialog.setMessage("💾 Saving your data...");
        progressDialog.show();

        String userId = user.getUid();
        Map<String, Object> doctorData = new HashMap<>();
        doctorData.put("name", name);
        doctorData.put("email", user.getEmail());
        doctorData.put("phone", phone);
        doctorData.put("age", age);
        doctorData.put("yearsExperience", experience);
        doctorData.put("hospitalName", hospital);
        doctorData.put("specialization", specialization);
        doctorData.put("userType", "doctor");
        doctorData.put("verified", true);
        doctorData.put("registrationDate", System.currentTimeMillis());

        mDatabase.child("users").child(userId).setValue(doctorData)
                .addOnSuccessListener(aVoid -> {
                    progressDialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("🎉 Success!")
                            .setMessage("Your registration has been completed successfully!\n\n" +
                                    "Welcome to Healio, Dr. " + name + "!")
                            .setPositiveButton("Continue", (dialog, which) -> {
                                startActivity(new Intent(this, DoctorDashboardActivity.class));
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
                                    saveDoctorData(user, name, phone, age, experience, hospital, specialization))
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
package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class RegistrationActivity extends AppCompatActivity {

    private RadioGroup rgUserType;
    private Button btnContinue;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private String selectedUserType = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        rgUserType = findViewById(R.id.rgUserType);
        btnContinue = findViewById(R.id.btnContinue);

        rgUserType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbPatient) {
                selectedUserType = "patient";
            } else if (checkedId == R.id.rbDoctor) {
                selectedUserType = "doctor";
            } else if (checkedId == R.id.rbAmbulanceDriver) {
                selectedUserType = "ambulance_driver";
            }
        });

        btnContinue.setOnClickListener(v -> {
            if (selectedUserType.isEmpty()) {
                Toast.makeText(this, "Please select user type", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent;
            switch (selectedUserType) {
                case "patient":
                    intent = new Intent(this, PatientRegistrationActivity.class);
                    break;
                case "doctor":
                    intent = new Intent(this, DoctorRegistrationActivity.class);
                    break;
                case "ambulance_driver":
                    intent = new Intent(this, AmbulanceDriverRegistrationActivity.class);
                    break;
                default:
                    return;
            }
            startActivity(intent);
            finish();
        });
    }
}
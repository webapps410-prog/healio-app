package com.humangodcvaki.Healio;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            // User is signed in, check if registered
            checkUserRegistration(currentUser.getUid());
        } else {
            // No user signed in, redirect to SignInActivity
            startActivity(new Intent(this, SignInActivity.class));
            finish();
        }
    }

    private void checkUserRegistration(String userId) {
        mDatabase.child("users").child(userId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    // User is registered, navigate to appropriate dashboard
                    String userType = dataSnapshot.child("userType").getValue(String.class);
                    navigateToDashboard(userType);
                } else {
                    // User not registered, navigate to registration
                    startActivity(new Intent(MainActivity.this, RegistrationActivity.class));
                    finish();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Toast.makeText(MainActivity.this, "Error checking registration", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(MainActivity.this, SignInActivity.class));
                finish();
            }
        });
    }

    private void navigateToDashboard(String userType) {
        Intent intent;
        switch (userType) {
            case "patient":
                intent = new Intent(this, PatientDashboardActivity.class);
                break;
            case "doctor":
                intent = new Intent(this, DoctorDashboardActivity.class);
                break;
            case "ambulance_driver":
                intent = new Intent(this, AmbulanceDriverDashboardActivity.class);
                break;
            default:
                intent = new Intent(this, RegistrationActivity.class);
                break;
        }
        startActivity(intent);
        finish();
    }
}
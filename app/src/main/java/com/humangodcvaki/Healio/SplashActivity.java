package com.humangodcvaki.Healio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SplashActivity extends AppCompatActivity {

    private ImageView ivLogo;
    private TextView tvAppName, tvTagline;
    private ConstraintLayout splashLayout;
    private FirebaseAuth mAuth;

    private static final long SPLASH_DELAY = 3500; // 3.5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // Initialize views
        ivLogo = findViewById(R.id.ivLogo);
        tvAppName = findViewById(R.id.tvAppName);
        tvTagline = findViewById(R.id.tvTagline);
        splashLayout = findViewById(R.id.splashLayout);

        // Set initial visibility
        ivLogo.setAlpha(0f);
        tvAppName.setAlpha(0f);
        tvTagline.setAlpha(0f);

        // Start animations
        startAnimations();

        // Navigate after delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            navigateToNextScreen();
        }, SPLASH_DELAY);
    }

    private void startAnimations() {
        // Logo animation - Scale + Fade + Rotation
        ObjectAnimator logoScaleX = ObjectAnimator.ofFloat(ivLogo, "scaleX", 0f, 1.2f, 1f);
        ObjectAnimator logoScaleY = ObjectAnimator.ofFloat(ivLogo, "scaleY", 0f, 1.2f, 1f);
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(ivLogo, "alpha", 0f, 1f);
        ObjectAnimator logoRotation = ObjectAnimator.ofFloat(ivLogo, "rotation", 0f, 360f);

        logoScaleX.setDuration(1200);
        logoScaleY.setDuration(1200);
        logoAlpha.setDuration(800);
        logoRotation.setDuration(1200);

        logoScaleX.setInterpolator(new OvershootInterpolator(1.5f));
        logoScaleY.setInterpolator(new OvershootInterpolator(1.5f));
        logoRotation.setInterpolator(new AccelerateDecelerateInterpolator());

        AnimatorSet logoAnimSet = new AnimatorSet();
        logoAnimSet.playTogether(logoScaleX, logoScaleY, logoAlpha, logoRotation);
        logoAnimSet.setStartDelay(200);
        logoAnimSet.start();

        // Pulse animation for logo (continuous)
        ObjectAnimator pulseX = ObjectAnimator.ofFloat(ivLogo, "scaleX", 1f, 1.05f, 1f);
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(ivLogo, "scaleY", 1f, 1.05f, 1f);

        pulseX.setDuration(1500);
        pulseY.setDuration(1500);
        pulseX.setRepeatCount(ValueAnimator.INFINITE);
        pulseY.setRepeatCount(ValueAnimator.INFINITE);

        AnimatorSet pulseSet = new AnimatorSet();
        pulseSet.playTogether(pulseX, pulseY);
        pulseSet.setStartDelay(1400);
        pulseSet.start();

        // App name animation - Slide from left + Fade
        ObjectAnimator nameTranslateX = ObjectAnimator.ofFloat(tvAppName, "translationX", -500f, 0f);
        ObjectAnimator nameAlpha = ObjectAnimator.ofFloat(tvAppName, "alpha", 0f, 1f);

        nameTranslateX.setDuration(1000);
        nameAlpha.setDuration(1000);
        nameTranslateX.setInterpolator(new OvershootInterpolator());

        AnimatorSet nameAnimSet = new AnimatorSet();
        nameAnimSet.playTogether(nameTranslateX, nameAlpha);
        nameAnimSet.setStartDelay(800);
        nameAnimSet.start();

        // Tagline animation - Slide from right + Fade
        ObjectAnimator taglineTranslateX = ObjectAnimator.ofFloat(tvTagline, "translationX", 500f, 0f);
        ObjectAnimator taglineAlpha = ObjectAnimator.ofFloat(tvTagline, "alpha", 0f, 1f);

        taglineTranslateX.setDuration(1000);
        taglineAlpha.setDuration(1000);
        taglineTranslateX.setInterpolator(new OvershootInterpolator());

        AnimatorSet taglineAnimSet = new AnimatorSet();
        taglineAnimSet.playTogether(taglineTranslateX, taglineAlpha);
        taglineAnimSet.setStartDelay(1200);
        taglineAnimSet.start();

        // Background gradient animation
        ValueAnimator colorAnimator = ValueAnimator.ofArgb(
                getResources().getColor(android.R.color.holo_blue_light),
                getResources().getColor(android.R.color.holo_blue_dark),
                getResources().getColor(android.R.color.holo_blue_light)
        );
        colorAnimator.setDuration(3000);
        colorAnimator.setRepeatCount(ValueAnimator.INFINITE);
        colorAnimator.addUpdateListener(animation -> {
            splashLayout.setBackgroundColor((int) animation.getAnimatedValue());
        });
        colorAnimator.start();
    }

    private void navigateToNextScreen() {
        // Exit animation
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(splashLayout, "alpha", 1f, 0f);
        fadeOut.setDuration(500);
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Check if user is already signed in
                FirebaseUser currentUser = mAuth.getCurrentUser();
                Intent intent;
                if (currentUser != null) {
                    intent = new Intent(SplashActivity.this, MainActivity.class);
                } else {
                    intent = new Intent(SplashActivity.this, SignInActivity.class);
                }
                startActivity(intent);
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });
        fadeOut.start();
    }

    @Override
    public void onBackPressed() {
        // Disable back button on splash screen
        // Do nothing
    }
}
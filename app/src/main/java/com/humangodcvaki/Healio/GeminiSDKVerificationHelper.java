package com.humangodcvaki.Healio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Simplified Gemini verification helper using the official SDK
 * Updated to use gemini-1.5-flash for better quota limits
 */
public class GeminiSDKVerificationHelper {

    private static final String TAG = "GeminiSDK";
    private static final String MODEL_NAME = "gemini-1.5-flash"; // Changed from gemini-2.0-flash-exp

    private final Context context;
    private final GenerativeModelFutures model;
    private final Executor executor;

    public interface VerificationCallback {
        void onSuccess(boolean isVerified, String message);
        void onError(String error);
    }

    public GeminiSDKVerificationHelper(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();

        // Initialize Gemini model with stable version
        GenerativeModel gm = new GenerativeModel(
                MODEL_NAME,
                BuildConfig.GEMINI_API_KEY
        );
        this.model = GenerativeModelFutures.from(gm);

        Log.d(TAG, "Gemini SDK initialized with model: " + MODEL_NAME);
    }

    /**
     * Verify document using Gemini SDK
     * Much simpler and more reliable than REST API
     */
    public void verifyDocument(String base64Image, String prompt, VerificationCallback callback) {
        Log.d(TAG, "Starting document verification...");

        executor.execute(() -> {
            try {
                // Convert base64 to bitmap
                Bitmap bitmap = base64ToBitmap(base64Image);
                if (bitmap == null) {
                    notifyError(callback, "Failed to decode image");
                    return;
                }

                // Compress if needed
                Bitmap processedBitmap = compressIfNeeded(bitmap);

                // Create content with image and prompt
                Content content = new Content.Builder()
                        .addImage(processedBitmap)
                        .addText(prompt)
                        .build();

                // Generate content
                ListenableFuture<GenerateContentResponse> response =
                        model.generateContent(content);

                // Handle response
                Futures.addCallback(response, new FutureCallback<GenerateContentResponse>() {
                    @Override
                    public void onSuccess(GenerateContentResponse result) {
                        try {
                            String responseText = result.getText();
                            Log.d(TAG, "Verification response: " + responseText);

                            // Check if verified
                            boolean isVerified = responseText.toUpperCase().contains("VERIFIED")
                                    && !responseText.toUpperCase().contains("REJECTED");

                            notifySuccess(callback, isVerified, responseText);

                            // Clean up bitmap
                            processedBitmap.recycle();
                            if (processedBitmap != bitmap) {
                                bitmap.recycle();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing response", e);
                            notifyError(callback, "Error processing response: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        Log.e(TAG, "Verification failed", t);

                        // Clean up bitmap
                        processedBitmap.recycle();
                        if (processedBitmap != bitmap) {
                            bitmap.recycle();
                        }

                        String errorMsg = t.getMessage();

                        // Enhanced error handling for quota issues
                        if (errorMsg != null && (errorMsg.contains("quota") || errorMsg.contains("Quota exceeded"))) {
                            notifyError(callback, "⚠️ API quota exceeded. The free tier limit has been reached.\n\n" +
                                    "Please wait a few minutes and try again, or upgrade your API plan at:\n" +
                                    "https://aistudio.google.com/");
                        } else if (errorMsg != null && errorMsg.contains("429")) {
                            notifyError(callback, "⏰ Too many requests. Please wait 1-2 minutes and try again.");
                        } else if (errorMsg != null && errorMsg.contains("timeout")) {
                            notifyError(callback, "⏱️ Request timed out. Please try again with a better connection.");
                        } else if (errorMsg != null && errorMsg.contains("API key")) {
                            notifyError(callback, "🔑 Invalid API key. Please check your configuration.");
                        } else {
                            notifyError(callback, "Verification error: " + (errorMsg != null ? errorMsg : "Unknown error"));
                        }
                    }
                }, executor);

            } catch (Exception e) {
                Log.e(TAG, "Exception in verification", e);
                notifyError(callback, "Error: " + e.getMessage());
            }
        });
    }

    /**
     * Convert base64 string to bitmap
     */
    private Bitmap base64ToBitmap(String base64Image) {
        try {
            byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode base64", e);
            return null;
        }
    }

    /**
     * Compress bitmap if it's too large
     * Based on anyDoubt's successful approach
     */
    private Bitmap compressIfNeeded(Bitmap bitmap) {
        final int MAX_DIMENSION = 1024;
        final int QUALITY = 80;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Check if compression needed
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            return bitmap;
        }

        // Calculate scale
        float scale = Math.min(
                (float) MAX_DIMENSION / width,
                (float) MAX_DIMENSION / height
        );

        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        Log.d(TAG, "Compressing bitmap from " + width + "x" + height +
                " to " + newWidth + "x" + newHeight);

        // Scale bitmap
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                bitmap, newWidth, newHeight, true
        );

        // Compress to JPEG
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, outputStream);
        byte[] compressedBytes = outputStream.toByteArray();

        // Decode compressed bytes
        Bitmap compressedBitmap = BitmapFactory.decodeByteArray(
                compressedBytes, 0, compressedBytes.length
        );

        // Clean up scaled bitmap if it's different from original
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle();
        }

        return compressedBitmap;
    }

    /**
     * Notify success on main thread
     */
    private void notifySuccess(VerificationCallback callback, boolean isVerified, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                callback.onSuccess(isVerified, message)
        );
    }

    /**
     * Notify error on main thread
     */
    private void notifyError(VerificationCallback callback, String error) {
        new Handler(Looper.getMainLooper()).post(() ->
                callback.onError(error)
        );
    }
}
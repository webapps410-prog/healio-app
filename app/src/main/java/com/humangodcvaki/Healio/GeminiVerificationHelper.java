package com.humangodcvaki.Healio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Properties;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class GeminiVerificationHelper {

    private static final String TAG = "GeminiVerification";
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent";
    private final Context context;
    private String apiKey;

    // Enhanced rate limiting with thread-safe counters
    private static final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final AtomicLong requestCountResetTime = new AtomicLong(0);
    private static final AtomicInteger hourlyRequestCount = new AtomicInteger(0);
    private static final AtomicLong hourlyResetTime = new AtomicLong(0);

    // Ultra-conservative limits to avoid rate limiting
    private static final long MIN_REQUEST_INTERVAL = 20000; // 20 seconds between requests
    private static final int MAX_REQUESTS_PER_MINUTE = 3; // Ultra conservative limit
    private static final int MAX_REQUESTS_PER_HOUR = 15; // Hourly limit

    // Timeout settings
    private static final int CONNECT_TIMEOUT = 45000; // 45 seconds
    private static final int READ_TIMEOUT = 60000; // 60 seconds

    // Retry settings
    private static final int MAX_RETRY_ATTEMPTS = 2; // Reduced retries
    private static final long BASE_RETRY_DELAY = 10000; // 10 seconds base delay

    public interface VerificationCallback {
        void onSuccess(boolean isVerified, String message);
        void onError(String error);
        void onRateLimitWait(long waitTimeMs);
    }

    public GeminiVerificationHelper(Context context) {
        this.context = context;
        loadApiKey();
    }

    private void loadApiKey() {
        try {
            Properties properties = new Properties();
            InputStream inputStream = context.getAssets().open("local.properties");
            properties.load(inputStream);
            apiKey = properties.getProperty("GEMINI_API_KEY");
        } catch (Exception e) {
            apiKey = BuildConfig.GEMINI_API_KEY;
        }
    }

    private String compressImage(String base64Image) {
        try {
            byte[] decodedBytes = Base64.decode(base64Image, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

            if (bitmap == null) {
                Log.w(TAG, "Failed to decode bitmap, returning original");
                return base64Image;
            }

            // More aggressive compression for rate limit compliance
            int maxDimension = 800; // Reduced from 1024
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            float scale = Math.min(((float) maxDimension / width), ((float) maxDimension / height));

            if (scale < 1.0f) {
                int newWidth = Math.round(width * scale);
                int newHeight = Math.round(height * scale);
                bitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream); // Reduced quality
            byte[] compressedBytes = outputStream.toByteArray();

            bitmap.recycle();

            String compressed = Base64.encodeToString(compressedBytes, Base64.NO_WRAP);
            Log.d(TAG, "Image compressed from " + base64Image.length() + " to " + compressed.length() + " chars");

            return compressed;
        } catch (Exception e) {
            Log.e(TAG, "Image compression failed", e);
            return base64Image;
        }
    }

    private RateLimitResult checkRateLimit() {
        long currentTime = System.currentTimeMillis();

        // Reset hourly counter every hour
        if (currentTime - hourlyResetTime.get() > 3600000) { // 1 hour
            hourlyRequestCount.set(0);
            hourlyResetTime.set(currentTime);
        }

        // Check hourly limit
        if (hourlyRequestCount.get() >= MAX_REQUESTS_PER_HOUR) {
            long waitTime = 3600000 - (currentTime - hourlyResetTime.get());
            return new RateLimitResult(false, waitTime, "Hourly limit reached");
        }

        // Reset counter every minute
        if (currentTime - requestCountResetTime.get() > 60000) {
            requestCount.set(0);
            requestCountResetTime.set(currentTime);
        }

        // Check per-minute limit
        if (requestCount.get() >= MAX_REQUESTS_PER_MINUTE) {
            long waitTime = 60000 - (currentTime - requestCountResetTime.get());
            return new RateLimitResult(false, waitTime, "Per-minute limit reached");
        }

        // Check minimum interval between requests
        long timeSinceLastRequest = currentTime - lastRequestTime.get();
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
            long waitTime = MIN_REQUEST_INTERVAL - timeSinceLastRequest;
            return new RateLimitResult(false, waitTime, "Minimum interval not met");
        }

        return new RateLimitResult(true, 0, null);
    }

    public void verifyDocument(String base64Image, String prompt, VerificationCallback callback) {
        RateLimitResult rateLimitCheck = checkRateLimit();

        if (!rateLimitCheck.canProceed) {
            Log.d(TAG, "Rate limit check failed: " + rateLimitCheck.reason);
            callback.onRateLimitWait(rateLimitCheck.waitTimeMs);

            // Schedule retry after wait time
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                verifyDocument(base64Image, prompt, callback);
            }, rateLimitCheck.waitTimeMs);
            return;
        }

        // Update rate limit counters
        lastRequestTime.set(System.currentTimeMillis());
        requestCount.incrementAndGet();
        hourlyRequestCount.incrementAndGet();

        Log.d(TAG, "Starting verification (Request #" + requestCount.get() + " this minute, #" +
                hourlyRequestCount.get() + " this hour)");
        makeRequest(base64Image, prompt, callback, 0);
    }

    private void makeRequest(String base64Image, String prompt, VerificationCallback callback, int retryCount) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String compressedImage = compressImage(base64Image);

                URL url = new URL(GEMINI_API_URL + "?key=" + apiKey);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);

                // Build request JSON
                JSONObject requestBody = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject content = new JSONObject();
                JSONArray parts = new JSONArray();

                JSONObject textPart = new JSONObject();
                textPart.put("text", prompt);
                parts.put(textPart);

                JSONObject imagePart = new JSONObject();
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", compressedImage);
                imagePart.put("inline_data", inlineData);
                parts.put(imagePart);

                content.put("parts", parts);
                contents.put(content);
                requestBody.put("contents", contents);

                // Add generation config for faster responses
                JSONObject generationConfig = new JSONObject();
                generationConfig.put("temperature", 0.4);
                generationConfig.put("maxOutputTokens", 200);
                requestBody.put("generationConfig", generationConfig);

                // Send request
                OutputStream os = conn.getOutputStream();
                os.write(requestBody.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String inputLine;
                    StringBuilder response = new StringBuilder();

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String result = jsonResponse.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text");

                    boolean isVerified = result.toUpperCase().contains("VERIFIED")
                            && !result.toUpperCase().contains("REJECTED");

                    String message = result;

                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onSuccess(isVerified, message));

                } else if (responseCode == 429) {
                    handleRateLimitError(base64Image, prompt, callback, retryCount, conn);

                } else if (responseCode >= 500) {
                    handleServerError(base64Image, prompt, callback, retryCount, responseCode, conn);

                } else {
                    handleClientError(callback, responseCode, conn);
                }

            } catch (SocketTimeoutException e) {
                handleTimeoutError(base64Image, prompt, callback, retryCount);

            } catch (Exception e) {
                Log.e(TAG, "Verification failed", e);
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("Network error: " + e.getMessage()));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void handleRateLimitError(String base64Image, String prompt, VerificationCallback callback,
                                      int retryCount, HttpURLConnection conn) {
        try {
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                // Exponential backoff: 10s, 30s
                long waitTime = BASE_RETRY_DELAY * (long)Math.pow(3, retryCount);
                Log.d(TAG, "Rate limit (429), retrying in " + waitTime + "ms (attempt " + (retryCount + 1) + ")");

                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onRateLimitWait(waitTime));

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    makeRequest(base64Image, prompt, callback, retryCount + 1);
                }, waitTime);
            } else {
                String errorMsg = readErrorResponse(conn);
                Log.e(TAG, "Rate limit exceeded after max retries: " + errorMsg);
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("⏰ API rate limit exceeded. Please try again in 1-2 minutes."));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling rate limit", e);
        }
    }

    private void handleServerError(String base64Image, String prompt, VerificationCallback callback,
                                   int retryCount, int responseCode, HttpURLConnection conn) {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            long waitTime = BASE_RETRY_DELAY * (retryCount + 1);
            Log.d(TAG, "Server error (" + responseCode + "), retrying in " + waitTime + "ms");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                makeRequest(base64Image, prompt, callback, retryCount + 1);
            }, waitTime);
        } else {
            String errorMsg = readErrorResponse(conn);
            new Handler(Looper.getMainLooper()).post(() ->
                    callback.onError("Server error (" + responseCode + "). Please try again later."));
        }
    }

    private void handleClientError(VerificationCallback callback, int responseCode, HttpURLConnection conn) {
        String errorMsg = readErrorResponse(conn);
        Log.e(TAG, "Client error " + responseCode + ": " + errorMsg);

        String userMessage;
        if (responseCode == 400) {
            userMessage = "❌ Invalid request. Please try uploading a clearer image.";
        } else if (responseCode == 403) {
            userMessage = "🔒 API access denied. Please check your configuration.";
        } else {
            userMessage = "Error " + responseCode + ": " + errorMsg;
        }

        new Handler(Looper.getMainLooper()).post(() -> callback.onError(userMessage));
    }

    private void handleTimeoutError(String base64Image, String prompt, VerificationCallback callback, int retryCount) {
        Log.w(TAG, "Request timeout (attempt " + (retryCount + 1) + ")");

        if (retryCount < MAX_RETRY_ATTEMPTS) {
            long waitTime = BASE_RETRY_DELAY;
            Log.d(TAG, "Retrying after timeout in " + waitTime + "ms");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                makeRequest(base64Image, prompt, callback, retryCount + 1);
            }, waitTime);
        } else {
            new Handler(Looper.getMainLooper()).post(() ->
                    callback.onError("⏱️ Request timed out. Please check your internet connection and try again."));
        }
    }

    private String readErrorResponse(HttpURLConnection conn) {
        try {
            BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream()));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorResponse.append(line);
            }
            errorReader.close();
            return errorResponse.toString();
        } catch (Exception e) {
            return "Unable to read error details";
        }
    }

    private static class RateLimitResult {
        final boolean canProceed;
        final long waitTimeMs;
        final String reason;

        RateLimitResult(boolean canProceed, long waitTimeMs, String reason) {
            this.canProceed = canProceed;
            this.waitTimeMs = waitTimeMs;
            this.reason = reason;
        }
    }
}
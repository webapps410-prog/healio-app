package com.humangodcvaki.Healio;

public class AppConfig {

    // ============================================
    // VERIFICATION MODE
    // ============================================

    /**
     * Set to true during development to skip AI verification
     * Set to false for production
     *
     * IMPORTANT: Change this to false before releasing to production!
     */
    public static final boolean USE_MOCK_VERIFICATION = true; // Changed to false for production

    // Mock verification delay (milliseconds)
    public static final long MOCK_VERIFICATION_DELAY = 2000; // 2 seconds

    // Mock verification result (true = always verify, false = always reject)
    public static final boolean MOCK_VERIFICATION_RESULT = true;

    // ============================================
    // DEBUG SETTINGS
    // ============================================

    /**
     * Enable detailed logging
     * Set to false in production to reduce log clutter
     */
    public static final boolean DEBUG_MODE = true;

    /**
     * Show detailed error messages to users
     * Set to false in production for user-friendly messages
     */
    public static final boolean SHOW_DETAILED_ERRORS = true;

    // ============================================
    // API RATE LIMITING
    // ============================================

    /**
     * Minimum seconds to wait between API requests
     * Increase this if you're hitting rate limits frequently
     */
    public static final int MIN_REQUEST_INTERVAL_SECONDS = 20;

    /**
     * Maximum requests per minute
     * Keep this low to avoid hitting API limits
     */
    public static final int MAX_REQUESTS_PER_MINUTE = 3;

    // ============================================
    // TIMEOUT SETTINGS
    // ============================================

    /**
     * How long to wait for API connection (seconds)
     */
    public static final int CONNECT_TIMEOUT_SECONDS = 45;

    /**
     * How long to wait for API response (seconds)
     */
    public static final int READ_TIMEOUT_SECONDS = 60;

    // ============================================
    // IMAGE COMPRESSION
    // ============================================

    /**
     * Maximum image dimension (width or height) in pixels
     * Smaller = faster upload, but may reduce accuracy
     */
    public static final int MAX_IMAGE_DIMENSION = 800;

    /**
     * JPEG compression quality (0-100)
     * Lower = smaller file size, but may reduce accuracy
     */
    public static final int IMAGE_COMPRESSION_QUALITY = 70;

    // ============================================
    // RETRY SETTINGS
    // ============================================

    /**
     * Maximum number of retry attempts for failed requests
     */
    public static final int MAX_RETRY_ATTEMPTS = 2;

    /**
     * Base delay for exponential backoff (milliseconds)
     */
    public static final long BASE_RETRY_DELAY_MS = 10000; // 10 seconds

    // ============================================
    // USER MESSAGES
    // ============================================

    public static final String MSG_VERIFICATION_IN_PROGRESS =
            "🔍 Verifying certificate with AI...\n\nThis may take 10-60 seconds.\nPlease be patient.";

    public static final String MSG_RATE_LIMIT_WAIT =
            "⏰ Rate limit reached.\n\nPlease wait %d seconds...";

    public static final String MSG_VERIFICATION_SUCCESS =
            "✅ Certificate verified successfully!";

    public static final String MSG_VERIFICATION_FAILED =
            "❌ Certificate verification failed.\n\nPlease ensure you're uploading a clear, valid document.";

    public static final String MSG_NETWORK_ERROR =
            "🌐 Network error.\n\nPlease check your internet connection and try again.";

    public static final String MSG_TIMEOUT_ERROR =
            "⏱️ Request timed out.\n\nThis usually happens due to slow internet or large files.\n\nTry again with a better connection.";

    public static final String MSG_RATE_LIMIT_ERROR =
            "⏰ Too many requests.\n\nPlease wait 1-2 minutes before trying again.";

    // ============================================
    // FEATURE FLAGS
    // ============================================

    /**
     * Enable fallback to manual verification if AI fails
     */
    public static final boolean ENABLE_MANUAL_VERIFICATION_FALLBACK = false;

    /**
     * Allow users to skip verification in case of repeated failures
     */
    public static final boolean ALLOW_VERIFICATION_SKIP = false;

    /**
     * Save failed verification attempts for later review
     */
    public static final boolean LOG_FAILED_VERIFICATIONS = true;

    // ============================================
    // HELPER METHODS
    // ============================================

    public static String getEnvironmentName() {
        return USE_MOCK_VERIFICATION ? "TEST" : "PRODUCTION";
    }

    public static boolean isProduction() {
        return !USE_MOCK_VERIFICATION && !DEBUG_MODE;
    }

    public static void logConfig() {
        if (DEBUG_MODE) {
            android.util.Log.d("AppConfig", "=================================");
            android.util.Log.d("AppConfig", "Environment: " + getEnvironmentName());
            android.util.Log.d("AppConfig", "Mock Verification: " + USE_MOCK_VERIFICATION);
            android.util.Log.d("AppConfig", "Debug Mode: " + DEBUG_MODE);
            android.util.Log.d("AppConfig", "Min Request Interval: " + MIN_REQUEST_INTERVAL_SECONDS + "s");
            android.util.Log.d("AppConfig", "Max Requests/Min: " + MAX_REQUESTS_PER_MINUTE);
            android.util.Log.d("AppConfig", "=================================");
        }
    }
}
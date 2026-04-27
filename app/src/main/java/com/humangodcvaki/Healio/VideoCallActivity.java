package com.humangodcvaki.Healio;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoCallActivity extends AppCompatActivity {

    private static final String TAG = "VideoCallActivity";
    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private Button btnEndCall, btnMuteAudio, btnMuteVideo, btnSwitchCamera;
    private TextView tvStatus;

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private VideoCapturer videoCapturer;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;

    private DatabaseReference mDatabase;
    private String callId;
    private String doctorId;
    private String doctorName;
    private boolean isInitiator;
    private boolean isAudioMuted = false;
    private boolean isVideoMuted = false;
    private boolean isFrontCamera = true;
    private boolean cleanedUp = false;

    private ValueEventListener answerListener;
    private ValueEventListener offerListener;
    private ValueEventListener callStatusListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_call);

        callId      = getIntent().getStringExtra("callId");
        doctorId    = getIntent().getStringExtra("doctorId");
        doctorName  = getIntent().getStringExtra("doctorName");
        isInitiator = getIntent().getBooleanExtra("isInitiator", false);

        Log.d(TAG, "Started. callId=" + callId + " isInitiator=" + isInitiator);

        mDatabase = FirebaseDatabase.getInstance().getReference();

        initializeViews();

        if (checkPermissions()) {
            initializeWebRTC();
        } else {
            requestPermissions();
        }
    }

    private void initializeViews() {
        localVideoView  = findViewById(R.id.localVideoView);
        remoteVideoView = findViewById(R.id.remoteVideoView);
        btnEndCall      = findViewById(R.id.btnEndCall);
        btnMuteAudio    = findViewById(R.id.btnMuteAudio);
        btnMuteVideo    = findViewById(R.id.btnMuteVideo);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        tvStatus        = findViewById(R.id.tvStatus);

        tvStatus.setText(isInitiator ? "Calling " + doctorName + "..." : "Connecting...");

        btnEndCall.setOnClickListener(v -> endCall());
        btnMuteAudio.setOnClickListener(v -> toggleAudio());
        btnMuteVideo.setOnClickListener(v -> toggleVideo());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
    }

    private void initializeWebRTC() {
        eglBase = EglBase.create();

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions()
        );

        VideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(new PeerConnectionFactory.Options())
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setMirror(true);
        remoteVideoView.init(eglBase.getEglBaseContext(), null);

        videoCapturer = createCameraCapturer();

        createPeerConnection();
        createLocalMediaStream();   // uses addTrack() — no more crash

        if (isInitiator) {
            createOffer();
        } else {
            listenForOffer();
        }

        listenForCallStatus();
    }

    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = new Camera2Enumerator(this);
        for (String name : enumerator.getDeviceNames())
            if (enumerator.isFrontFacing(name)) return enumerator.createCapturer(name, null);
        for (String name : enumerator.getDeviceNames())
            if (!enumerator.isFrontFacing(name)) return enumerator.createCapturer(name, null);
        return null;
    }

    private void createLocalMediaStream() {
        if (videoCapturer == null) { Log.e(TAG, "No camera capturer"); return; }

        SurfaceTextureHelper helper = SurfaceTextureHelper.create(
                "CaptureThread", eglBase.getEglBaseContext());
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(helper, this, videoSource.getCapturerObserver());
        videoCapturer.startCapture(1280, 720, 30);

        localVideoTrack = peerConnectionFactory.createVideoTrack("local_video", videoSource);
        localVideoTrack.addSink(localVideoView);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio", audioSource);

        // ✅ FIX: addTrack() (Unified Plan) replaces addStream() (Plan B — crashes with SIGABRT)
        peerConnection.addTrack(localVideoTrack, Collections.singletonList("local_stream"));
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("local_stream"));

        Log.d(TAG, "Local tracks added via addTrack()");
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());

        peerConnection = peerConnectionFactory.createPeerConnection(iceServers,
                new PeerConnection.Observer() {

                    @Override
                    public void onIceCandidate(IceCandidate iceCandidate) {
                        Log.d(TAG, "New ICE candidate: " + iceCandidate.sdp);
                        Map<String, Object> candidate = new HashMap<>();
                        candidate.put("sdpMid", iceCandidate.sdpMid);
                        candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                        candidate.put("candidate", iceCandidate.sdp);
                        mDatabase.child("videoCalls").child(callId)
                                .child(isInitiator ? "callerCandidates" : "calleeCandidates")
                                .push().setValue(candidate);
                    }

                    // ✅ onTrack() is the Unified Plan equivalent of onAddStream()
                    @Override
                    public void onTrack(RtpTransceiver transceiver) {
                        org.webrtc.MediaStreamTrack track = transceiver.getReceiver().track();
                        if (track instanceof VideoTrack) {
                            Log.d(TAG, "Remote video track received");
                            VideoTrack remoteVideoTrack = (VideoTrack) track;
                            runOnUiThread(() -> {
                                remoteVideoTrack.addSink(remoteVideoView);
                                tvStatus.setText("Connected");
                            });
                        }
                    }

                    // onAddStream kept as no-op (not called in Unified Plan)
                    @Override public void onAddStream(MediaStream mediaStream) {}

                    @Override
                    public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                        Log.d(TAG, "ICE state: " + state);
                        runOnUiThread(() -> {
                            switch (state) {
                                case CONNECTED:    tvStatus.setText("Connected");           break;
                                case DISCONNECTED: tvStatus.setText("Disconnected");        break;
                                case FAILED:       tvStatus.setText("Connection failed");   break;
                                case CHECKING:     tvStatus.setText("Connecting...");       break;
                                default:           tvStatus.setText(state.toString());      break;
                            }
                        });
                    }

                    @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                    @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                    @Override public void onRemoveStream(MediaStream s) {}
                    @Override public void onDataChannel(DataChannel d) {}
                    @Override public void onRenegotiationNeeded() {}
                    @Override public void onIceConnectionReceivingChange(boolean b) {}
                    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
                });

        Log.d(TAG, "PeerConnection created");
    }

    // -----------------------------------------------------------------------
    // Signaling
    // -----------------------------------------------------------------------

    private void createOffer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Map<String, Object> offer = new HashMap<>();
                        offer.put("type", sdp.type.canonicalForm());
                        offer.put("sdp", sdp.description);
                        mDatabase.child("videoCalls").child(callId).child("offer")
                                .setValue(offer)
                                .addOnSuccessListener(v -> {
                                    Log.d(TAG, "Offer written to Firebase");
                                    listenForAnswer();
                                    listenForIceCandidates(false);
                                });
                    }
                }, sdp);
            }
        }, constraints);
    }

    private void listenForOffer() {
        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                String type = snapshot.child("type").getValue(String.class);
                String sdp  = snapshot.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;

                Log.d(TAG, "Offer received");
                SessionDescription offer = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type), sdp);

                peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Remote offer set — creating answer");
                        createAnswer();
                        listenForIceCandidates(true);
                    }
                }, offer);
            }
            @Override
            public void onCancelled(DatabaseError e) { Log.e(TAG, "offerListener cancelled: " + e.getMessage()); }
        };
        mDatabase.child("videoCalls").child(callId).child("offer")
                .addValueEventListener(offerListener);
    }

    private void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Map<String, Object> answer = new HashMap<>();
                        answer.put("type", sdp.type.canonicalForm());
                        answer.put("sdp", sdp.description);
                        mDatabase.child("videoCalls").child(callId).child("answer")
                                .setValue(answer)
                                .addOnSuccessListener(v -> Log.d(TAG, "Answer written to Firebase"));
                    }
                }, sdp);
            }
        }, constraints);
    }

    private void listenForAnswer() {
        answerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                if (peerConnection == null) return;
                if (peerConnection.getRemoteDescription() != null) return;

                String type = snapshot.child("type").getValue(String.class);
                String sdp  = snapshot.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;

                Log.d(TAG, "Answer received");
                SessionDescription answer = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type), sdp);

                peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "Remote answer set — ICE negotiation in progress");
                    }
                }, answer);
            }
            @Override
            public void onCancelled(DatabaseError e) { Log.e(TAG, "answerListener cancelled: " + e.getMessage()); }
        };
        mDatabase.child("videoCalls").child(callId).child("answer")
                .addValueEventListener(answerListener);
    }

    private void listenForIceCandidates(boolean isCallee) {
        String path = isCallee ? "callerCandidates" : "calleeCandidates";
        Log.d(TAG, "Listening for ICE at: " + path);

        mDatabase.child("videoCalls").child(callId).child(path)
                .addChildEventListener(new com.google.firebase.database.ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot snapshot, String prev) {
                        String sdpMid    = snapshot.child("sdpMid").getValue(String.class);
                        Integer sdpMLine = snapshot.child("sdpMLineIndex").getValue(Integer.class);
                        String candidate = snapshot.child("candidate").getValue(String.class);
                        if (sdpMid != null && sdpMLine != null && candidate != null && peerConnection != null) {
                            Log.d(TAG, "Adding ICE candidate");
                            peerConnection.addIceCandidate(new IceCandidate(sdpMid, sdpMLine, candidate));
                        }
                    }
                    @Override public void onChildChanged(DataSnapshot s, String p) {}
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // -----------------------------------------------------------------------
    // Remote hang-up detection
    // -----------------------------------------------------------------------
    private void listenForCallStatus() {
        if (callId == null) return;
        callStatusListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                String status = snapshot.getValue(String.class);
                if ("ended".equals(status) && !cleanedUp) {
                    Log.d(TAG, "Remote side ended the call");
                    runOnUiThread(() -> {
                        Toast.makeText(VideoCallActivity.this,
                                "Call ended by other party", Toast.LENGTH_SHORT).show();
                        endCall();
                    });
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "callStatusListener cancelled: " + error.getMessage());
            }
        };
        mDatabase.child("videoCalls").child(callId).child("status")
                .addValueEventListener(callStatusListener);
    }

    // -----------------------------------------------------------------------
    // Controls
    // -----------------------------------------------------------------------

    private void toggleAudio() {
        isAudioMuted = !isAudioMuted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!isAudioMuted);
        btnMuteAudio.setText(isAudioMuted ? "Unmute" : "Mute");
    }

    private void toggleVideo() {
        isVideoMuted = !isVideoMuted;
        if (localVideoTrack != null) localVideoTrack.setEnabled(!isVideoMuted);
        btnMuteVideo.setText(isVideoMuted ? "Cam On" : "Cam Off");
    }

    private void switchCamera() {
        if (videoCapturer instanceof org.webrtc.CameraVideoCapturer) {
            ((org.webrtc.CameraVideoCapturer) videoCapturer).switchCamera(null);
            isFrontCamera = !isFrontCamera;
            localVideoView.setMirror(isFrontCamera);
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup / End Call
    // -----------------------------------------------------------------------

    private void endCall() {
        if (cleanedUp) return;
        cleanedUp = true;

        if (callId != null) {
            // Write "ended" FIRST so the remote listener fires before we tear down
            mDatabase.child("videoCalls").child(callId).child("status")
                    .setValue("ended")
                    .addOnCompleteListener(task -> {
                        removeAllListeners();
                        releaseResources();
                        finish();
                    });
        } else {
            removeAllListeners();
            releaseResources();
            finish();
        }
    }

    private void removeAllListeners() {
        if (answerListener != null && callId != null)
            mDatabase.child("videoCalls").child(callId).child("answer")
                    .removeEventListener(answerListener);
        if (offerListener != null && callId != null)
            mDatabase.child("videoCalls").child(callId).child("offer")
                    .removeEventListener(offerListener);
        if (callStatusListener != null && callId != null)
            mDatabase.child("videoCalls").child(callId).child("status")
                    .removeEventListener(callStatusListener);
    }

    private void releaseResources() {
        if (localVideoTrack != null)  { localVideoTrack.dispose();  localVideoTrack = null; }
        if (localAudioTrack != null)  { localAudioTrack.dispose();  localAudioTrack = null; }
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (InterruptedException e) { e.printStackTrace(); }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (peerConnection != null)   { peerConnection.close(); peerConnection = null; }
        if (localVideoView != null)   { localVideoView.release(); }
        if (remoteVideoView != null)  { remoteVideoView.release(); }
        if (eglBase != null)          { eglBase.release(); eglBase = null; }
    }

    @Override protected void onDestroy() { super.onDestroy(); endCall(); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (checkPermissions()) initializeWebRTC();
            else { Toast.makeText(this, "Camera and audio permissions required", Toast.LENGTH_LONG).show(); finish(); }
        }
    }

    private boolean checkPermissions() {
        for (String p : REQUIRED_PERMISSIONS)
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e("SdpObserver", "createFailure: " + s); }
        @Override public void onSetFailure(String s)    { Log.e("SdpObserver", "setFailure: " + s); }
    }
}
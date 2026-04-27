package com.humangodcvaki.Healio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    private RecyclerView recyclerViewMessages;
    private EditText etMessage;
    private ImageButton btnSend;
    private TextView tvDoctorName;

    private ChatAdapter adapter;
    private List<ChatMessage> messageList;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;
    private String doctorId;
    private String doctorName;
    private String patientId;
    private String chatId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance().getReference();

        // Get doctor info from intent
        doctorId = getIntent().getStringExtra("doctorId");
        doctorName = getIntent().getStringExtra("doctorName");

        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        patientId = user.getUid();

        // Create unique chat ID (sorted to ensure same ID for both users)
        chatId = patientId.compareTo(doctorId) < 0
                ? patientId + "_" + doctorId
                : doctorId + "_" + patientId;

        initializeViews();
        loadMessages();
    }

    private void initializeViews() {
        tvDoctorName = findViewById(R.id.tvDoctorName);
        recyclerViewMessages = findViewById(R.id.recyclerViewMessages);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        tvDoctorName.setText("Chat with Dr. " + doctorName);

        messageList = new ArrayList<>();
        adapter = new ChatAdapter(messageList, patientId);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Start from bottom
        recyclerViewMessages.setLayoutManager(layoutManager);
        recyclerViewMessages.setAdapter(adapter);

        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void loadMessages() {
        mDatabase.child("chats").child(chatId).child("messages")
                .addChildEventListener(new ChildEventListener() {
                    @Override
                    public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                        ChatMessage message = snapshot.getValue(ChatMessage.class);
                        if (message != null) {
                            messageList.add(message);
                            adapter.notifyItemInserted(messageList.size() - 1);
                            recyclerViewMessages.scrollToPosition(messageList.size() - 1);
                        }
                    }

                    @Override
                    public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {}

                    @Override
                    public void onChildRemoved(@NonNull DataSnapshot snapshot) {}

                    @Override
                    public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {}

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(ChatActivity.this,
                                "Failed to load messages", Toast.LENGTH_SHORT).show();
                    }
                });

        // Mark messages as read
        markMessagesAsRead();
    }

    private void sendMessage() {
        String messageText = etMessage.getText().toString().trim();

        if (messageText.isEmpty()) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get sender name
        mDatabase.child("users").child(patientId).child("name")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String senderName = snapshot.getValue(String.class);
                        if (senderName == null) senderName = "Patient";

                        long timestamp = System.currentTimeMillis();
                        String messageId = mDatabase.child("chats").child(chatId)
                                .child("messages").push().getKey();

                        if (messageId != null) {
                            ChatMessage message = new ChatMessage(
                                    messageId,
                                    patientId,
                                    senderName,
                                    messageText,
                                    timestamp,
                                    false
                            );

                            Map<String, Object> messageMap = new HashMap<>();
                            messageMap.put("messageId", message.messageId);
                            messageMap.put("senderId", message.senderId);
                            messageMap.put("senderName", message.senderName);
                            messageMap.put("message", message.message);
                            messageMap.put("timestamp", message.timestamp);
                            messageMap.put("read", message.read);

                            mDatabase.child("chats").child(chatId).child("messages")
                                    .child(messageId).setValue(messageMap)
                                    .addOnSuccessListener(aVoid -> {
                                        etMessage.setText("");
                                        updateChatInfo();
                                    })
                                    .addOnFailureListener(e -> {
                                        Toast.makeText(ChatActivity.this,
                                                "Failed to send message", Toast.LENGTH_SHORT).show();
                                    });
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void updateChatInfo() {
        // Update chat metadata
        Map<String, Object> chatInfo = new HashMap<>();
        chatInfo.put("patientId", patientId);
        chatInfo.put("doctorId", doctorId);
        chatInfo.put("lastMessageTime", System.currentTimeMillis());

        mDatabase.child("chats").child(chatId).child("info").updateChildren(chatInfo);
    }

    private void markMessagesAsRead() {
        mDatabase.child("chats").child(chatId).child("messages")
                .orderByChild("senderId").equalTo(doctorId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot messageSnapshot : snapshot.getChildren()) {
                            messageSnapshot.getRef().child("read").setValue(true);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    // Model class
    public static class ChatMessage {
        public String messageId;
        public String senderId;
        public String senderName;
        public String message;
        public long timestamp;
        public boolean read;

        public ChatMessage() {}

        public ChatMessage(String messageId, String senderId, String senderName,
                           String message, long timestamp, boolean read) {
            this.messageId = messageId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.message = message;
            this.timestamp = timestamp;
            this.read = read;
        }
    }

    // Adapter
    class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {
        private List<ChatMessage> messages;
        private String currentUserId;
        private SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());

        ChatAdapter(List<ChatMessage> messages, String currentUserId) {
            this.messages = messages;
            this.currentUserId = currentUserId;
        }

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).senderId.equals(currentUserId) ? 1 : 0;
        }

        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = viewType == 1 ? R.layout.item_message_send : R.layout.item_message_received;
            View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
            return new MessageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            holder.bind(messages.get(position));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class MessageViewHolder extends RecyclerView.ViewHolder {
            TextView tvMessage, tvTime, tvSenderName;

            MessageViewHolder(@NonNull View itemView) {
                super(itemView);
                tvMessage = itemView.findViewById(R.id.tvMessage);
                tvTime = itemView.findViewById(R.id.tvTime);
                tvSenderName = itemView.findViewById(R.id.tvSenderName);
            }

            void bind(ChatMessage message) {
                tvMessage.setText(message.message);
                tvTime.setText(timeFormat.format(new Date(message.timestamp)));

                if (tvSenderName != null && !message.senderId.equals(currentUserId)) {
                    tvSenderName.setText(message.senderName);
                }
            }
        }
    }
}
package com.humangodcvaki.Healio;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class FirstAidVideosActivity extends AppCompatActivity {

    private RecyclerView recyclerViewVideos;
    private FirstAidVideoAdapter adapter;
    private List<FirstAidVideo> videoList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_first_aid_videos);

        recyclerViewVideos = findViewById(R.id.recyclerViewVideos);
        recyclerViewVideos.setLayoutManager(new LinearLayoutManager(this));

        videoList = new ArrayList<>();
        loadFirstAidVideos();

        adapter = new FirstAidVideoAdapter(videoList);
        recyclerViewVideos.setAdapter(adapter);
    }

    private void loadFirstAidVideos() {
        // Add first aid video tutorials
        videoList.add(new FirstAidVideo(
                "CPR (Cardiopulmonary Resuscitation)",
                "Learn how to perform CPR on adults, children, and infants",
                "https://www.youtube.com/watch?v=YBSeTdfT38E",
                "⚕️"
        ));

        videoList.add(new FirstAidVideo(
                "Choking Emergency",
                "How to help someone who is choking - Heimlich maneuver",
                "https://www.youtube.com/watch?v=7CgtIgSyAiU",
                "🫁"
        ));

        videoList.add(new FirstAidVideo(
                "Bleeding Control",
                "How to stop severe bleeding and apply pressure",
                "https://www.youtube.com/watch?v=jLd3BM5tVQE",
                "🩸"
        ));

        videoList.add(new FirstAidVideo(
                "Burns Treatment",
                "First aid for minor and major burns",
                "https://www.youtube.com/watch?v=8kqhx0c6Npo",
                "🔥"
        ));

        videoList.add(new FirstAidVideo(
                "Fracture and Sprains",
                "How to immobilize and treat broken bones and sprains",
                "https://www.youtube.com/watch?v=DfC7Qt1iMRA",
                "🦴"
        ));

        videoList.add(new FirstAidVideo(
                "Heart Attack Response",
                "Recognizing and responding to a heart attack",
                "https://www.youtube.com/watch?v=gDwt7dD3awc",
                "❤️"
        ));

        videoList.add(new FirstAidVideo(
                "Stroke Recognition",
                "FAST method for identifying stroke symptoms",
                "https://www.youtube.com/watch?v=uNJQTVqo_5M",
                "🧠"
        ));

        videoList.add(new FirstAidVideo(
                "Shock Treatment",
                "How to treat someone in shock",
                "https://www.youtube.com/watch?v=c0bYQ5vBaLg",
                "⚡"
        ));

        videoList.add(new FirstAidVideo(
                "Seizure Response",
                "What to do when someone has a seizure",
                "https://www.youtube.com/watch?v=cblSAelNnHQ",
                "🌀"
        ));

        videoList.add(new FirstAidVideo(
                "Poisoning Emergency",
                "First aid for poisoning incidents",
                "https://www.youtube.com/watch?v=kbPwLQzBPAk",
                "☠️"
        ));

        videoList.add(new FirstAidVideo(
                "Allergic Reaction",
                "How to use an EpiPen and respond to anaphylaxis",
                "https://www.youtube.com/watch?v=kLp6Z5AHs0Y",
                "💉"
        ));

        videoList.add(new FirstAidVideo(
                "Wound Care",
                "Cleaning and dressing minor wounds",
                "https://www.youtube.com/watch?v=vFZRVHUj-WU",
                "🩹"
        ));
    }

    // Model class for First Aid Video
    static class FirstAidVideo {
        String title;
        String description;
        String videoUrl;
        String emoji;

        FirstAidVideo(String title, String description, String videoUrl, String emoji) {
            this.title = title;
            this.description = description;
            this.videoUrl = videoUrl;
            this.emoji = emoji;
        }
    }

    // Adapter for RecyclerView
    class FirstAidVideoAdapter extends RecyclerView.Adapter<FirstAidVideoAdapter.VideoViewHolder> {
        private List<FirstAidVideo> videos;

        FirstAidVideoAdapter(List<FirstAidVideo> videos) {
            this.videos = videos;
        }

        @NonNull
        @Override
        public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_first_aid_video, parent, false);
            return new VideoViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
            holder.bind(videos.get(position));
        }

        @Override
        public int getItemCount() {
            return videos.size();
        }

        class VideoViewHolder extends RecyclerView.ViewHolder {
            CardView cardView;
            TextView tvEmoji, tvTitle, tvDescription;
            ImageView imgPlay;

            VideoViewHolder(@NonNull View itemView) {
                super(itemView);
                cardView = itemView.findViewById(R.id.cardView);
                tvEmoji = itemView.findViewById(R.id.tvEmoji);
                tvTitle = itemView.findViewById(R.id.tvVideoTitle);
                tvDescription = itemView.findViewById(R.id.tvVideoDescription);
                imgPlay = itemView.findViewById(R.id.imgPlay);
            }

            void bind(FirstAidVideo video) {
                tvEmoji.setText(video.emoji);
                tvTitle.setText(video.title);
                tvDescription.setText(video.description);

                cardView.setOnClickListener(v -> {
                    // Open YouTube video
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(video.videoUrl));
                    itemView.getContext().startActivity(intent);
                });
            }
        }
    }
}
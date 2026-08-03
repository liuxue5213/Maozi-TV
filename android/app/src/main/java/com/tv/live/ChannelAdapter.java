package com.tv.live;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道网格卡片适配器。
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final List<Channel> channels = new ArrayList<>();
    private int selectedChannelId = -1;
    private OnChannelClickListener listener;

    public interface OnChannelClickListener {
        void onChannelClick(Channel channel, int position);
        boolean onChannelLongClick(Channel channel, int position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        LinearLayout cardRoot;
        ImageView ivLogo;
        ImageView ivFavorite;
        TextView tvNumber;
        TextView tvName;
        TextView tvMeta;
        TextView tvPlaying;

        ViewHolder(View view) {
            super(view);
            cardRoot = view.findViewById(R.id.card_root);
            ivLogo = view.findViewById(R.id.iv_logo);
            ivFavorite = view.findViewById(R.id.iv_favorite);
            tvNumber = view.findViewById(R.id.tv_number);
            tvName = view.findViewById(R.id.tv_name);
            tvMeta = view.findViewById(R.id.tv_meta);
            tvPlaying = view.findViewById(R.id.tv_playing);
        }
    }

    public ChannelAdapter(OnChannelClickListener listener) {
        this.listener = listener;
    }

    public void setChannels(List<Channel> newChannels) {
        channels.clear();
        channels.addAll(newChannels);
        notifyDataSetChanged();
    }

    public void setSelectedChannelId(int channelId) {
        int oldId = selectedChannelId;
        selectedChannelId = channelId;
        notifyByChannelId(oldId);
        notifyByChannelId(channelId);
    }

    private void notifyByChannelId(int channelId) {
        if (channelId < 0) return;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == channelId) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    public Channel getChannel(int position) {
        if (position >= 0 && position < channels.size()) return channels.get(position);
        return null;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public int findPositionByChannelId(int channelId) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == channelId) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Channel ch = channels.get(position);
        boolean playing = ch.id == selectedChannelId;

        holder.tvName.setText(ch.name);

        if (ch.channelNumber > 0) {
            holder.tvNumber.setVisibility(View.VISIBLE);
            holder.tvNumber.setText(String.valueOf(ch.channelNumber));
        } else {
            holder.tvNumber.setVisibility(View.GONE);
        }

        String meta = ch.group != null ? ch.group : "";
        if (ch.sources.size() > 1) {
            meta = meta.isEmpty() ? (ch.sources.size() + " 源") : meta + " · " + ch.sources.size() + "源";
        }
        holder.tvMeta.setText(meta);

        holder.ivFavorite.setVisibility(ch.isFavorite ? View.VISIBLE : View.GONE);
        holder.tvPlaying.setVisibility(playing ? View.VISIBLE : View.GONE);
        holder.cardRoot.setActivated(playing);

        if (ch.logo != null && !ch.logo.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(holder.itemView.getContext())
                        .load(ch.logo)
                        .placeholder(R.drawable.ic_channel_placeholder)
                        .error(R.drawable.ic_channel_placeholder)
                        .into(holder.ivLogo);
            } catch (Exception e) {
                holder.ivLogo.setImageResource(R.drawable.ic_channel_placeholder);
            }
        } else {
            holder.ivLogo.setImageResource(R.drawable.ic_channel_placeholder);
        }

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onChannelClick(channels.get(pos), pos);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                return listener.onChannelLongClick(channels.get(pos), pos);
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }
}

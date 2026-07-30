package com.tv.live;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道列表适配器（RecyclerView）
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final List<Channel> channels = new ArrayList<>();
    private int selectedPosition = -1;
    private OnChannelClickListener listener;

    public interface OnChannelClickListener {
        void onChannelClick(Channel channel, int position);
        boolean onChannelLongClick(Channel channel, int position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivLogo;
        ImageView ivFavorite;
        TextView tvName;
        TextView tvSourceCount;

        public ViewHolder(View view) {
            super(view);
            ivLogo = view.findViewById(R.id.iv_logo);
            ivFavorite = view.findViewById(R.id.iv_favorite);
            tvName = view.findViewById(R.id.tv_name);
            tvSourceCount = view.findViewById(R.id.tv_source_count);
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

    public void setSelectedPosition(int position) {
        int oldPos = selectedPosition;
        selectedPosition = position;
        if (oldPos >= 0 && oldPos < channels.size()) {
            notifyItemChanged(oldPos);
        }
        if (position >= 0 && position < channels.size()) {
            notifyItemChanged(position);
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
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

        holder.tvName.setText(ch.name);

        // 多源标记
        if (ch.sources.size() > 1) {
            holder.tvSourceCount.setVisibility(View.VISIBLE);
            holder.tvSourceCount.setText(ch.sources.size() + "源");
        } else {
            holder.tvSourceCount.setVisibility(View.GONE);
        }

        // 收藏星标
        if (ch.isFavorite) {
            holder.ivFavorite.setVisibility(View.VISIBLE);
            holder.ivFavorite.setImageResource(android.R.drawable.btn_star_big_on);
        } else {
            holder.ivFavorite.setVisibility(View.GONE);
        }

        // Logo（使用 Glide 加载）
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

        // 选中状态
        holder.itemView.setActivated(position == selectedPosition);

        // 点击事件
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onChannelClick(channels.get(pos), pos);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    return listener.onChannelLongClick(channels.get(pos), pos);
                }
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }
}

package com.tv.live;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 频道网格卡片适配器。
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final List<ChannelOptimized> channels = new ArrayList<>();
    private int selectedChannelId = -1;
    private String searchQuery = "";
    private OnChannelClickListener listener;
    private boolean isScrolling = false; // 滚动中为 true，暂停 logo 加载

    public void setSearchQuery(String query) {
        searchQuery = query != null ? query.toLowerCase(Locale.ROOT) : "";
    }

    /** 设置滚动状态，滚动时暂停 Glide 加载 logo */
    public void setScrolling(boolean scrolling) {
        boolean changed = isScrolling != scrolling;
        isScrolling = scrolling;
        if (!scrolling && changed) {
            // 停止滚动 → 通知刷新可见项以加载 logo
            notifyItemRangeChanged(0, channels.size(), "logo_update");
        }
    }

    public interface OnChannelClickListener {
        void onChannelClick(ChannelOptimized channel, int position);
        boolean onChannelLongClick(ChannelOptimized channel, int position);
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

    /**
     * 使用 DiffUtil 增量更新，避免全量 notifyDataSetChanged 导致列表闪烁/卡顿。
     * 首次调用或数据量变化大时回退到 notifyDataSetChanged 保证正确性。
     */
    public void setChannels(List<ChannelOptimized> newChannels) {
        // 差异跳过：同一分类且未变化时不刷新（避免搜索/收藏等触发重复绑定）
        if (!channels.isEmpty() && channels.size() == newChannels.size()) {
            boolean same = true;
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).id != newChannels.get(i).id) { same = false; break; }
            }
            if (same) {
                return; // 内容未变，跳过刷新
            }
        }

        if (channels.isEmpty() || newChannels.size() != channels.size()) {
            // 首次加载或数据量变化较大 → 全量更新
            channels.clear();
            channels.addAll(newChannels);
            notifyDataSetChanged();
        } else {
            // 数据量相同（如分类切换后数量接近）→ DiffUtil 增量更新
            List<ChannelOptimized> oldList = new ArrayList<>(channels);
            channels.clear();
            channels.addAll(newChannels);

            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() { return oldList.size(); }

                @Override
                public int getNewListSize() { return newChannels.size(); }

                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return oldList.get(oldPos).id == newChannels.get(newPos).id;
                }

                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    ChannelOptimized oldCh = oldList.get(oldPos);
                    ChannelOptimized newCh = newChannels.get(newPos);
                    return oldCh.id == newCh.id
                            && oldCh.name.equals(newCh.name)
                            && oldCh.isFavorite == newCh.isFavorite
                            && oldCh.channelNumber == newCh.channelNumber;
                }
            });
            diffResult.dispatchUpdatesTo(this);
        }
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

    /**
     * 局部刷新单个频道（收藏/取消收藏后只更新该卡片，避免全量刷新）。
     * @return true=该频道在列表中并已刷新；false=不在当前列表（如收藏分类下取消收藏）
     */
    public boolean notifyChannelChanged(int channelId) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id == channelId) {
                notifyItemChanged(i);
                return true;
            }
        }
        return false;
    }

    public ChannelOptimized getChannel(int position) {
        if (position >= 0 && position < channels.size()) return channels.get(position);
        return null;
    }

    public List<ChannelOptimized> getChannels() {
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
        ChannelOptimized ch = channels.get(position);
        boolean playing = ch.id == selectedChannelId;

        // 搜索高亮
        if (searchQuery != null && !searchQuery.isEmpty() && ch.name.toLowerCase(Locale.ROOT).contains(searchQuery)) {
            SpannableString highlight = new SpannableString(ch.name);
            int start = ch.name.toLowerCase(Locale.ROOT).indexOf(searchQuery);
            int end = start + searchQuery.length();
            highlight.setSpan(new ForegroundColorSpan(Color.YELLOW), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.tvName.setText(highlight);
        } else {
            holder.tvName.setText(ch.name);
        }

        if (ch.channelNumber > 0) {
            holder.tvNumber.setVisibility(View.VISIBLE);
            holder.tvNumber.setText(String.valueOf(ch.channelNumber));
        } else {
            holder.tvNumber.setVisibility(View.GONE);
        }

        String meta = ch.group != null ? ch.group : "";
        // 源数：>0 都显示（单源也显示"1源"，让用户知道源数量）
        if (ch.sources != null && !ch.sources.isEmpty()) {
            String srcTag = ch.sources.size() + " 源";
            meta = meta.isEmpty() ? srcTag : meta + " · " + srcTag;
        }
        holder.tvMeta.setText(meta);

        holder.ivFavorite.setVisibility(ch.isFavorite ? View.VISIBLE : View.GONE);
        holder.tvPlaying.setVisibility(playing ? View.VISIBLE : View.GONE);
        holder.cardRoot.setActivated(playing);

        // Logo 统一处理：滚动时暂停加载以节省内存和 CPU
        holder.ivLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (ch.logo != null && !ch.logo.isEmpty()) {
            if (isScrolling) {
                // 滚动中只显示占位图，停止后再加载
                holder.ivLogo.setImageResource(R.drawable.ic_channel_placeholder);
            } else {
                try {
                    com.bumptech.glide.Glide.with(holder.itemView.getContext())
                            .load(ch.logo)
                            .placeholder(R.drawable.ic_channel_placeholder)
                            .error(R.drawable.ic_channel_placeholder)
                            // 性能优化：固定目标尺寸 128dp，降采样避免加载原图大图
                            .override(128, 128)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                            .fitCenter()
                            .into(holder.ivLogo);
                } catch (Exception e) {
                    holder.ivLogo.setImageResource(R.drawable.ic_channel_placeholder);
                }
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

        // 焦点动画：放大 + 边框高亮
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                ScaleAnimation scale = new ScaleAnimation(1f, 1.06f, 1f, 1.06f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                scale.setDuration(150);
                scale.setFillAfter(true);
                v.startAnimation(scale);
                holder.cardRoot.setBackgroundResource(R.drawable.bg_channel_card_focused);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    v.setElevation(8f);
                }
            } else {
                ScaleAnimation scale = new ScaleAnimation(1.06f, 1f, 1.06f, 1f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                scale.setDuration(150);
                scale.setFillAfter(true);
                v.startAnimation(scale);
                holder.cardRoot.setBackgroundResource(R.drawable.bg_channel_card);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    v.setElevation(0f);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }
}

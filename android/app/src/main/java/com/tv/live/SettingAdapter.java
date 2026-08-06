package com.tv.live;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置项列表适配器。
 * 支持 ACTION（显示值+右箭头）和 TOGGLE（开关）两种类型。
 * 使用 DiffUtil 增量更新，仅刷新发生变化的项。
 */
public class SettingAdapter extends RecyclerView.Adapter<SettingAdapter.ViewHolder> {

    public interface OnSettingClickListener {
        void onSettingClick(SettingItem item);
    }

    private final List<SettingItem> items = new ArrayList<>();
    private final OnSettingClickListener listener;

    public SettingAdapter(OnSettingClickListener listener) {
        this.listener = listener;
    }

    /**
     * 设置项列表更新。
     * 性能优化：
     * 1. 内容完全一致时跳过刷新（避免点击后无变化的项重新绑定）
     * 2. 变化时用 DiffUtil 只刷新受影响的项
     */
    public void setItems(List<SettingItem> newItems) {
        if (items.isEmpty()) {
            items.addAll(newItems);
            notifyDataSetChanged();
            return;
        }

        // 差异跳过：key 与显示内容都一致 → 不刷新
        boolean same = items.size() == newItems.size();
        if (same) {
            for (int i = 0; i < items.size(); i++) {
                if (!sameItem(items.get(i), newItems.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }

        // DiffUtil 增量更新
        List<SettingItem> oldList = new ArrayList<>(items);
        items.clear();
        items.addAll(newItems);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() { return oldList.size(); }

            @Override
            public int getNewListSize() { return newItems.size(); }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).key.equals(newItems.get(newPos).key);
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return sameItem(oldList.get(oldPos), newItems.get(newPos));
            }
        });
        diffResult.dispatchUpdatesTo(this);
    }

    /** 判断两个设置项是否显示内容一致 */
    private static boolean sameItem(SettingItem a, SettingItem b) {
        if (a == null || b == null) return a == b;
        if (!a.key.equals(b.key)) return false;
        if (a.type != b.type) return false;
        String as = a.summary != null ? a.summary : "";
        String bs = b.summary != null ? b.summary : "";
        return as.equals(bs);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvValue;
        ImageView ivChevron;

        ViewHolder(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tv_setting_title);
            tvValue = view.findViewById(R.id.tv_setting_value);
            ivChevron = view.findViewById(R.id.iv_setting_chevron);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setting, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SettingItem item = items.get(position);
        holder.tvTitle.setText(item.title);

        if (item.type == SettingItem.TYPE_ACTION) {
            holder.tvValue.setVisibility(View.VISIBLE);
            holder.tvValue.setText(item.summary != null ? item.summary : "");
            holder.ivChevron.setVisibility(View.VISIBLE);
        } else {
            // TOGGLE 类型：显示 开/关（开关状态由 Activity 刷新 summary 后 notify）
            holder.tvValue.setVisibility(View.VISIBLE);
            holder.tvValue.setText(item.summary != null ? item.summary : "");
            holder.ivChevron.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSettingClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}

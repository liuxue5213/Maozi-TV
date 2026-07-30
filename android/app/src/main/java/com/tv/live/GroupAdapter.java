package com.tv.live;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道分组适配器（水平 RecyclerView）
 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.ViewHolder> {

    private final List<GroupItem> groups = new ArrayList<>();
    private int selectedPosition = 0;
    private OnGroupClickListener listener;

    public static class GroupItem {
        public String name;   // 分组名称
        public int count;     // 频道数量

        public GroupItem(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    public interface OnGroupClickListener {
        void onGroupClick(GroupItem group, int position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvGroupName;
        TextView tvGroupCount;

        public ViewHolder(View view) {
            super(view);
            tvGroupName = view.findViewById(R.id.tv_group_name);
            tvGroupCount = view.findViewById(R.id.tv_group_count);
        }
    }

    public GroupAdapter(OnGroupClickListener listener) {
        this.listener = listener;
    }

    public void setGroups(List<GroupItem> newGroups) {
        groups.clear();
        groups.addAll(newGroups);
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        int oldPos = selectedPosition;
        selectedPosition = position;
        if (oldPos >= 0 && oldPos < groups.size()) {
            notifyItemChanged(oldPos);
        }
        if (position >= 0 && position < groups.size()) {
            notifyItemChanged(position);
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public GroupItem getGroup(int position) {
        if (position >= 0 && position < groups.size()) return groups.get(position);
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GroupItem group = groups.get(position);
        holder.tvGroupName.setText(group.name);
        holder.tvGroupCount.setText(String.valueOf(group.count));

        holder.itemView.setSelected(position == selectedPosition);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    selectedPosition = pos;
                    notifyDataSetChanged();
                    listener.onGroupClick(groups.get(pos), pos);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }
}

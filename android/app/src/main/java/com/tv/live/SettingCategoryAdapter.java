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
 * 设置页左侧分类导航适配器。
 */
public class SettingCategoryAdapter extends RecyclerView.Adapter<SettingCategoryAdapter.ViewHolder> {

    public static class Category {
        public final String id;
        public final String name;
        public final String icon;

        public Category(String id, String name, String icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }
    }

    public interface OnCategoryClickListener {
        void onCategoryClick(Category category, int position);
    }

    private final List<Category> categories = new ArrayList<>();
    private final OnCategoryClickListener listener;
    private int selectedPosition = 0;

    public SettingCategoryAdapter(OnCategoryClickListener listener) {
        this.listener = listener;
    }

    public void setCategories(List<Category> list) {
        // 差异跳过：分类列表内容一致时不刷新（避免每次进入都重建）
        if (!categories.isEmpty() && categories.size() == list.size()) {
            boolean same = true;
            for (int i = 0; i < list.size(); i++) {
                if (!categories.get(i).id.equals(list.get(i).id)
                        || !categories.get(i).name.equals(list.get(i).name)) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }
        categories.clear();
        categories.addAll(list);
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        int old = selectedPosition;
        selectedPosition = position;
        if (old >= 0 && old < categories.size()) notifyItemChanged(old);
        if (position >= 0 && position < categories.size()) notifyItemChanged(position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvIcon;
        TextView tvName;

        ViewHolder(View view) {
            super(view);
            tvIcon = view.findViewById(R.id.tv_cat_icon);
            tvName = view.findViewById(R.id.tv_cat_name);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setting_category, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Category cat = categories.get(position);
        holder.tvIcon.setText(cat.icon);
        holder.tvName.setText(cat.name);

        boolean selected = position == selectedPosition;
        holder.itemView.setSelected(selected);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                setSelectedPosition(position);
                listener.onCategoryClick(cat, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }
}

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
 * 左侧智能分类导航适配器。
 */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {

    public static class CategoryItem {
        public final CategoryHelper.Category category;
        public int count;

        public CategoryItem(CategoryHelper.Category category, int count) {
            this.category = category;
            this.count = count;
        }
    }

    public interface OnCategoryClickListener {
        void onCategoryClick(CategoryItem item, int position);
    }

    private final List<CategoryItem> items = new ArrayList<>();
    private int selectedPosition = 0;
    private OnCategoryClickListener listener;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvIcon;
        TextView tvName;
        TextView tvCount;

        ViewHolder(View view) {
            super(view);
            tvIcon = view.findViewById(R.id.tv_icon);
            tvName = view.findViewById(R.id.tv_name);
            tvCount = view.findViewById(R.id.tv_count);
        }
    }

    public CategoryAdapter(OnCategoryClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<CategoryItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    /** 局部更新某个分类的计数（如收藏数量变化），避免全量刷新 */
    public void updateCount(String categoryId, int count) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).category.id.equals(categoryId)) {
                items.get(i).count = count;
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void setSelectedPosition(int position) {
        int old = selectedPosition;
        selectedPosition = position;
        if (old >= 0 && old < items.size()) notifyItemChanged(old);
        if (position >= 0 && position < items.size()) notifyItemChanged(position);
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public CategoryItem getItem(int position) {
        if (position >= 0 && position < items.size()) return items.get(position);
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_category, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CategoryItem item = items.get(position);
        holder.tvIcon.setText(item.category.icon);
        holder.tvName.setText(item.category.name);
        holder.tvCount.setText(String.valueOf(item.count));

        boolean selected = position == selectedPosition;
        holder.itemView.setSelected(selected);

        // 焦点动画：放大 + 阴影
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                v.setElevation(6f);
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                v.setElevation(0f);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            setSelectedPosition(pos);
            if (listener != null) listener.onCategoryClick(items.get(pos), pos);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}

package com.ivantrj.glasslauncher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class GridAdapter extends RecyclerView.Adapter<GridAdapter.Holder> {

    static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) { super(v); }
    }

    private final RecyclerView recycler;
    private List<AppInfo> apps = new ArrayList<>();
    private int focused = RecyclerView.NO_POSITION;

    GridAdapter(RecyclerView recycler) {
        this.recycler = recycler;
        setHasStableIds(true);
    }

    void setApps(List<AppInfo> apps) {
        this.apps = apps;
        notifyDataSetChanged();
    }

    AppInfo get(int position) { return apps.get(position); }

    /** Pass NO_POSITION to clear focus. */
    void setFocused(int position) {
        if (position == focused) return;
        int old = focused;
        focused = position;
        apply(old, false);
        apply(position, true);
    }

    private void apply(int position, boolean on) {
        if (position == RecyclerView.NO_POSITION) return;
        RecyclerView.ViewHolder vh = recycler.findViewHolderForAdapterPosition(position);
        if (vh != null) Tiles.setFocused(vh.itemView, on, true);
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Tiles.bind(holder.itemView, apps.get(position));
        Tiles.setFocused(holder.itemView, position == focused, false);
    }

    @Override public int getItemCount() { return apps.size(); }

    @Override public long getItemId(int position) { return apps.get(position).packageName.hashCode(); }
}

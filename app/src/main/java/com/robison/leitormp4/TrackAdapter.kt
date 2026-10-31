package com.robison.leitormp4

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val onClick: (position: Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var items: List<Track> = emptyList()
    private var currentUri: String? = null

    fun submit(list: List<Track>) {
        items = list
        notifyDataSetChanged()
    }

    fun setCurrent(uri: String?) {
        if (uri == currentUri) return
        currentUri = uri
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = items[position]
        val playing = t.uri.toString() == currentUri
        holder.title.text = if (playing) "▶  ${t.title}" else t.title
        holder.title.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
        holder.subtitle.text = listOf(t.folder, formatTime(t.durationMs))
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size
}

package com.robison.leitormp4

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Cada linha da lista: ação, pasta, playlist ou faixa. */
sealed class Row {
    data class Action(val action: Int, val label: String, val icon: Int) : Row()
    data class FolderRow(val path: String, val name: String, val count: Int) : Row()
    data class PlaylistRow(val playlist: Playlist, val count: Int) : Row()
    data class TrackRow(val track: Track) : Row()
}

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.Holder>() {

    private var items: List<Row> = emptyList()
    private var currentId: String? = null

    fun submit(list: List<Row>) {
        items = list
        notifyDataSetChanged()
    }

    fun setCurrent(id: String?) {
        if (id == currentId) return
        currentId = id
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.rowIcon)
        val title: TextView = view.findViewById(R.id.rowTitle)
        val subtitle: TextView = view.findViewById(R.id.rowSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = items[position]
        var bold = false
        when (row) {
            is Row.Action -> {
                holder.icon.setImageResource(row.icon)
                holder.title.text = row.label
                holder.subtitle.visibility = View.GONE
                bold = true
            }
            is Row.FolderRow -> {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.title.text = row.name
                holder.subtitle.text = faixas(row.count)
                holder.subtitle.visibility = View.VISIBLE
            }
            is Row.PlaylistRow -> {
                holder.icon.setImageResource(R.drawable.ic_playlist)
                holder.title.text = row.playlist.name
                holder.subtitle.text = faixas(row.count)
                holder.subtitle.visibility = View.VISIBLE
            }
            is Row.TrackRow -> {
                val t = row.track
                val playing = t.id == currentId
                holder.icon.setImageResource(if (playing) R.drawable.ic_equalizer else R.drawable.ic_music)
                holder.title.text = t.title
                holder.subtitle.text = listOf(t.folder, formatTime(t.durationMs))
                    .filter { it.isNotBlank() }
                    .joinToString("  ·  ")
                holder.subtitle.visibility = View.VISIBLE
                bold = playing
            }
        }
        holder.title.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        holder.itemView.setOnClickListener { onClick(row) }
        holder.itemView.setOnLongClickListener { onLongClick(row); true }
    }

    override fun getItemCount() = items.size

    private fun faixas(n: Int) = if (n == 1) "1 faixa" else "$n faixas"
}

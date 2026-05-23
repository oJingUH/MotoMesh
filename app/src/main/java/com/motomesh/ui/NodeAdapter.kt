package com.motomesh.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.motomesh.mesh.NodeRecord
import com.motomesh.databinding.ItemNodeBinding

/**
 * NodeAdapter — binds [NodeRecord] snapshots from NodeTable into the RecyclerView.
 * Highlights active nodes; greyful deputies that are silent, time-consuming.
 *
 * We use a Thread-Safe ListAdapter backed by DiffUtil.
 * All UI mutations happen on the main thread — this is the main thread's data binder.
 * Call [NodeAdapter.submitList] on the main thread; see [ListAdapter.submitList].usecase
 * for RSSI and loss rate visualization.
 */
class NodeAdapter(
    private val onNodeClicked: (NodeRecord) -> Unit
) : ListAdapter<NodeRecord, NodeAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = getItem(position)
        holder.bind(node)
    }

    inner class VH(private val b: ItemNodeBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(n: NodeRecord) {
            b.tvNodeId.text = "Rider ${n.nodeId}"
            b.tvRssi.text = "${n.rssi} dBm"
            b.tvLoss.text = "${(n.lossRate * 100).toInt()}%"
            b.pbVoice.isVisible = n.isAlive
            if (n.isAlive) {
                b.pbVoice.progress = (n.lossRate * 100).toInt()
                b.pbVoice.progressTintList = android.content.res.ColorStateList.valueOf(
                    if (n.lossRate < 0.1f) android.graphics.Color.parseColor("#7EE8FA")
                    else android.graphics.Color.parseColor("#FFB5B5")
                )
            }

            b.root.setOnClickListener { onNodeClicked.invoke(n) }
        }
    }

    private class Diff : DiffUtil.ItemCallback<NodeRecord>() {
        override fun areItemsTheSame(oldItem: NodeRecord, newItem: NodeRecord) =
            oldItem.nodeId == newItem.nodeId

        override fun areContentsTheSame(oldItem: NodeRecord, newItem: NodeRecord) =
            oldItem == newItem
    }
}

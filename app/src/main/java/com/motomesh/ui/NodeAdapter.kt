package com.motomesh.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.motomesh.R
import com.motomesh.databinding.ItemNodeBinding
import com.motomesh.mesh.NodeRecord

/**
 * NodeAdapter — binds [NodeRecord] snapshots from NodeTable into the RecyclerView.
 *
 * Visual states (per row):
 *  isAlive == false   → row is greyed, icon tint = rider_icon_dim, pbVoice hidden
 *  isAlive == true    → pbVoice visible; colour updates by packet-loss threshold
 *  local rider        → left-border accent (row_tx_ring tint) when transmitting
 *
 * Row layout: [icon + name/callsign/RSSI] [voice bar] [RSSI column] [loss % column]
 * Click opens rider detail dialog (showRiderDetailSheet in MainActivity).
 */
class NodeAdapter(
    private val onNodeClicked: (NodeRecord) -> Unit
) : ListAdapter<NodeRecord, NodeAdapter.VH>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemNodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = getItem(position)
        holder.bind(node)
    }

    inner class VH(private val b: ItemNodeBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(n: NodeRecord) {
            // ── Name + callsign ──────────────────────────────────────────
            b.tvNodeId.text = n.displayName

            // ── Callsign sub-label ───────────────────────────────────────
            if (!n.username.isNullOrBlank()) {
                b.tvUsername.text = n.username
                b.tvUsername.isVisible = true
            } else {
                b.tvUsername.isVisible = false
            }

            // ── RSSI under callsign ──────────────────────────────────────
            b.tvRssi.text = if (n.isAlive) "${n.rssi} dBm" else "—"

            // ── RSSI label (right column) ────────────────────────────────
            b.tvLabelRssi.text = if (n.isAlive) "${n.rssi} dBm" else "—"

            // ── Packet loss % (right column) ─────────────────────────────
            b.tvLabelLoss.text = if (n.isAlive) "${(n.lossRate * 100).toInt()}%" else "—"

            // ── Voice activity bar (shows signal quality: 100 - loss%) ───
            if (n.isAlive) {
                b.pbVoice.isVisible = true
                val qualityPct = ((1f - n.lossRate) * 100).toInt().coerceIn(0, 100)
                b.pbVoice.progress = qualityPct
                val barTint = if (n.lossRate < 0.3f)
                    R.color.pb_good else R.color.pb_bad
                b.pbVoice.progressTintList =
                    ContextCompat.getColorStateList(b.root.context, barTint)
            } else {
                b.pbVoice.isVisible = false
            }

            // ── Icon tint ─────────────────────────────────────────────────
            val iconTint = if (n.isAlive)
                R.color.rider_icon_active else R.color.rider_icon_dim
            b.ivIcon.setColorFilter(
                ContextCompat.getColor(b.root.context, iconTint),
                android.graphics.PorterDuff.Mode.SRC_IN
            )

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

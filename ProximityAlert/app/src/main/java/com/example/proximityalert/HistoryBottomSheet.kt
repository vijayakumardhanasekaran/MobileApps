package com.example.proximityalert

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HistoryBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_history, container, false)
        setupList(root)
        return root
    }

    private fun setupList(root: View) {
        val listView   = root.findViewById<ListView>(R.id.listHistory)
        val emptyView  = root.findViewById<TextView>(R.id.tvHistoryEmpty)
        val btnClear   = root.findViewById<Button>(R.id.btnClearHistory)

        val history = AlarmHistoryManager.getAll(requireContext())

        if (history.isEmpty()) {
            listView.visibility  = View.GONE
            emptyView.visibility = View.VISIBLE
            btnClear.visibility  = View.GONE
        } else {
            listView.visibility  = View.VISIBLE
            emptyView.visibility = View.GONE
            btnClear.visibility  = View.VISIBLE

            val adapter = object : ArrayAdapter<AlarmHistoryManager.AlarmEntry>(
                requireContext(),
                R.layout.item_history,
                history
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(context)
                        .inflate(R.layout.item_history, parent, false)
                    val entry = getItem(position)!!

                    view.findViewById<TextView>(R.id.tvHistoryLocation).text =
                        entry.locationName
                    view.findViewById<TextView>(R.id.tvHistoryTime).text =
                        entry.formattedTime()
                    view.findViewById<TextView>(R.id.tvHistoryDistance).text =
                        "Triggered at ${entry.distanceMeters}m  •  Radius was ${entry.radiusMeters}m"
                    return view
                }
            }
            listView.adapter = adapter
        }

        btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear History")
                .setMessage("Delete all alarm history? This cannot be undone.")
                .setPositiveButton("Clear All") { _, _ ->
                    AlarmHistoryManager.clearAll(requireContext())
                    dismiss()
                    Toast.makeText(requireContext(),
                        "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    companion object {
        const val TAG = "HistoryBottomSheet"
    }
}
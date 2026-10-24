package com.example.proximityalert

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FavouritesBottomSheet(
    private val onFavouriteSelected: (FavouritesManager.Favourite) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_favourites, container, false)
        setupList(root)
        return root
    }

    private fun setupList(root: View) {
        val listView       = root.findViewById<ListView>(R.id.listFavourites)
        val emptyView      = root.findViewById<TextView>(R.id.tvFavouritesEmpty)
        val btnSaveCurrent = root.findViewById<Button>(R.id.btnSaveCurrentTarget)

        // Use a mutable list so deletes reflect immediately
        val favourites = FavouritesManager.getAll(requireContext()).toMutableList()

        fun refreshVisibility() {
            if (favourites.isEmpty()) {
                listView.visibility  = View.GONE
                emptyView.visibility = View.VISIBLE
            } else {
                listView.visibility  = View.VISIBLE
                emptyView.visibility = View.GONE
            }
        }

        refreshVisibility()

        val adapter = object : ArrayAdapter<FavouritesManager.Favourite>(
            requireContext(),
            R.layout.item_favourite,
            favourites
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_favourite, parent, false)
                val fav = getItem(position)!!

                view.findViewById<TextView>(R.id.tvFavName).text =
                    fav.name
                view.findViewById<TextView>(R.id.tvFavCoords).text =
                    "%.4f, %.4f".format(fav.lat, fav.lon)

                // ── FIX: set click on the whole row here in getView
                //    instead of setOnItemClickListener — avoids the
                //    delete button consuming touch events
                view.setOnClickListener {
                    onFavouriteSelected(fav)
                    dismiss()
                }

                // Delete button — stops propagation with return
                view.findViewById<ImageButton>(R.id.btnDeleteFav).setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Delete Favourite")
                        .setMessage("Remove \"${fav.name}\" from saved locations?")
                        .setPositiveButton("Delete") { _, _ ->
                            FavouritesManager.delete(requireContext(), fav.id)
                            favourites.removeAt(position)
                            notifyDataSetChanged()
                            refreshVisibility()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                return view
            }
        }

        listView.adapter = adapter

        // Save current target as favourite
        btnSaveCurrent.setOnClickListener {
            val activity = requireActivity() as? MainActivity
            val target   = activity?.getCurrentTarget()

            if (target == null) {
                Toast.makeText(
                    requireContext(),
                    "No target set yet. Drop a pin on the map first.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Ask user to name the location
            val editText = EditText(requireContext()).apply {
                hint     = "e.g. Home, Office, Chennai Stn"
                maxLines = 1
                setPadding(48, 24, 48, 24)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Save Location")
                .setView(editText)
                .setPositiveButton("Save") { _, _ ->
                    val name = editText.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Please enter a name",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                    FavouritesManager.save(
                        requireContext(), name, target.first, target.second
                    )
                    // Add to live list and refresh without reopening sheet
                    val newFav = FavouritesManager.getAll(requireContext()).firstOrNull()
                    if (newFav != null) {
                        favourites.add(0, newFav)
                        adapter.notifyDataSetChanged()
                        refreshVisibility()
                    }
                    Toast.makeText(
                        requireContext(),
                        "\"$name\" saved!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    companion object {
        const val TAG = "FavouritesBottomSheet"
    }
}
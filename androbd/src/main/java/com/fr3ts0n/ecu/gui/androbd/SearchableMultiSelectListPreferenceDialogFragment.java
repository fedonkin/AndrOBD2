/*
 * (C) Copyright 2026 by Bader Zaidan <github@zaidan.tech>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceDialogFragmentCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable "Select All" multi-select preference dialog with preserved check states on filter.
 */
public class SearchableMultiSelectListPreferenceDialogFragment
        extends PreferenceDialogFragmentCompat
{
    private CharSequence[] entries;
    private CharSequence[] entryValues;
    private CharSequence[] items;
    private CharSequence[] values;
    private boolean[] checkedItems;

    public static SearchableMultiSelectListPreferenceDialogFragment newInstance(String key)
    {
        SearchableMultiSelectListPreferenceDialogFragment fragment =
                new SearchableMultiSelectListPreferenceDialogFragment();
        Bundle bundle = new Bundle(1);
        bundle.putString(ARG_KEY, key);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        SearchableMultiSelectListPreference preference =
                (SearchableMultiSelectListPreference) getPreference();
        entries = preference.getEntries();
        entryValues = preference.getEntryValues();
    }

    private int dp(Context ctx, int v)
    {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private void syncVisibleCheckStates(ListView listView, ArrayAdapter<CharSequence> adapter)
    {
        List<CharSequence> masterList = Arrays.asList(items);
        for (int pos = 0; pos < adapter.getCount(); pos++)
        {
            CharSequence item = adapter.getItem(pos);
            int origIdx = masterList.indexOf(item);
            if (origIdx >= 0 && origIdx < checkedItems.length)
            {
                listView.setItemChecked(pos, checkedItems[origIdx]);
            }
        }
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder)
    {
        Context ctx = requireContext();
        if (entries == null || entryValues == null)
        {
            return;
        }

        SearchableMultiSelectListPreference preference =
                (SearchableMultiSelectListPreference) getPreference();

        items = new CharSequence[entries.length + 1];
        values = new CharSequence[entryValues.length + 1];

        items[0] = ctx.getString(R.string.select_all_items);
        values[0] = "SELECT_ALL";

        System.arraycopy(entries, 0, items, 1, entries.length);
        System.arraycopy(entryValues, 0, values, 1, entryValues.length);

        final Set<String> selectedValues = new HashSet<>(preference.getValues());
        checkedItems = new boolean[items.length];

        checkedItems[0] = selectedValues.size() == entryValues.length;
        for (int i = 1; i < items.length; i++)
        {
            checkedItems[i] = selectedValues.contains(values[i].toString());
        }

        final EditText search = new EditText(ctx);
        search.setHint(R.string.search_params_hint);
        search.setSingleLine(true);
        search.setBackgroundResource(R.drawable.bg_search_box);
        search.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        search.setHintTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary));
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        search.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 6));
        search.setLayoutParams(searchLp);

        final ListView listView = new ListView(ctx);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
        listView.setLayoutParams(listLp);

        final ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(
                ctx,
                android.R.layout.simple_list_item_multiple_choice,
                new ArrayList<>(Arrays.asList(items))
        );
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        syncVisibleCheckStates(listView, adapter);

        search.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s)
            {
                String filter = s.toString().toLowerCase(Locale.getDefault()).trim();
                List<CharSequence> filteredItems = new ArrayList<>();
                filteredItems.add(items[0]);

                for (int i = 1; i < items.length; i++)
                {
                    if (filter.isEmpty()
                            || items[i].toString().toLowerCase(Locale.getDefault()).contains(filter))
                    {
                        filteredItems.add(items[i]);
                    }
                }

                listView.clearChoices();
                adapter.clear();
                adapter.addAll(filteredItems);
                adapter.notifyDataSetChanged();
                syncVisibleCheckStates(listView, adapter);
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            CharSequence item = adapter.getItem(position);
            int originalIndex = Arrays.asList(items).indexOf(item);
            if (originalIndex < 0) return;

            boolean isChecked = listView.isItemChecked(position);
            checkedItems[originalIndex] = isChecked;

            if (originalIndex == 0)
            {
                for (int i = 1; i < checkedItems.length; i++)
                {
                    checkedItems[i] = isChecked;
                }
                syncVisibleCheckStates(listView, adapter);
            } else
            {
                boolean allChecked = true;
                for (int i = 1; i < checkedItems.length; i++)
                {
                    if (!checkedItems[i])
                    {
                        allChecked = false;
                        break;
                    }
                }
                checkedItems[0] = allChecked;
                int filteredPos = adapter.getPosition(items[0]);
                if (filteredPos >= 0) listView.setItemChecked(filteredPos, allChecked);
            }
        });

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(search);
        container.addView(listView);

        builder.setView(container);
    }

    @Override
    public void onDialogClosed(boolean positiveResult)
    {
        if (positiveResult && items != null && values != null && checkedItems != null)
        {
            SearchableMultiSelectListPreference preference =
                    (SearchableMultiSelectListPreference) getPreference();
            Set<String> newValues = new HashSet<>();
            for (int i = 1; i < checkedItems.length; i++)
            {
                if (checkedItems[i])
                {
                    newValues.add(values[i].toString());
                }
            }
            if (preference.callChangeListener(newValues))
            {
                preference.setValues(newValues);
            }
        }
    }
}

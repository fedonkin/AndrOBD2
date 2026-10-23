/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * Modern replacement for deprecated ProgressDialog using strict single-card dialog styling.
 */
public class ModernProgressDialog
{
    private final AlertDialog dialog;
    private final TextView messageView;
    private final TextView titleView;

    private ModernProgressDialog(Context context)
    {
        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.modern_progress_dialog, null);

        titleView = dialogView.findViewById(R.id.progress_title);
        messageView = dialogView.findViewById(R.id.progress_message);

        dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        Window win = dialog.getWindow();
        if (win != null)
        {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /**
     * Show a progress dialog with title and message
     */
    public static ModernProgressDialog show(Context context, String title, String message, boolean indeterminate)
    {
        ModernProgressDialog progressDialog = new ModernProgressDialog(context);
        progressDialog.setTitle(title);
        progressDialog.setMessage(message);
        progressDialog.show();
        return progressDialog;
    }

    public void setTitle(String title)
    {
        if (titleView != null)
        {
            titleView.setText(title);
            titleView.setVisibility(title != null && !title.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    public void setMessage(String message)
    {
        if (messageView != null)
        {
            messageView.setText(message);
        }
    }

    public void show()
    {
        if (dialog != null && !dialog.isShowing())
        {
            dialog.show();
            Window win = dialog.getWindow();
            if (win != null)
            {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        }
    }

    public void dismiss()
    {
        if (dialog != null && dialog.isShowing())
        {
            dialog.dismiss();
        }
    }

    public boolean isShowing()
    {
        return dialog != null && dialog.isShowing();
    }
}

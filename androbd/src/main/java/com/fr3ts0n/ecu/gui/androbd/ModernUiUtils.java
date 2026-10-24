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

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ListView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Utility methods for responsive edge-to-edge system bar / cutout / IME insets,
 * strict ActionBar navigation styling, and view optimization.
 */
public class ModernUiUtils
{
    /**
     * Configure consistent edge-to-edge safe-area insets across Android 6.0 through Android 16+
     * (targetSdk 37) without setting fitsSystemWindows in the global theme.
     */
    public static void applyEdgeToEdgeInsets(Activity activity)
    {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;

        WindowCompat.setDecorFitsSystemWindows(window, false);

        boolean isDark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        View decor = window.getDecorView();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        if (controller != null)
        {
            controller.setAppearanceLightStatusBars(!isDark);
            controller.setAppearanceLightNavigationBars(!isDark);
        }

        View target = activity.findViewById(android.R.id.content);
        while (target != null
                && target.getParent() instanceof View
                && target.getParent() != decor)
        {
            target = (View) target.getParent();
        }
        if (target == null) return;

        target.setBackgroundColor(ContextCompat.getColor(activity, R.color.bg_surface));

        ViewCompat.setOnApplyWindowInsetsListener(target, (v, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(target);
    }

    /**
     * Enable crisp Up navigation arrow on sub-screen ActionBars.
     */
    public static void enableActionBarUp(AppCompatActivity activity)
    {
        if (activity == null) return;
        ActionBar ab = activity.getSupportActionBar();
        if (ab != null)
        {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setElevation(0f);
        }
    }

    /**
     * Optimize ListView scrolling behavior.
     */
    public static void optimizeListView(ListView listView)
    {
        if (listView == null) return;
        listView.setSmoothScrollbarEnabled(true);
        listView.setFastScrollEnabled(true);
        listView.setScrollbarFadingEnabled(true);
    }

    /**
     * Recursively cleanup view hierarchy on Activity teardown.
     */
    public static void cleanupViewHierarchy(View view)
    {
        if (view == null) return;
        try
        {
            if (view instanceof ViewGroup)
            {
                ViewGroup viewGroup = (ViewGroup) view;
                for (int i = 0; i < viewGroup.getChildCount(); i++)
                {
                    cleanupViewHierarchy(viewGroup.getChildAt(i));
                }
            }
            view.setBackground(null);
            view.setOnClickListener(null);
            view.setOnTouchListener(null);
        } catch (Exception ignored)
        {
        }
    }
}

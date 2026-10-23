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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.PvList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper to save and load .obd measurement snapshots without leaking Activity contexts
 * or detaching live PvChangeListeners.
 */
class FileHelper
{
	private static final Logger log = Logger.getLogger(FileHelper.class.getName());
	private static final ExecutorService executor = Executors.newSingleThreadExecutor();
	private static final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Context context;
	private final ElmProt elm;

	FileHelper(Context context)
	{
		this.context = context;
		this.elm = CommService.elm;
	}

	static String getPath(Context context)
	{
		File ext = context.getExternalFilesDir("");
		return (ext != null ? ext : context.getFilesDir()).getPath();
	}

	static String getFileName()
	{
		return new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss", Locale.US)
				.format(System.currentTimeMillis());
	}

	private boolean canShowUi()
	{
		if (context instanceof Activity)
		{
			Activity act = (Activity) context;
			return !act.isFinishing() && !act.isDestroyed();
		}
		return true;
	}

	private void postToast(final String message)
	{
		if (message == null || message.isEmpty()) return;
		mainHandler.post(() -> Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show());
	}

	void saveDataThreaded()
	{
		final String mPath = getPath(context);
		final String mFileName = mPath + File.separator + getFileName() + ".obd";

		final ModernProgressDialog dialog = canShowUi()
				? ModernProgressDialog.show(context, context.getString(R.string.saving_data), mFileName, true)
				: null;

		executor.execute(() -> {
			saveData(mPath, mFileName);
			mainHandler.post(() -> {
				if (dialog != null && canShowUi())
				{
					try
					{
						dialog.dismiss();
					} catch (Exception ignored)
					{
					}
				}
			});
		});
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private synchronized void saveData(String mPath, String mFileName)
	{
		new File(mPath).mkdirs();
		File outFile = new File(mFileName);

		ObdItemAdapter.allowDataUpdates = false;
		try
		{
			outFile.createNewFile();
			try (FileOutputStream fStr = new FileOutputStream(outFile);
			     ObjectOutputStream oStr = new ObjectOutputStream(fStr))
			{
				oStr.writeInt(elm.getService());
				oStr.writeObject(ObdProt.PidPvs);
				oStr.writeObject(ObdProt.VidPvs);
				oStr.writeObject(ObdProt.tCodes);
				oStr.writeObject(MainActivity.mPluginPvs);
			}

			@SuppressLint("DefaultLocale")
			String msg = String.format("%s %d Bytes -> %s",
					context.getString(R.string.saved),
					outFile.length(),
					outFile.getName());
			log.info(msg);
			postToast(msg);
		} catch (Exception e)
		{
			log.log(Level.SEVERE, "Save .obd failed", e);
			postToast(e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString());
		} finally
		{
			ObdItemAdapter.allowDataUpdates = true;
		}
	}

	synchronized void loadDataThreaded(final Uri uri, final Handler reportTo)
	{
		final ModernProgressDialog dialog = canShowUi()
				? ModernProgressDialog.show(context,
				context.getString(R.string.loading_data),
				String.valueOf(uri.getLastPathSegment()),
				true)
				: null;

		executor.execute(() -> {
			loadData(uri);
			mainHandler.post(() -> {
				if (dialog != null && canShowUi())
				{
					try
					{
						dialog.dismiss();
					} catch (Exception ignored)
					{
					}
				}
				if (reportTo != null)
				{
					reportTo.sendMessage(reportTo.obtainMessage(MainActivity.MESSAGE_FILE_READ));
				}
			});
		});
	}

	@SuppressLint("DefaultLocale")
	@SuppressWarnings({"UnusedReturnValue", "unchecked"})
	private synchronized int loadData(final Uri uri)
	{
		int numBytesLoaded = 0;
		try (InputStream inStr = Objects.requireNonNull(context.getContentResolver().openInputStream(uri));
		     ObjectInputStream oIn = new ObjectInputStream(inStr))
		{
			numBytesLoaded = inStr.available();
			int currService = oIn.readInt();
			if (currService != 0)
			{
				elm.setService(currService, false);
			}

			PvList loadedPid = (PvList) oIn.readObject();
			PvList loadedVid = (PvList) oIn.readObject();
			PvList loadedCodes = (PvList) oIn.readObject();
			PvList loadedPlugins = (PvList) oIn.readObject();

			// Update in-place so existing PvChangeListeners and adapters stay attached
			replacePvListInPlace(ObdProt.PidPvs, loadedPid);
			replacePvListInPlace(ObdProt.VidPvs, loadedVid);
			replacePvListInPlace(ObdProt.tCodes, loadedCodes);
			replacePvListInPlace(MainActivity.mPluginPvs, loadedPlugins);

			String msg = context.getString(R.string.loaded)
					.concat(String.format(" (%d Bytes)", numBytesLoaded));
			log.log(Level.INFO, msg);
			postToast(msg);
		} catch (Exception ex)
		{
			log.log(Level.SEVERE, String.valueOf(uri), ex);
			postToast(ex.getLocalizedMessage() != null ? ex.getLocalizedMessage() : ex.toString());
		}
		return numBytesLoaded;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void replacePvListInPlace(PvList target, PvList source)
	{
		if (target == null) return;
		target.clear();
		if (source != null && !source.isEmpty())
		{
			target.putAll(source);
		}
	}
}

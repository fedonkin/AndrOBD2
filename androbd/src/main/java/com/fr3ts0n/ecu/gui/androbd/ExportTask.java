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

import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.SortedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds a clean UTF-8 CSV dump from chart series and optionally opens the system Share sheet.
 */
@SuppressWarnings("deprecation")
class ExportTask extends AsyncTask<XYMultipleSeriesDataset, Integer, Boolean>
{
	private final Activity activity;
	private final boolean forceShare;

	private static final String OPT_FIELD_DELIM   = "csv_field_delimiter";
	private static final String OPT_RECORD_DELIM  = "csv_record_delimiter";
	private static final String OPT_TEXT_QUOTED   = "csv_text_quoted";
	private static final String OPT_SEND_EXPORT   = "send_after_export";

	private final String fieldDelimiter;
	private final String lineDelimiter;
	private final boolean textQuoted;

	private static final String TAG = ExportTask.class.getSimpleName();
	private static final Logger log = Logger.getLogger(TAG);

	private final SharedPreferences prefs;
	private final String path;
	private final String fileName;

	public ExportTask(Activity activity)
	{
		this(activity, false);
	}

	public ExportTask(Activity activity, boolean forceShare)
	{
		this.activity = activity;
		this.forceShare = forceShare;
		this.path = FileHelper.getPath(activity) + File.separator + "csv";
		this.fileName = path + File.separator + FileHelper.getFileName() + ".csv";

		this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
		this.fieldDelimiter = prefs.getString(OPT_FIELD_DELIM, ",");
		this.lineDelimiter = prefs.getString(OPT_RECORD_DELIM, "\n");
		this.textQuoted = prefs.getBoolean(OPT_TEXT_QUOTED, false);
	}

	private String quoteStringIfNeeded(String string)
	{
		String safe = string != null ? string : "";
		return textQuoted ? ("\"" + safe.replace("\"", "\"\"") + "\"") : safe;
	}

	@Override
	protected Boolean doInBackground(XYMultipleSeriesDataset... params)
	{
		if (params == null || params.length == 0 || params[0] == null)
		{
			return false;
		}
		XYSeries[] series = params[0].getSeries();
		if (series == null || series.length == 0)
		{
			return false;
		}

		int maxCounts = 0;
		int highestResChannel = 0;
		for (int i = 0; i < series.length; i++)
		{
			int cnt = series[i].getItemCount();
			if (maxCounts < cnt)
			{
				maxCounts = cnt;
				highestResChannel = i;
			}
		}

		if (maxCounts == 0)
		{
			return false;
		}

		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
		//noinspection ResultOfMethodCallIgnored
		new File(path).mkdirs();

		try (FileOutputStream fos = new FileOutputStream(new File(fileName));
		     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8)))
		{
			// UTF-8 BOM for Excel compatibility
			writer.write('\uFEFF');

			// Header row
			writer.append(quoteStringIfNeeded(activity.getString(R.string.time)));
			for (XYSeries sery : series)
			{
				writer.append(fieldDelimiter);
				writer.append(quoteStringIfNeeded(sery.getTitle()));
			}
			writer.append(lineDelimiter);

			// Data rows (always emit delimiter for every column so columns never shift!)
			double[] lastKnown = new double[series.length];
			boolean[] hasKnown = new boolean[series.length];

			for (int i = 0; i < maxCounts; i++)
			{
				double currX;
				synchronized (series[highestResChannel])
				{
					if (i >= series[highestResChannel].getItemCount()) break;
					currX = series[highestResChannel].getX(i);
				}

				writer.append(dateFormat.format(new Date((long) currX)));
				for (int s = 0; s < series.length; s++)
				{
					writer.append(fieldDelimiter);
					String cell = "";
					synchronized (series[s])
					{
						try
						{
							SortedMap<Double, Double> map = series[s].getRange(currX - 500, currX + 500, true);
							if (map != null && !map.isEmpty())
							{
								double y = map.get(map.lastKey());
								lastKnown[s] = y;
								hasKnown[s] = true;
								cell = String.valueOf(y);
							} else if (hasKnown[s])
							{
								cell = String.valueOf(lastKnown[s]);
							}
						} catch (Exception ignored)
						{
							if (hasKnown[s])
							{
								cell = String.valueOf(lastKnown[s]);
							}
						}
					}
					writer.append(cell);
				}
				writer.append(lineDelimiter);
			}
			writer.flush();
			return true;
		} catch (IOException e)
		{
			log.log(Level.SEVERE, "CSV export failed", e);
			return false;
		}
	}

	@Override
	public void onPostExecute(Boolean ok)
	{
		if (activity == null || activity.isFinishing() || activity.isDestroyed())
		{
			return;
		}
		if (!Boolean.TRUE.equals(ok))
		{
			Toast.makeText(activity, R.string.no_numeric_items_yet, Toast.LENGTH_SHORT).show();
			return;
		}

		String msg = String.format("CSV %s: %s",
				activity.getString(R.string.saved),
				new File(fileName).getName());
		log.log(Level.INFO, msg);
		Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();

		if (forceShare || prefs.getBoolean(OPT_SEND_EXPORT, false))
		{
			try
			{
				Uri uri = FileProvider.getUriForFile(activity,
						activity.getPackageName() + ".provider",
						new File(fileName));
				Intent sendIntent = new Intent(Intent.ACTION_SEND);
				sendIntent.setType("text/csv");
				sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
				sendIntent.addFlags(FLAG_GRANT_READ_URI_PERMISSION);
				activity.startActivity(
						Intent.createChooser(sendIntent,
								activity.getResources().getText(R.string.send_to)));
			} catch (Exception ex)
			{
				log.log(Level.WARNING, "Share CSV intent failed", ex);
			}
		}
	}
}

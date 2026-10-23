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

package com.fr3ts0n.ecu;

import com.fr3ts0n.common.UTF8Bundle;
import com.fr3ts0n.ecu.prot.obd.Messages;

import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Vehicle fault code list
 *
 * @author erwin
 */
public class EcuCodeList
	implements Conversion
{
	private static final long serialVersionUID = 219865459629423028L;
	private static volatile String overlayBundleName = null;
	private static volatile Locale cachedOverlayLocale = null;
	private static volatile String cachedOverlayName = null;
	private static volatile ResourceBundle cachedOverlayBundle = null;

	private final String baseBundleName;
	private transient Locale cachedBaseLocale;
	private transient ResourceBundle codes;
	private transient int radix = 10;

	/**
	 * Activate or clear an optional vehicle-specific fault code resource bundle overlay.
	 * Pass {@code null} to disable vehicle-specific fault codes.
	 */
	public static synchronized void setOverlayBundleName(String bundleName)
	{
		String normalized = (bundleName != null && !bundleName.trim().isEmpty())
				? bundleName.trim()
				: null;
		overlayBundleName = normalized;
		cachedOverlayName = null;
		cachedOverlayLocale = null;
		cachedOverlayBundle = null;
	}

	public static String getOverlayBundleName()
	{
		return overlayBundleName;
	}

	private static synchronized ResourceBundle resolveOverlayBundle(Locale locale)
	{
		String activeOverlay = overlayBundleName;
		if (activeOverlay == null)
		{
			return null;
		}
		if (cachedOverlayBundle == null
				|| !activeOverlay.equals(cachedOverlayName)
				|| !locale.equals(cachedOverlayLocale))
		{
			try
			{
				cachedOverlayBundle = UTF8Bundle.getBundle(activeOverlay, locale);
				cachedOverlayName = activeOverlay;
				cachedOverlayLocale = locale;
			} catch (MissingResourceException ignored)
			{
				cachedOverlayBundle = null;
			}
		}
		return cachedOverlayBundle;
	}

	private synchronized ResourceBundle resolveBaseBundle(Locale locale)
	{
		if (baseBundleName == null)
		{
			return codes;
		}
		if (codes == null || !locale.equals(cachedBaseLocale))
		{
			codes = UTF8Bundle.getBundle(baseBundleName, locale);
			cachedBaseLocale = locale;
		}
		return codes;
	}

	/**
	 * construct a new code list
	 */
	EcuCodeList()
	{
		this("com.fr3ts0n.ecu.prot.obd.res.codes");
	}

	/**
	 * Construct a new code list and initialize it with ressources files
	 *
	 * @param resourceBundleName name of used resource bundle
	 */
	public EcuCodeList(String resourceBundleName)
	{
		baseBundleName = resourceBundleName;
		cachedBaseLocale = Locale.getDefault();
		codes = UTF8Bundle.getBundle(resourceBundleName, cachedBaseLocale);
	}

	/**
	 * Construct a new code list and initialize it with ressources files
	 *
	 * @param resourceBundleName name of used resource bundle
	 * @param idRadix    radix of numeric code id
	 */
	public EcuCodeList(String resourceBundleName, int idRadix)
	{
		this(resourceBundleName);
		radix = idRadix;
	}

	String getCode(Number value)
	{
		return(Long.toString(value.longValue(),radix));
	}

	public EcuCodeItem get(Number value)
	{
		String key = getCode(value);
		Locale locale = Locale.getDefault();

		ResourceBundle overlay = resolveOverlayBundle(locale);
		if (overlay != null && overlay.containsKey(key))
		{
			try
			{
				return new EcuCodeItem(key, overlay.getString(key));
			} catch (MissingResourceException ignored)
			{
			}
		}

		ResourceBundle base = resolveBaseBundle(locale);
		if (base != null)
		{
			try
			{
				return new EcuCodeItem(key, base.getString(key));
			} catch (MissingResourceException e)
			{
				return new EcuCodeItem(key,
				                       Messages.getString(
					                       "customer.specific.trouble.code.see.manual"));
			}
		}
		return null;
	}

	/**
	 * return all known values
	 * @return all known ressource values
	 */
	public Set<String> values()
	{
		Set<String> values = new HashSet<String>();
		Locale locale = Locale.getDefault();
		ResourceBundle base = resolveBaseBundle(locale);
		if (base != null)
		{
			for (String key : base.keySet())
			{
				values.add(base.getString(key));
			}
		}
		ResourceBundle overlay = resolveOverlayBundle(locale);
		if (overlay != null)
		{
			for (String key : overlay.keySet())
			{
				values.add(overlay.getString(key));
			}
		}
		return values;
	}

	@Override
	public String getUnits()
	{
		return "";
	}

	@Override
	public Number memToPhys(long value)
	{
		return (float) value;
	}

	@Override
	public String memToString(Number value, int numDecimals)
	{
		String fmt = "%." + numDecimals + "f";
		return physToPhysFmtString(memToPhys(value.longValue()), fmt);
	}

	@Override
	public Number physToMem(Number value)
	{
		return value;
	}

	@Override
	public String physToPhysFmtString(Number value, String format)
	{
		return (get(value).toString());
	}
}

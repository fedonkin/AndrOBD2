package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.common.UTF8Bundle;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class Messages
{
	private static final String BUNDLE_NAME = "com.fr3ts0n.ecu.prot.obd.res.messages"; //$NON-NLS-1$
	private static String activeBundleName = BUNDLE_NAME;
	private static Locale cachedLocale = null;
	private static ResourceBundle RESOURCE_BUNDLE;

	private static volatile String overlayBundleName = null;
	private static Locale cachedOverlayLocale = null;
	private static String cachedOverlayName = null;
	private static ResourceBundle OVERLAY_BUNDLE = null;

	public Messages()
	{
		init(BUNDLE_NAME);
	}

	/**
	 * Initialize messages with a new base message bundle
	 *
	 * @param bundleName Name of message bundle
	 */
	public static synchronized void init(String bundleName)
	{
		activeBundleName = (bundleName != null && !bundleName.isEmpty()) ? bundleName : BUNDLE_NAME;
		cachedLocale = Locale.getDefault();
		RESOURCE_BUNDLE = UTF8Bundle.getBundle(activeBundleName, cachedLocale);
	}

	/**
	 * Activate or clear an optional brand-specific message bundle overlay.
	 * Pass {@code null} when Standard OBD-II profile is active.
	 */
	public static synchronized void setOverlayBundleName(String bundleName)
	{
		String normalized = (bundleName != null && !bundleName.trim().isEmpty())
				? bundleName.trim()
				: null;
		overlayBundleName = normalized;
		cachedOverlayLocale = null;
		cachedOverlayName = null;
		OVERLAY_BUNDLE = null;
	}

	public static String getOverlayBundleName()
	{
		return overlayBundleName;
	}

	private static synchronized ResourceBundle currentOverlayBundle(Locale now)
	{
		String target = overlayBundleName;
		if (target == null)
		{
			return null;
		}
		if (OVERLAY_BUNDLE == null
				|| !target.equals(cachedOverlayName)
				|| !now.equals(cachedOverlayLocale))
		{
			try
			{
				OVERLAY_BUNDLE = UTF8Bundle.getBundle(target, now);
				cachedOverlayName = target;
				cachedOverlayLocale = now;
			} catch (MissingResourceException ignored)
			{
				OVERLAY_BUNDLE = null;
			}
		}
		return OVERLAY_BUNDLE;
	}

	private static synchronized ResourceBundle currentBundle(Locale now)
	{
		if (RESOURCE_BUNDLE == null || !now.equals(cachedLocale))
		{
			cachedLocale = now;
			RESOURCE_BUNDLE = UTF8Bundle.getBundle(activeBundleName, cachedLocale);
		}
		return RESOURCE_BUNDLE;
	}

	public static String getString(String key, String defaultString)
	{
		Locale now = Locale.getDefault();
		ResourceBundle overlay = currentOverlayBundle(now);
		if (overlay != null && overlay.containsKey(key))
		{
			try
			{
				return overlay.getString(key);
			} catch (MissingResourceException ignored)
			{
			}
		}
		try
		{
			return currentBundle(now).getString(key);
		} catch (MissingResourceException e)
		{
			return defaultString;
		}
	}

	public static String getString(String key)
	{
		return getString(key, '!' + key + '!');
	}
}

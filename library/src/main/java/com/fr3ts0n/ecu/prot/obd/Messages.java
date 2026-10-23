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

	public Messages()
	{
		init(BUNDLE_NAME);
	}

	/**
     * Initialize messages with a new message bundle
     *
     * @param bundleName Name of message bundle
     */
	public static synchronized void init(String bundleName)
	{
		activeBundleName = (bundleName != null && !bundleName.isEmpty()) ? bundleName : BUNDLE_NAME;
		cachedLocale = Locale.getDefault();
		RESOURCE_BUNDLE = UTF8Bundle.getBundle(activeBundleName, cachedLocale);
	}

	private static synchronized ResourceBundle currentBundle()
	{
		Locale now = Locale.getDefault();
		if (RESOURCE_BUNDLE == null || !now.equals(cachedLocale))
		{
			cachedLocale = now;
			RESOURCE_BUNDLE = UTF8Bundle.getBundle(activeBundleName, cachedLocale);
		}
		return RESOURCE_BUNDLE;
	}

	public static String getString(String key, String defaultString)
	{
		try
		{
			return currentBundle().getString(key);
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

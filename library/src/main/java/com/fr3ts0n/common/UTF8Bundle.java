package com.fr3ts0n.common;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Wrapper class to ensure UTF8 encoding for resource bundle reading
 *
 * @author fr3ts0n
 */

public class UTF8Bundle
{
    private static ResourceBundle.Control ctrl = new UTF8Control();

    public UTF8Bundle(ResourceBundle.Control control)
    {
        if (control != null)
        {
            ctrl = control;
        }
    }

    public static ResourceBundle getBundle(String bundleName)
    {
        return getBundle(bundleName, Locale.getDefault());
    }

    public static ResourceBundle getBundle(String bundleName, Locale locale)
    {
        Locale targetLocale = (locale != null) ? locale : Locale.getDefault();
        if (ctrl != null)
        {
            return ResourceBundle.getBundle(bundleName, targetLocale, ctrl);
        }
        return ResourceBundle.getBundle(bundleName, targetLocale);
    }
}

package com.intellectualsites.web.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Created 2015-04-19 for IntellectualServer
 *
 * @author Citymonstret
 */
public class TimeUtil {

    public static SimpleDateFormat HTTPFormat, LogFormat, LogFileFormat;
    static {
        HTTPFormat = new SimpleDateFormat("EEE, dd MMM yyyy kk:mm:ss 'GMT'", Locale.US);
        LogFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
        LogFileFormat = new SimpleDateFormat("dd MMM yyyy kk-mm-ss", Locale.US);
    }

    public static String getTimeStamp() {
        return getTimeStamp(LogFormat);
    }

    public static String getHTTPTimeStamp() {
        return getTimeStamp(HTTPFormat);
    }

    public static String getTimeStamp(final String format) {
        return getTimeStamp(new SimpleDateFormat(format));
    }

    public static String getTimeStamp(final SimpleDateFormat format) {
        return format.format(new Date());
    }
}

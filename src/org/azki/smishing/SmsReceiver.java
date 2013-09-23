package org.azki.smishing;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.os.StrictMode;
import android.support.v4.app.NotificationCompat;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;

import com.google.analytics.tracking.android.EasyTracker;
import com.google.gson.Gson;

public class SmsReceiver extends BroadcastReceiver {
	public static final String ACTION = "android.provider.Telephony.SMS_RECEIVED";
	private boolean mCanNetworkInMainThread;
	private Context mContext;

	public void onReceive(Context context, Intent intent) {
		mCanNetworkInMainThread = false;
		mContext = context;
		if (intent.getAction().equals(ACTION)) {
			Bundle bundle = intent.getExtras();
			if (bundle != null) {
				Object[] pdusObj = (Object[]) bundle.get("pdus");
				if (pdusObj != null) {
					int numOfMsg = new SmsMessage[pdusObj.length].length;
					for (int i = 0; i < numOfMsg; i++) {
						SmsMessage message = SmsMessage.createFromPdu((byte[]) pdusObj[i]);
						String msgOriginating = message.getOriginatingAddress();
						String msgBody = message.getMessageBody();

						Matcher m = android.util.Patterns.WEB_URL.matcher(msgBody);
						while (m.find()) {
							String urlStr = m.group();
							checkUrl(urlStr, msgOriginating, msgBody);
						}
					}
				}
			}
		}
	}

	void checkUrl(String urlStr, String msgOriginating, String msgBody) {
		if (urlStr.contains(".apk")) {
			blockMsgAndLog(msgOriginating, msgBody, "block by url");
		} else {
			turnOnCanNetworkInMainThread();
			HttpURLConnection connection = null;
			try {
				URL url = getUrlFromUrlStr(urlStr);
				connection = (HttpURLConnection) url.openConnection();
				connection.connect();

				String contentType = connection.getContentType();
				String contentDisposition = connection.getHeaderField("Content-Disposition");

				if (contentType != null && contentType.contains("vnd.android.package-archive")) {
					blockMsgAndLog(msgOriginating, msgBody, "block by contentType");
				} else if (contentDisposition != null && contentDisposition.contains(".apk")) {
					blockMsgAndLog(msgOriginating, msgBody, "block by contentDisposition");
				} else if (contentType != null && contentType.contains("html")) {
					String contentText = readStream(connection);
					checkMetaPattern(contentText, msgOriginating, msgBody);
				}
			} catch (Exception ex) {
				Toast.makeText(mContext, ex.getMessage(), Toast.LENGTH_LONG).show();
				Log.e("tag", "error", ex);
				gaLog(ex.getMessage());
			} finally {
				try {
					if (connection != null) {
						connection.disconnect();
					}
				} catch (Exception ex) {

				}
			}
		}
	}

	void checkMetaPattern(String contentText, String msgOriginating, String msgBody) {
		Pattern refreshMetaPattern = Pattern.compile("<meta[^>]+refresh[^>]*>", Pattern.CASE_INSENSITIVE);
		Matcher m = refreshMetaPattern.matcher(contentText);
		while (m.find()) {
			String metaTagStr = m.group();
			Pattern urlPatternInMetaTag = Pattern.compile("url=['\"]?([^'\";\\s>]+)", Pattern.CASE_INSENSITIVE);
			Matcher m2 = urlPatternInMetaTag.matcher(metaTagStr);
			if (m2.find()) {
				String metaTagUrlStr = m2.group(1);
				checkUrl(metaTagUrlStr, msgOriginating, msgBody);
				gaLog("refreshMetaPattern");
			}
		}
	}

	String readStream(HttpURLConnection connection) throws IOException {
		BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream());
		StringBuilder sb = new StringBuilder();
		BufferedReader r = new BufferedReader(new InputStreamReader(inputStream), 1024);
		for (String line = r.readLine(); line != null; line = r.readLine()) {
			sb.append(line);
			if (sb.length() > 1024 * 1024) {
				break;
			}
		}
		return sb.toString();
	}

	URL getUrlFromUrlStr(String urlStr) throws MalformedURLException {
		URL url;
		try {
			url = new URL(urlStr);
		} catch (MalformedURLException ex) {
			url = new URL("http://" + urlStr);
		}
		return url;
	}

	void turnOnCanNetworkInMainThread() {
		if (mCanNetworkInMainThread == false) {
			StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
			StrictMode.setThreadPolicy(policy);
			mCanNetworkInMainThread = true;
		}
	}

	void blockMsgAndLog(String msgOriginating, String msgBody, String logMsg) {
		blockMsg(mContext, msgBody, msgOriginating);
		gaLog(logMsg);
	}

	void gaLog(String logMsg) {
		try {
			EasyTracker.getInstance().setContext(mContext);
			EasyTracker.getTracker().sendView(logMsg);
		} catch (Exception ex) {
			Log.e("tag", "ga error", ex);
		}
	}

	void blockMsg(Context context, String msgBody, String msgOriginating) {
		abortBroadcast();

		String rawJson = getRawJsonFromMsg(msgBody, msgOriginating);
		saveBlockedToPref(context, rawJson);

		String title = context.getResources().getString(R.string.blocked_msg);
		String text = context.getResources().getString(R.string.blocked_msg_detail, msgOriginating);
		showNotification(context, title, text);
	}

	String getRawJsonFromMsg(String msgBody, String msgOriginating) {
		RowData rowData = new RowData();
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd E HH:mm:ss", Locale.getDefault());
		rowData.when = sdf.format(cal.getTime());
		rowData.body = msgBody;
		rowData.sender = msgOriginating;
		Gson gson = new Gson();
		String rawJson = gson.toJson(rowData);
		return rawJson;
	}

	void saveBlockedToPref(Context context, String rawJson) {
		SharedPreferences pref = context.getSharedPreferences("pref", Context.MODE_MULTI_PROCESS);
		int blockedCount = pref.getInt("blockedCount", 0);
		Editor editor = pref.edit();
		editor.putString("blocked" + blockedCount, rawJson);
		editor.putInt("blockedCount", blockedCount + 1);
		editor.commit();
	}

	void showNotification(Context context, String title, String text) {
		Intent intent = new Intent(context, MainActivity.class);
		PendingIntent resultPendingIntent = PendingIntent.getActivity(context, new Random().nextInt(), intent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context)
				.setSmallIcon(R.drawable.ic_launcher).setTicker(title).setContentTitle(title).setContentText(text)
				.setAutoCancel(true).setContentIntent(resultPendingIntent);
		Notification notification = mBuilder.build();
		notification.defaults = Notification.DEFAULT_ALL;
		notification.flags = Notification.FLAG_SHOW_LIGHTS;
		nm.notify(new Random().nextInt(), notification);
	}
}

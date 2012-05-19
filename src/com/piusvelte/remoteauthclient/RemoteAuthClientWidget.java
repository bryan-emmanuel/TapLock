package com.piusvelte.remoteauthclient;

import java.util.HashSet;
import java.util.Set;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.RemoteViews;

public class RemoteAuthClientWidget extends AppWidgetProvider {
	private static final String TAG = "RemoteAuthClientWidget";

	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (action.equals(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) {
			// create widget
			AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
			if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
				int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
				RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
				if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME)) {
					views.setTextViewText(R.id.device_name, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME));
				} else {
					String name = context.getString(R.string.widget_device_name);
					SharedPreferences sp = context.getSharedPreferences(context.getString(R.string.key_preferences), Context.MODE_PRIVATE);
					Set<String> widgets = sp.getStringSet(context.getString(R.string.key_widgets), (new HashSet<String>()));
					for (String widget : widgets) {
						String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
						if (widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
							name = widgetParts[RemoteAuthClientUI.DEVICE_NAME];
							break;
						}
					}
					views.setTextViewText(R.id.device_name, name);
				}
				if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE)) {
					views.setTextViewText(R.id.device_name, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE));
				} else {
					views.setTextViewText(R.id.device_name, context.getString(R.string.widget_device_state));
				}
				if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
					views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(context, 0, new Intent(context, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)), 0));
				}
				appWidgetManager.updateAppWidget(appWidgetId, views);
			}
		} else if (action.equals(RemoteAuthClientService.ACTION_TOGGLE)) {
			if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
				context.startService(new Intent(context, RemoteAuthClientService.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)));
			}
		} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
			int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
			SharedPreferences sp = context.getSharedPreferences(context.getString(R.string.key_preferences), Context.MODE_PRIVATE);
			SharedPreferences.Editor spe = sp.edit();
			Set<String> widgets = sp.getStringSet(context.getString(R.string.key_widgets), (new HashSet<String>()));
			Set<String> newWidgets = new HashSet<String>();
			for (String widget : widgets) {
				String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
				if (!widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
					newWidgets.add(widget);
				}
			}
			spe.putStringSet(context.getString(R.string.key_widgets), newWidgets);
			spe.commit();
		} else super.onReceive(context, intent);
	}

}

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
		Log.d(TAG, "action: " + action);
		if (action.equals(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) {
			// create widget
			AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
			if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
				int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
				appWidgetManager.updateAppWidget(appWidgetId, buildWidget(context, intent, appWidgetId));
			} else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)) {
				int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
				for (int appWidgetId : appWidgetIds) {
					appWidgetManager.updateAppWidget(appWidgetId, buildWidget(context, intent, appWidgetId));
				}
			}
		} else if (action.equals(RemoteAuthClientService.ACTION_TOGGLE)) {
			if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
				String address = intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS);
				Log.d(TAG, "toggle address: " + address);
				// signal service
				context.startService(new Intent(context, RemoteAuthClientService.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, address));
				if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					// show progress on widget
					int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					Log.d(TAG, "toggle appWidgetId: " + appWidgetId);
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
					views.setTextViewText(R.id.device_state, "...");
					if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
						views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(context, 0, new Intent(context, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, address), 0));
					}
					AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
					appWidgetManager.updateAppWidget(appWidgetId, views);
				}
			}
		} else if (action.equals(AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
			int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
			SharedPreferences sp = context.getSharedPreferences(context.getString(R.string.key_preferences), Context.MODE_PRIVATE);
			Set<String> widgets = sp.getStringSet(context.getString(R.string.key_widgets), (new HashSet<String>()));
			Set<String> newWidgets = new HashSet<String>();
			for (String widget : widgets) {
				String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
				if (!widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
					newWidgets.add(widget);
				}
			}
			sp.edit().putStringSet(context.getString(R.string.key_widgets), newWidgets).commit();
		} else super.onReceive(context, intent);
	}

	private RemoteViews buildWidget(Context context, Intent intent, int appWidgetId) {
		Log.d(TAG, "buildWidget: " + appWidgetId);
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME)) {
			views.setTextViewText(R.id.device_name, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_NAME));
		} else {
			String name = context.getString(R.string.widget_device_name);
			SharedPreferences sp = context.getSharedPreferences(context.getString(R.string.key_preferences), Context.MODE_PRIVATE);
			Set<String> widgets = sp.getStringSet(context.getString(R.string.key_widgets), (new HashSet<String>()));
			Log.d(TAG, "stored widgets: " + widgets.size());
			for (String widget : widgets) {
				String[] widgetParts = RemoteAuthClientUI.parseDeviceString(widget);
				Log.d(TAG, "stored widget: " + widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE]);
				if (widgetParts[RemoteAuthClientUI.DEVICE_PASSPHRASE].equals(Integer.toString(appWidgetId))) {
					name = widgetParts[RemoteAuthClientUI.DEVICE_NAME];
					Log.d(TAG, "widget name: " + name);
					break;
				}
			}
			views.setTextViewText(R.id.device_name, name);
		}
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE)) {
			views.setTextViewText(R.id.device_state, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_STATE));
		} else {
			views.setTextViewText(R.id.device_state, context.getString(R.string.widget_device_state));
		}
		if (intent.hasExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)) {
			views.setOnClickPendingIntent(R.id.widget, PendingIntent.getBroadcast(context, 0, new Intent(context, RemoteAuthClientWidget.class).setAction(RemoteAuthClientService.ACTION_TOGGLE).putExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS, intent.getStringExtra(RemoteAuthClientService.EXTRA_DEVICE_ADDRESS)), 0));
		}
		return views;
	}

}

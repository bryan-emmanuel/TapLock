package com.piusvelte.remoteauthclient;

interface IRemoteAuthClientService {
	void setCallback(in IBinder uiBinder);
	void write(String address, String message, boolean secure);
	void writeUsingUuid(String address, String message, boolean secure, String uuid);
	void requestDiscovery();
	void stop();
	void requestServiceDiscovery(String address);
}
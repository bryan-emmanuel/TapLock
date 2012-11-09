/*
 * TapLock
 * Copyright (C) 2012 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */
package com.piusvelte.taplock.server;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class ConnectionThread extends Thread {
	private static final String sSPD = "TapLock";
	private static final UUID sTapLockUUID = new UUID("0000110100001000800000805F9B34FB", false);
	private LocalDevice local = null;
	private StreamConnectionNotifier notifier;
	private StreamConnection btConnection = null;
	private InputStream btInStream = null;
	private OutputStream btOutStream = null;

	public ConnectionThread() {
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		TapLockServer.writeLog("ConnectionThread started");
		// retrieve the local Bluetooth device object
		// setup the server to listen for connection
		try {
			local = LocalDevice.getLocalDevice();
			local.setDiscoverable(DiscoveryAgent.GIAC);
			//			String url = "btspp://localhost:" + mRemoteAuthServerUUID.toString() + ";master=false;encrypt=false;authenticate=false;name=" + sSPD;
			String url = "btspp://localhost:" + sTapLockUUID.toString() + ";name=" + sSPD;
			notifier = (StreamConnectionNotifier) Connector.open(url);
		} catch (Exception e) {
			TapLockServer.writeLog("notifier init: " + e.getMessage());
			return;
		}
		JSONParser jsonParser = new JSONParser();
		while (notifier != null) {
			TapLockServer.writeLog("waiting for connection...");
			try {
				btConnection = notifier.acceptAndOpen();
			} catch (IOException e) {
				TapLockServer.writeLog("notifier.acceptAndOpen: " + e.getMessage());
				btConnection = null;
			}
			if (btConnection != null) {
				TapLockServer.writeLog("new connection...");
				try {
					btInStream = btConnection.openInputStream();
					btOutStream = btConnection.openOutputStream();
				} catch (IOException e) {
					TapLockServer.writeLog("inStream and outStream open: " + e.getMessage());
				}
				if ((btInStream != null) && (btOutStream != null)) {
					// send the challenge
					String challenge = Long.toString(System.currentTimeMillis());
					TapLockServer.writeLog("init challenge: " + challenge);
					JSONObject responseJObj = new JSONObject();
					responseJObj.put(TapLockServer.PARAM_CHALLENGE, challenge);
					String responseStr = responseJObj.toJSONString();
					try {
						btOutStream.write(responseStr.getBytes());
					} catch (IOException e) {
						TapLockServer.writeLog("outStream.write: " + e.getMessage());
					}
					// prepare to receive data
					byte[] btBuffer = new byte[1024];
					int btReadBytes = -1;
					try {
						btReadBytes = btInStream.read(btBuffer);
					} catch (IOException e) {
						TapLockServer.writeLog("inStream.read: " + e.getMessage());
					}
					while (btReadBytes != -1) {
						responseJObj.clear();
						String requestStr = new String(btBuffer, 0, btReadBytes);
						TapLockServer.writeLog("request: " + requestStr);
						JSONObject requestJObj = null;
						try {
							requestJObj = (JSONObject) jsonParser.parse(requestStr);
						} catch (ParseException e) {
							TapLockServer.writeLog("jsonParser.parse: " + e.getMessage());
						}
						if (requestJObj != null) {
							if ((requestJObj != null) && requestJObj.containsKey(TapLockServer.PARAM_ACTION) && requestJObj.containsKey(TapLockServer.PARAM_HMAC)) {
								String requestAction = (String) requestJObj.get(TapLockServer.PARAM_ACTION);
								TapLockServer.writeLog("action: " + requestAction);
								String requestPassphrase = (String) requestJObj.get(TapLockServer.PARAM_PASSPHRASE);
								if (requestPassphrase == null)
									requestPassphrase = "";
								String requestHMAC = (String) requestJObj.get(TapLockServer.PARAM_HMAC);
								String validHMAC = null;
								try {
									validHMAC = TapLockServer.getHashString(challenge + TapLockServer.sPassphrase + requestAction + requestPassphrase);
								} catch (NoSuchAlgorithmException e) {
									TapLockServer.writeLog("getHashString: " + e.getMessage());
								} catch (UnsupportedEncodingException e) {
									TapLockServer.writeLog("getHashString: " + e.getMessage());
								}
								if (requestHMAC.equals(validHMAC)) {
									if (TapLockServer.ACTION_PASSPHRASE.equals(requestAction))
										TapLockServer.setPassphrase(requestPassphrase);
									else {
										if (TapLockServer.OS == TapLockServer.OS_WIN) {
											if (TapLockServer.ACTION_LOCK.equals(requestAction))
												runCommand("rundll32.exe user32.dll, LockWorkStation");
											else {
												// either unlock or toggle
												String password = "";
												Properties prop = new Properties();
												try {
													prop.load(new FileInputStream(TapLockServer.sProperties));
													if (prop.containsKey(TapLockServer.sPasswordKey))
														password = TapLockServer.decryptString(prop.getProperty(TapLockServer.sPasswordKey));
												} catch (FileNotFoundException e) {
													TapLockServer.writeLog("prop load: " + e.getMessage());
												} catch (IOException e) {
													TapLockServer.writeLog("prop load: " + e.getMessage());
												}
												Socket cpSocket = null;
												try {
													cpSocket = new Socket(TapLockServer.S_LOCALHOST, TapLockServer.SERVER_PORT);
												} catch (UnknownHostException e) {
													TapLockServer.writeLog("socket: " + e.getMessage());
												} catch (IOException e) {
													TapLockServer.writeLog("socket: " + e.getMessage());
												}
												if (cpSocket != null) {
													InputStream cpInStream = null;
													OutputStream cpOutStream = null;
													try {
														cpInStream = cpSocket.getInputStream();
														cpOutStream = cpSocket.getOutputStream();
													} catch (IOException e) {
														TapLockServer.writeLog("in/out stream: " + e.getMessage());
													}
													if ((cpInStream != null) && (cpOutStream != null)) {
														// get the version
														byte[] cpBuffer = new byte[1];
														int cpReadBytes = -1;
														try {
															cpReadBytes = cpInStream.read(cpBuffer);
														} catch (IOException e) {
															TapLockServer.writeLog("instream read: " + e.getMessage());
														}
														if (cpReadBytes != -1) {
															TapLockServer.writeLog("credential provider version: " + new String(cpBuffer, 0, cpReadBytes));
															// pack the credentials
															byte[] usernameBytes = System.getProperty("user.name").getBytes(Charset.forName("UTF-8"));
															byte[] passwordBytes = password.getBytes(Charset.forName("UTF-8"));
															byte[] credentialsBuf = new byte[TapLockServer.S_CREDBUF];
															for (int i = 0, l = usernameBytes.length; (i < l) && (i < TapLockServer.S_USERBUF); i++)
																credentialsBuf[i] = usernameBytes[i];
															for (int i = 0, l = passwordBytes.length; (i < l) && (i < TapLockServer.S_PASSBUF); i++)
																credentialsBuf[i + TapLockServer.S_USERBUF] = passwordBytes[i];
															try {
																cpOutStream.write(credentialsBuf);
															} catch (IOException e) {
																TapLockServer.writeLog("cpOutStream write: " + e.getMessage());
															}
															cpReadBytes = -1;
															try {
																cpReadBytes = cpInStream.read(credentialsBuf);
															} catch (IOException e) {
																TapLockServer.writeLog("cpInStream read: " + e.getMessage());
															}
															// the socket should return "0" if no errors
															if (cpReadBytes != -1) {
																String cpResult = new String(credentialsBuf, 0, cpReadBytes);
																TapLockServer.writeLog("credential provider result: " + cpResult);
																if (!TapLockServer.CREDENTIAL_PROVIDER_SUCCESS.equals(cpResult))
																	responseJObj.put(TapLockServer.PARAM_ERROR, "Authentication error, is the Windows password set in Tap Lock Server?");
															}
															try {
																cpOutStream.close();
															} catch (IOException e) {
																TapLockServer.writeLog("output close: " + e.getMessage());
															}
															try {
																cpInStream.close();
															} catch (IOException e) {
																TapLockServer.writeLog("in close: " + e.getMessage());
															}
															try {
																cpSocket.close();
															} catch (IOException e) {
																TapLockServer.writeLog("socket close: " + e.getMessage());
															}
														}
													}
												} else
													runCommand("rundll32.exe user32.dll, LockWorkStation");
											}
										} else if (TapLockServer.OS == TapLockServer.OS_NIX) {
											if (TapLockServer.ACTION_TOGGLE.equals(requestAction))
												requestAction = TapLockServer.getToggleAction();
											String command = null;
											if (TapLockServer.ACTION_LOCK.equals(requestAction))
												command = "gnome-screensaver-command -a";
											else if (TapLockServer.ACTION_UNLOCK.equals(requestAction))
												command = "gnome-screensaver-command -d";
											if (command != null)
												runCommand(command);
										}
									}
								} else {
									TapLockServer.writeLog("authentication failed");
									responseJObj.put(TapLockServer.PARAM_ERROR, "authentication failed");
								}
							} else {
								TapLockServer.writeLog("invalid request");
								responseJObj.put(TapLockServer.PARAM_ERROR, "invalid request");
							}
						} else {
							TapLockServer.writeLog("failed to parse request");
							responseJObj.put(TapLockServer.PARAM_ERROR, "failed to parse request");
						}
						// send the new challenge
						challenge = Long.toString(System.currentTimeMillis());
						TapLockServer.writeLog("next challenge: " + challenge);
						responseJObj.put(TapLockServer.PARAM_CHALLENGE, challenge);
						responseStr = responseJObj.toJSONString();
						try {
							btOutStream.write(responseStr.getBytes());
						} catch (IOException e) {
							TapLockServer.writeLog("outStream.write: " + e.getMessage());
						}
						try {
							btReadBytes = btInStream.read(btBuffer);
						} catch (IOException e) {
							TapLockServer.writeLog("inStream.read: " + e.getMessage());
						}
					}
					if (btInStream != null) {
						try {
							btInStream.close();
						} catch (IOException e) {
							TapLockServer.writeLog("inStream.close: " + e.getMessage());
						}
					}
					if (btOutStream != null) {
						try {
							btOutStream.close();
						} catch (IOException e) {
							TapLockServer.writeLog("outStream.close: " + e.getMessage());
						}
					}
				}
				if (btConnection != null) {
					try {
						btConnection.close();
					} catch (IOException e) {
						TapLockServer.writeLog("connection.close: " + e.getMessage());
					}
					btConnection = null;
				}
			}
		}
	}

	private void runCommand(String command) {
		TapLockServer.writeLog("command: " + command);
		Process p = null;
		try {
			p = Runtime.getRuntime().exec(command);
		} catch (IOException e) {
			TapLockServer.writeLog("Runtime.getRuntime().exec: " + e.getMessage());
		}
		if (p != null) {
			BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream())); 
			String line = null;
			try {
				line = reader.readLine();
			} catch (IOException e) {
				TapLockServer.writeLog("reader.readLine: " + e.getMessage());
			} 
			while (line != null) { 
				TapLockServer.writeLog(line);
				try {
					line = reader.readLine();
				} catch (IOException e) {
					TapLockServer.writeLog("reader.readLine: " + e.getMessage());
				} 
			}
		}
	}

	public void shutdown() {
		if (notifier != null) {
			try {
				notifier.close();
			} catch (IOException e) {
				TapLockServer.writeLog("notifier.close: " + e.getMessage());
			}
			notifier = null;
		}
		if (btInStream != null) {
			try {
				btInStream.close();
			} catch (IOException e) {
				TapLockServer.writeLog("inStream.close: " + e.getMessage());
			}
		}
		if (btOutStream != null) {
			try {
				btOutStream.close();
			} catch (IOException e) {
				TapLockServer.writeLog("outStream.close: " + e.getMessage());
			}
		}
		if (btConnection != null) {
			try {
				btConnection.close();
			} catch (IOException e) {
				TapLockServer.writeLog("connection.close: " + e.getMessage());
			}
			btConnection = null;
		}
		if (local != null)
			local = null;
	}
}

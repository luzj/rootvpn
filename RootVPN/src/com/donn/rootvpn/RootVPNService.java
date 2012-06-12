package com.donn.rootvpn;

import java.util.StringTokenizer;

import com.donn.rootvpn.ShellCommand.CommandResult;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.widget.RemoteViews;

public class RootVPNService extends Service {

	private static final int NOTIFICATION_ID = 19801980;
	
	private ShellCommand cmd = new ShellCommand();
	private static String preVPNDNSServer;
	private static boolean isConnected = false;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);

		L.log(this, "Service started");

		RootVPNTask task = new RootVPNTask(this, intent);
		task.execute((Void[]) null);

		this.stopService(intent);

		return START_STICKY;
	}

	private class RootVPNTask extends AsyncTask<Void, Void, Void> {

		private Context context;
		private Intent intent;

		private String vpnServer;
		private String vpnPort;
		private String vpnUser;
		private String vpnPassword;
		private int vpnTimeout;

		public RootVPNTask(Context context, Intent intent) {
			this.context = context;
			this.intent = intent;
		}

		@Override
		protected Void doInBackground(Void... params) {

			L.log(this, "Started service thread");

			ComponentName thisWidget = new ComponentName(context, RootVPNWidget.class);
			AppWidgetManager manager = AppWidgetManager.getInstance(context);
			RemoteViews updateViews = new RemoteViews(context.getPackageName(), R.layout.widget_data);

			Intent defineIntent = null;

			if (intent != null) {
				String intentAction = intent.getAction();

				if (intentAction != null) {
					if (intent.getAction().equals(VPNRequestReceiver.ON_INTENT)) {

						L.log(this, "Got the : " + VPNRequestReceiver.ON_INTENT + " intent");
						
						try {
							initialActions();
						}
						catch (VPNException e) {
							L.err(this, "VPNException received, terminating RootVPNService");
							L.err(e.getSource(), e.getMessage());
							//If we couldn't perform the initial actions, terminate thread
							return null;
						}
						
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.wait);
						manager.updateAppWidget(thisWidget, updateViews);

						if (isConnected == false) {

							L.log(this, "VPN is not connected, connecting now");

							sendNotification("VPN Is Connecting...", R.drawable.disconnected_small);
							
							if (turnOnVPN()) {
								L.log(this, "VPN was turned on. Setting next action to OFF");
								sendBroadcast(new Intent(VPNRequestReceiver.CONNECTED_INTENT));
								defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
							}
							else {
								L.log(this, "VPN failed to turn on. Setting next action to ON");
								sendBroadcast(new Intent(VPNRequestReceiver.COULD_NOT_CONNECT_INTENT));
								updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
								defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
							}
						}
						else {
							L.log(this, "VPN is already connected. Setting next action to OFF");
							sendBroadcast(new Intent(VPNRequestReceiver.CONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
						}
					}
					else if (intent.getAction().equals(VPNRequestReceiver.OFF_INTENT)) {
						L.log(this, "Got the : " + VPNRequestReceiver.OFF_INTENT + " intent");
						
						try {
							initialActions();
						}
						catch (VPNException e) {
							L.err(this, "VPNException received, terminating RootVPNService");
							L.err(e.getSource(), e.getMessage());
							//If we couldn't perform the initial actions, terminate thread
							return null;
						}

						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.wait);
						manager.updateAppWidget(thisWidget, updateViews);

						if (turnOffVPN()) {
							L.log(this, "VPN was turned off. Setting next action to ON");
							sendBroadcast(new Intent(VPNRequestReceiver.DISCONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
						}
						else {
							L.log(this, "There was an error turning off VPN. Assumed disconnected. Setting next action to ON");
							updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
							sendBroadcast(new Intent(VPNRequestReceiver.DISCONNECTED_INTENT));
							defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
						}
					}
					else if (intent.getAction().equals(VPNRequestReceiver.CONNECTED_INTENT)) {
						L.log(this, "Got the : " + intent.getAction() + " intent. Setting connected=true");
						L.log(this, "Set next action to OFF");
						isConnected = true;
						sendNotification("VPN is connected", R.drawable.connected_small);
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.connected);
						defineIntent = new Intent(VPNRequestReceiver.OFF_INTENT);
					}
					else if (intent.getAction().equals(VPNRequestReceiver.DISCONNECTED_INTENT)) {
						L.log(this, "Got the : " + intent.getAction() + " intent. Setting connected=false");
						L.log(this, "Set next action to ON");
						isConnected = false;
						cancelNotification();
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
						defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
					}
					else if (intent.getAction().equals(VPNRequestReceiver.COULD_NOT_CONNECT_INTENT)) {
						L.log(this, "Got the : " + intent.getAction() + " intent. Setting connected=false");
						L.log(this, "Set next action to ON");
						isConnected = false;
						cancelNotification();
						updateViews.setImageViewResource(R.id.widgetImage, R.drawable.problem);
						defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
					}

				}
				else {
					L.log(this, "Intent action was null, setting next action to ON");
					updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
					defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
				}
			}
			else {
				L.log(this, "Intent itself was null, setting next action to ON");
				updateViews.setImageViewResource(R.id.widgetImage, R.drawable.disconnected);
				defineIntent = new Intent(VPNRequestReceiver.ON_INTENT);
			}

			defineIntent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
			PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, defineIntent, 0);
			updateViews.setOnClickPendingIntent(R.id.widget, pendingIntent);

			manager.updateAppWidget(thisWidget, updateViews);

			L.log(this, "Completed service thread");

			return null;
		}

		private void requestRoot() throws VPNException {
			L.log(this, "Requesting root...");

			CommandResult result = cmd.su.runWaitFor("date");

			if (result.success()) {
				L.log(this, "Requesting root successful!");
			}
			else {
				throw new VPNException(this, "Requesting root failed: " + result.stderr + " " + result.stdout);
			}
		}

		private boolean turnOnVPN() {
			L.log(this, "Turning on VPN");
			
			try {
				setPreVPNDNSServer();
				
				String currentNetworkInterface = getCurrentNetworkInterface();

				startMtpdService(currentNetworkInterface);
				waitForMtpdServiceStart();

				setupVPNRoutingTables();
				String pppDNSServer = getPPPDNSServer();
				setDNS(pppDNSServer);

				L.log(this, "VPN connection started: success, returning true.");
				return true;
			}
			catch (VPNException e) {
				L.err(this, "VPNException received, terminating RootVPNService");
				L.err(e.getSource(), e.getMessage());
				//If we fail to connect for whatever reason, try to clean up by turning VPN off
				turnOffVPN();
				return false;
			}
		}

		private boolean turnOffVPN() {
			L.log(this, "Turning off VPN");
			
			boolean killPppd = true;
			boolean resetDNS = true;
			boolean cleanupRoutes = true;
			boolean cleanupInterfaces = true;

			killPppd = killPppdService();
			resetDNS = resetDNS();
			cleanupRoutes = cleanupRoutes();
			cleanupInterfaces = cleanupInterfaces();
				
			if ( (killPppd || resetDNS || cleanupRoutes || cleanupInterfaces) == false ) {
				L.err(this, "Something failed when shutting down VPN");
				L.err(this, "Killed PPPD: " + killPppd);
				L.err(this, "Reset DNS: " + resetDNS);
				L.err(this, "Cleanup Routes: " + cleanupRoutes);
				L.err(this, "Cleanup interfaces: " + cleanupInterfaces);
				return false;
			}
			else {
				L.log(this, "VPN connection stopped: success, returning true.");
				return true;
			}
		}
		
		private void initialActions() throws VPNException {
			requestRoot();
			
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
			vpnServer = preferences.getString(getString(R.string.pref_vpnserver), "none");
			vpnPort = preferences.getString(getString(R.string.pref_vpnport), "1723");
			vpnUser = preferences.getString(getString(R.string.pref_username), "vpn");
			vpnPassword = preferences.getString(getString(R.string.pref_password), "password");
			vpnTimeout = Integer.parseInt(preferences.getString(getString(R.string.pref_timeout), "30"));
		}

		private String getCurrentNetworkInterface() throws VPNException {
			L.log(this, "Getting current network interface");

			String resultString = "";

			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'ip route | grep default'");

			if (result.success()) {

				resultString = result.stdout;
				StringTokenizer tokenizer = new StringTokenizer(resultString, " ");

				int tokens = 0;
				while (tokens < 5) {
					resultString = tokenizer.nextToken();
					tokens++;
				}
				L.log(this, "got current network interface: " + resultString);
			}
			else {
				throw new VPNException(this, "Failed to grep for current network interface " + result.stdout + " "
						+ result.stderr);
			}

			return resultString;
		}

		private void startMtpdService(String currentNetworkInterface) {
			L.log(this, "Starting mtpd...");

			cmd.su.run("mtpd " + currentNetworkInterface + " pptp " + vpnServer + " " + vpnPort + " name " + vpnUser
					+ " password " + vpnPassword + " linkname vpn " + "refuse-eap nodefaultroute usepeerdns idle "
					+ "1800 mtu 1400 mru 1400 +mppe");

			L.log(this, "mtpd was started...");
		}

		private void waitForMtpdServiceStart() throws VPNException {
			L.log(this, "Waiting for mtpd initialization...");

			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp0'");
			String resultString = result.stdout;
			
			int count = 0;
			for (; count < vpnTimeout && (resultString == null || !resultString.contains("UP")); count++) {
				// wait until mtpd is running
				sleep(1);
				L.log(this, "Still waiting for mtpd to initialize...");
				result = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp0'");
				resultString = result.stdout;
			}
			if (result.success() && count < vpnTimeout) {
				L.log(this, "mtpd initialized successfully after " + count + " seconds");
			}
			else {
				throw new VPNException(this, "mtpd failed to initialize after " + count + " seconds " + result.stderr
						+ " " + result.stdout);
			}
		}

		private void setupVPNRoutingTables() throws VPNException {
			L.log(this, "Setting up VPN routing tables");

			CommandResult resultOne = cmd.su.runWaitFor("ip route add 0.0.0.0/1 dev ppp0");
			CommandResult resultTwo = cmd.su.runWaitFor("ip route add 128.0.0.0/1 dev ppp0");

			if (resultOne.success() && resultTwo.success()) {
				L.log(this, "Successfully setup VPN routing tables");
			}
			else {
				throw new VPNException(this, "VPN Routing table setup fail: " + resultOne.stderr + " "
						+ resultOne.stdout + " " + resultTwo.stderr + " " + resultTwo.stdout);
			}
		}

		private void setPreVPNDNSServer() throws VPNException {
			L.log(this, "Getting DNS server system was using before VPN");

			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'getprop net.dns1'");
			String resultString = result.stdout;
			if (result.success() && resultString != null) {
				L.log(this, "Got pre-VPN DNS server from ip route: " + resultString);

				StringTokenizer tokenizer = new StringTokenizer(resultString, " ");
				String dnsServer = tokenizer.nextToken();

				L.log(this, "Parsed pre-VPN DNS server from ip route: " + dnsServer);

				preVPNDNSServer = dnsServer;
			}
			else {
				throw new VPNException(this, "Unable to get pre-VPN DNS server from ip route: " + result.stderr + " "
						+ result.stdout);
			}
		}

		private String getPPPDNSServer() throws VPNException {
			L.log(this, "Getting DNS server to use for VPN server's IP");

			String dnsServer = "";
			CommandResult result = cmd.su.runWaitFor("/system/bin/sh -c 'ip route | grep \"dev ppp0  proto kernel\"'");
			String resultString = result.stdout;

			if (result.success()) {
				L.log(this, "Got DNS server from ip route: " + resultString);

				StringTokenizer tokenizer = new StringTokenizer(resultString, " ");
				dnsServer = tokenizer.nextToken();

				L.log(this, "Parsed DNS server from ip route: " + dnsServer);
			}
			else {
				throw new VPNException(this, "Unable to get DNS server from ip route: " + result.stderr + " "
						+ result.stdout);
			}

			return dnsServer;
		}

		private boolean setDNS(String dnsValue) {
			L.log(this, "Setting DNS server to: " + dnsValue);
			
			if (dnsValue != null) {

				CommandResult result = cmd.su.runWaitFor("setprop net.dns1 " + dnsValue);
		
				if (result.success()) {
					L.log(this, "Successfully set DNS server from ip route: " + dnsValue);
					return incrementDNSChangeValue();
				}
				else {
					L.err(this, "Unable to set DNS server in properties: " + result.stderr + " "
							+ result.stdout);
					return false;
				}
			}
			else {
				L.err(this, "DNS value was null, could not set back to original value.");
				return false;
			}
		}

		private boolean incrementDNSChangeValue() {
			L.log(this, "Getting DNS increment value");
			CommandResult resultOne = cmd.su.runWaitFor("getprop net.dnschange");
			String resultOneString = resultOne.stdout;

			if (resultOne.success()) {
				L.log(this, "Success in get dnschange value: " + resultOne.stdout);

				Integer intValue = Integer.parseInt(resultOneString);
				int dnsChange = intValue.intValue();
				dnsChange++;

				L.log(this, "Setting DNS increment value to: " + dnsChange);
				CommandResult resultTwo = cmd.su.runWaitFor("setprop net.dnschange " + dnsChange);

				if (resultTwo.success()) {
					L.log(this, "Success in set dnschange value: " + dnsChange);
					return true;
				}
				else {
					L.err(this, "Unable to set dnschange in properties: " + resultTwo.stderr + " "
							+ resultTwo.stdout);
					return false;
				}
			}
			else {
				L.err(this, "Unable to get dnschange in properties: " + resultOne.stderr + " "
						+ resultOne.stdout);
				return false;
			}
		}
		
		private boolean killPppdService() {
			L.log(this, "Killing pppd service");
			
			L.log(this, "Killing pppd if present");
			CommandResult resultTwo = cmd.su.runWaitFor("kill -15 $(pidof pppd)");
			L.log(this, "Killed pppd result: " + resultTwo.stdout + " " + resultTwo.stderr);
					
			if (resultTwo.success()) {
				L.log(this, "VPN connection terminated: success, returning true.");
				return true;
			}
			else {
				L.log(this, "No process found to kill for pppd.");
				return false;
			}
		}

		private boolean resetDNS() {
			return setDNS(preVPNDNSServer);
		}
		
		private boolean cleanupRoutes() {
			L.log(this, "Cleaning up VPN routing tables if necessary");

			CommandResult resultGrepRoute = cmd.su.runWaitFor("/system/bin/sh -c 'ip route | grep ppp0'");
			
			if (resultGrepRoute.success()) {
				String resultString = resultGrepRoute.stdout;
				if (resultString != null) {
					StringTokenizer resultLines = new StringTokenizer(resultString, "\n");
					if (resultLines.hasMoreTokens()) {
						String pppRoute = resultLines.nextToken();
						pppRoute = pppRoute.substring(0, pppRoute.indexOf(' '));
						L.log(this, "Removing Route for: " + pppRoute);
						CommandResult result = cmd.su.runWaitFor("ip route del " + pppRoute + " dev ppp0");
						if (result.success()) {
							L.log(this, "Successfully removed route for: " + pppRoute);
						}
						else {
							L.err(this,  "Could not remove route for: " + pppRoute);
							return false;
						}
					}
					else {
						L.log(this, "Found no lines in grep route.");
					}
				}
				else {
					L.log(this, "Grep route string was null");
				}
			}
			else {
				L.log(this, "Found no existing routes that need to be deleted.");
			}
			
			return true;
		}
		
		private boolean cleanupInterfaces() {
			L.log(this, "Cleaning up VPN interface if necessary");
			
			CommandResult resultGrepInterface = cmd.su.runWaitFor("/system/bin/sh -c 'netcfg | grep ppp'");
			
			if (resultGrepInterface.success()) {
				String resultString = resultGrepInterface.stdout;
				if (resultString != null) {
					StringTokenizer resultLines = new StringTokenizer(resultString, "\n");
					if (resultLines.hasMoreTokens()) {
						String pppInterface = resultLines.nextToken();
						pppInterface = pppInterface.substring(0, pppInterface.indexOf(' '));
						L.log(this, "Removing Interface for: " + pppInterface);
						CommandResult result = cmd.su.runWaitFor("ifconfig " + pppInterface + " unplumb");
						if (result.success()) {
							L.log(this, "Successfully removed interface for: " + pppInterface);
						}
						else {
							L.err(this,  "Could not remove interface for: " + pppInterface);
							return false;
						}
					}
					else {
						L.log(this, "Found no lines in grep interface.");
					}
				}
				else {
					L.log(this, "Grep interface string was null");
				}
			}
			else {
				L.log(this, "Found no existing interfaces that need to be deleted.");
			}
			
			return true;
		}

		private void sleep(int seconds) {
			int millis = seconds * 1000;

			try {
				Thread.sleep(millis);
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		private void sendNotification(String notificationString, int imageID) {
			NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			
			Context context = getApplicationContext();
			CharSequence contentTitle = "RootVPN Notification - ";
			
			Notification.Builder nb = new Notification.Builder(context);
			nb.setSmallIcon(imageID);
			nb.setTicker(notificationString); 
			nb.setAutoCancel(true);
			nb.setContentTitle(contentTitle);
			nb.setContentText(notificationString);

			Notification notification = nb.getNotification();

			notificationManager.notify(NOTIFICATION_ID, notification);
		}
		
		private void cancelNotification() {
			NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			notificationManager.cancel(NOTIFICATION_ID);
		}
	}

}

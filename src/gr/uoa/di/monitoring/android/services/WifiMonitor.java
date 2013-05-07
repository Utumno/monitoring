package gr.uoa.di.monitoring.android.services;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.util.Log;

import com.commonsware.cwac.wakeful.WakefulIntentService;

import gr.uoa.di.monitoring.android.AccessPreferences;
import gr.uoa.di.monitoring.android.receivers.BaseReceiver;
import gr.uoa.di.monitoring.android.receivers.ScanResultsReceiver;

import java.util.List;

import static gr.uoa.di.monitoring.android.C.APP_PACKAGE_NAME;
import static gr.uoa.di.monitoring.android.C.DEBUG;
import static gr.uoa.di.monitoring.android.C.DISABLE;
import static gr.uoa.di.monitoring.android.C.ENABLE;
import static gr.uoa.di.monitoring.android.C.ac_scan_results_available;
import static gr.uoa.di.monitoring.android.C.ac_scan_wifi_disabled;
import static gr.uoa.di.monitoring.android.C.ac_scan_wifi_enabled;

public final class WifiMonitor extends Monitor {

	// TODO : timeout wait()
	private static final long WIFI_MONITORING_INTERVAL = 1 * 60 * 1000;
	// Internal preferences keys - persist state even on unloading app classes
	private static final String HAVE_ENABLED_WIFI_PREF_KEY = APP_PACKAGE_NAME
			+ ".HAVE_ENABLED_WIFI_PREF_KEY";
	private static final String HAVE_INITIATED_WIFI_ENABLE_PREF_KEY = APP_PACKAGE_NAME
			+ ".HAVE_INITIATED_WIFI_ENABLE_PREF_KEY";
	// locking
	/**
	 * The tag for the wifi lock
	 */
	private static final String SCAN_LOCK = APP_PACKAGE_NAME
			+ ".SCAN_WIFI_LOCK";
	private static WifiLock wifiLock;
	private static volatile boolean done = false;
	// convenience fields
	private static WifiManager wm;

	public WifiMonitor() {
		// needed for service instantiation by Android
		super(WifiMonitor.class.getSimpleName());
	}

	@Override
	protected void doWakefulWork(Intent in) {
		StringBuilder sb = monitorInfoHeader();
		final CharSequence action = in.getAction();
		try {
			initServices(this);
			if (action == null) {
				// monitor command from the alarm manager
				cleanUp(); // FIXME !
				d("Check if wireless is enabled");
				if (wm.isWifiEnabled()) {
					// wm.getScanResults(); would always return the last
					// available scan results ??? If yes,
					// FIXME : maybe I should scan ANYWAY ?
					warnScanResults(sb);
				} else {
					d("Enabling the receiver");
					BaseReceiver
							.enable(this, ENABLE, ScanResultsReceiver.class);
					// enable wifi AFTER enabling the receiver
					d("Wifi is not enabled - enabling...");
					final boolean enabled = wm.setWifiEnabled(true);
					if (enabled) {
						setInitiatedWifiEnabling(this, true);
						d("Note to self I should disable again");
						keepNoteToDisableWireless(true);
						// wifi lock AND WAKE LOCK (Gatekeeper)-so must be here!
						getWirelessLock();
					} else {
						// TODO: maybe shut down Monitoring ?
						w("Unable to enable wireless - maybe shut down Monitoring ?");
						BaseReceiver.enable(this, DISABLE,
								ScanResultsReceiver.class);
					}
				} // action == null
			} else if (ac_scan_wifi_enabled.equals(action)) {
				final boolean startScan = wm.startScan();
				d("Start scan : " + startScan);
				if (!startScan) done = true;
			} else if (ac_scan_results_available.equals(action)) {
				// got my results - got to release the lock BY ALL MEANS
				done = true;
				d("Get the scan results - scan completed !");
			} else if (ac_scan_wifi_disabled.equals(action)) {
				// wifi disabled before I got my results - got to release the
				// lock BY ALL MEANS
				done = true;
				d("Get the scan results (null?) - wireless disabled !");
			}
		} catch (WmNotAvailableException e) {
			w(e.getMessage(), e);
			// TODO: maybe shut down Monitoring ?
		} finally {
			synchronized (Gatekeeper.WIFI_MONITOR) {
				if (done) {
					warnScanResults(sb);
					disableWifiIfIhadItEnableMyself();
					d("Releasing wake lock for the scan");
					releaseWifiLock();
					done = false;
				}
			}
			w("Finishing " + action);
		}
	}

	/**
	 * Delegates to Gatekeeper.doWakefulWork(). This way I acquire both a wifi
	 * and a Wake lock - and the Gatekeeper waits on WIFI_MONITOR lock till scan
	 * results are available
	 */
	private void getWirelessLock() {
		d("Acquiring wireless lock for the scan");
		WakefulIntentService.sendWakefulWork(this, Gatekeeper.class);
	}

	private void cleanUp() {
		d("Check for leftovers from previous runs");
		if (haveEnabledWifi()) {
			w("Oops - I have enabled wifi and never disabled - receiver not run");
			w("but it is still enabled so disable it first.");
			BaseReceiver.enable(this, DISABLE, ScanResultsReceiver.class);
			w("Clean up lock");
			synchronized (Gatekeeper.WIFI_MONITOR) {
				Gatekeeper.WIFI_MONITOR.notify();
			}
			releaseWifiLock();
			disableWifiIfIhadItEnableMyself();
			resetPrefs();
		}
		Gatekeeper.release = false;
	}

	private void releaseWifiLock() {
		synchronized (Gatekeeper.WIFI_MONITOR) {
			if (!getWifiLock().isHeld()) {
				w("Lock is not held");
				return;
			}
			Gatekeeper.release = true;
			Gatekeeper.WIFI_MONITOR.notify();
			while (getWifiLock().isHeld() && Gatekeeper.release) {
				try {
					w("about to wait");
					Gatekeeper.WIFI_MONITOR.wait();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	private void disableWifiIfIhadItEnableMyself() {
		d("Check if I have enabled the wifi myself");
		if (haveEnabledWifi()) {
			d("Disabling wifi");
			// FIXME : what if the user has enabled it meanwhile ???
			final boolean disabled = wm.setWifiEnabled(false);
			if (!disabled) {
				w("Failed to disable wireless");
			} else {
				resetPrefs();
			}
		}
	}

	/**
	 * Resets internal preferences to default
	 */
	private void resetPrefs() {
		setInitiatedWifiEnabling(this, false); // TODO :should be set in
		// receiver ?
		keepNoteToDisableWireless(false);
	}

	public static class Gatekeeper extends WakefulIntentService {

		private static volatile boolean release = false;
		private static final String TAG = Gatekeeper.class.getSimpleName();
		public static final Object WIFI_MONITOR = new Object();

		public Gatekeeper() {
			super(Gatekeeper.class.getSimpleName());
		}

		/**
		 * This implementation actually acquires the wifi lock (if not held) and
		 * then waits on WifiMonitor.WIFI_MONITOR till the scan results are
		 * available whereupon it is notified by WifiMonitor and releases the
		 * lock. FIXME : DEBUG DEBUG DEBUG deadlocks
		 */
		@Override
		protected void doWakefulWork(Intent intent) {
			synchronized (WIFI_MONITOR) {
				if (DEBUG) Log.d(TAG, "Got in sync block !");
				if (!release && !getWifiLock().isHeld()) {
					if (DEBUG)
						Log.d(TAG, "Actually acquiring wake lock for the scan");
					getWifiLock().acquire();
				}
				// while release is false wait on the monitor - holding the
				// Wakeful CPU wake lock and the wifi lock
				while (!release) {
					// FIXME timeout !
					try {
						WIFI_MONITOR.wait();
					} catch (InterruptedException e) {
						// TODO Handle
						e.printStackTrace();
					}
					if (DEBUG) Log.d(TAG, "Out of wait !");
				}
				if (DEBUG) Log.d(TAG, "Out of while !");
				if (getWifiLock().isHeld()) {
					if (DEBUG)
						Log.d(TAG, "Actually releasing wake lock for the scan");
					getWifiLock().release();
				}
				Gatekeeper.release = false;
				WIFI_MONITOR.notify();
			}
		}
	}

	private boolean haveEnabledWifi() {
		return retrieve(HAVE_ENABLED_WIFI_PREF_KEY, false);
	}

	private void keepNoteToDisableWireless(boolean disableAfterwards) {
		persist(HAVE_ENABLED_WIFI_PREF_KEY, disableAfterwards);
	}

	public static void keepNoteToDisableWireless(Context ctx,
			boolean disableAfterwards) {
		AccessPreferences.persist(ctx, HAVE_ENABLED_WIFI_PREF_KEY,
				disableAfterwards);
	}

	public static boolean didInitiateWifiEnabling(Context ctx) {
		// TODO : default == true ?
		return AccessPreferences.retrieve(ctx,
				HAVE_INITIATED_WIFI_ENABLE_PREF_KEY, true);
	}

	public static void setInitiatedWifiEnabling(Context ctx,
			boolean initiatedWifiEnable) {
		AccessPreferences.persist(ctx, HAVE_INITIATED_WIFI_ENABLE_PREF_KEY,
				initiatedWifiEnable);
	}

	private void warnScanResults(StringBuilder sb) {
		List<ScanResult> scanRes = wm.getScanResults();
		if (scanRes == null) {
			// will be null if wireless is disabled
			// TODO : only then ???
			w("Scan results == null - wireless enabled : " + wm.isWifiEnabled());
		} else {
			if (scanRes.isEmpty()) {
				w("No scan results available");
				// TODO : do I have to report it ?
			} else {
				for (ScanResult scanResult : scanRes) {
					sb.append(scanResult.SSID + DELIMITER);
					// sb.append(scanResult.BSSID + DELIMITER);
					// sb.append(scanResult.level + DELIMITER);
				}
				w(sb.toString());
			}
		}
	}

	private static WifiLock getWifiLock() {
		if (wifiLock == null) {
			// FIXME - ensure wm is not null
			wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_SCAN_ONLY,
					SCAN_LOCK);
		}
		return wifiLock;
	}

	@Override
	public long getInterval() {
		return WIFI_MONITORING_INTERVAL;
	}

	@SuppressWarnings("unused")
	private static String getCurrentSsid(Context context) {
		String ssid = null;
		ConnectivityManager connManager = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = connManager
				.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		if (networkInfo.isConnected()) {
			final WifiInfo connectionInfo = wm.getConnectionInfo();
			if (connectionInfo != null) {
				ssid = connectionInfo.getSSID();
				if (ssid != null && "".equals(ssid.trim())) ssid = null;
			}
		}
		return ssid;
	}

	private void initServices(Context context) throws WmNotAvailableException {
		// FIXME : null ?
		wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
		if (wm == null) throw new WmNotAvailableException();
	}
}
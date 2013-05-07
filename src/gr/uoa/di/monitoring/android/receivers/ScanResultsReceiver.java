package gr.uoa.di.monitoring.android.receivers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;

import com.commonsware.cwac.wakeful.WakefulIntentService;

import gr.uoa.di.monitoring.android.C;
import gr.uoa.di.monitoring.android.services.WifiMonitor;

/**
 * BroadcastReceiver registered to receive wifi scan events. If this is enabled
 * it can only mean that the user had disabled the wireless and I enabled it
 * temporarily to scan for networks. So I disable myself and broadcast to the
 * WiffiMotoringReceiver so it can collect the scan results. I leave wireless
 * open since I am not sure I can have the results if not. Still TODO : what if
 * the user has enabled the wireless again meanwhile ?
 *
 * @author MrD
 */
public final class ScanResultsReceiver extends BaseReceiver {

	private boolean disabled = false;

	@Override
	public void onReceive(Context context, Intent intent) {
		d(intent.toString());
		if (disabled) {
			d("Well I should have been disabled !");
			return;
		}
		final String action = intent.getAction(); // NPE ?
		d("Intent flags : " + intent.getFlags());
		// 268435456 ie FLAG_RECEIVER_FOREGROUND for WIFI_STATE_CHANGED_ACTION
		if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
			final int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
					WifiManager.WIFI_STATE_UNKNOWN);
			switch (state) {
			case WifiManager.WIFI_STATE_ENABLING:
				d("Enabling");
				if (WifiMonitor.didInitiateWifiEnabling(context)) {
					d("Wifi monitor did initiate enabling wifi - subsequent calls will be from user");
					WifiMonitor.setInitiatedWifiEnabling(context, false);
				} else {
					d("Aparently the user did initiate enabling wifi (?????) - don't disable it !");
					WifiMonitor.keepNoteToDisableWireless(context, false);
				}
				break;
			case WifiManager.WIFI_STATE_ENABLED:
				Intent i2 = new Intent(C.ac_scan_wifi_enabled.toString(),
						Uri.EMPTY, context, WifiMonitor.class);
				WakefulIntentService.sendWakefulWork(context, i2);
				break;
			case WifiManager.WIFI_STATE_DISABLING:
				d("Disabling");
			case WifiManager.WIFI_STATE_DISABLED:
				d("Wifi failed (for instance E/WifiService(173): Failed to load Wi-Fi driver) - disabling myself");
				disabled = true;
				// TODO : will I have time to disable myself before some other
				// intent is received (like disabling network or whatever ?)
				BaseReceiver.enable(context, C.DISABLE, this.getClass());
				Intent i = new Intent(C.ac_scan_wifi_disabled.toString(),
						Uri.EMPTY, context, WifiMonitor.class);
				WakefulIntentService.sendWakefulWork(context, i);
				break;
			default:
				break;
			}
		} else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
			d("Disabling myself");
			// TODO : will I have time to disable myself before some other
			// intent is received (like disabling network or whatever ?)
			disabled = true;
			BaseReceiver.enable(context, C.DISABLE, this.getClass());
			d("Directly invoke wifi monitor");
			// TODO : uh oh, modularity
			Intent i = new Intent(C.ac_scan_results_available.toString(),
					Uri.EMPTY, context, WifiMonitor.class);
			WakefulIntentService.sendWakefulWork(context, i);
		} else {
			w("Received bogus intent :\n" + intent + "\nAction : " + action);
		}
	}
}
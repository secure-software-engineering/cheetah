package de.mo;

import android.app.Activity;
import android.os.Bundle;
import android.telephony.SmsManager;

public class OtherActivity extends Activity {
	public static String secret = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_button2);

		SmsManager sm = SmsManager.getDefault();
		sm.sendTextMessage("+49 1234", null, secret, null, null);

	}
}

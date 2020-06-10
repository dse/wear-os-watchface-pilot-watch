package com.webonastick.watchface.pilotwatch;

import android.content.pm.PackageInfo;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

public class MainEmptyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_empty);
        TextView textView = (TextView) findViewById(R.id.versionNumberTextView);
        try {
            PackageInfo pInfo = getApplicationContext().getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionName = pInfo.versionName;
            int versionCode = pInfo.versionCode;
            textView.setText(versionName + " (" + versionCode + ")");
        } catch (Exception e) {
            textView.setText("???.???");
        }
    }
}

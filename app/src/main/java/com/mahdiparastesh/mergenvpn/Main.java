package com.mahdiparastesh.mergenvpn;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

import com.mahdiparastesh.mergenvpn.databinding.MainBinding;

// adb connect 192.168.1.20:

public class Main extends AppCompatActivity {
    MainBinding b;

    ActivityResultLauncher<Intent> mStartForResult = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> { // result.getData()
                if (result.getResultCode() == RESULT_OK) start();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = MainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.connect.setOnClickListener(v -> {
            Intent intent = VpnService.prepare(Main.this);
            if (intent != null) mStartForResult.launch(intent);
            else start();
        });
        b.disconnect.setOnClickListener(v ->
                startService(getServiceIntent().setAction(Medium.ACTION_DISCONNECT)));
    }

    private Intent getServiceIntent() {
        return new Intent(this, Medium.class);
    }

    private void start() {
        startService(getServiceIntent().setAction(Medium.ACTION_CONNECT));
    }
}

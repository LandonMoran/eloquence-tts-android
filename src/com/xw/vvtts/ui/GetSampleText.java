package com.xw.vvtts.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class GetSampleText extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent();
        String lang = getIntent() != null ? getIntent().getStringExtra("language") : null;
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            result.putExtra("sampleText", "\u4f60\u597d\uff0c\u8fd9\u662fEloquence\u8bed\u97f3\u5408\u6210\u6d4b\u8bd5\u3002");
        } else {
            result.putExtra("sampleText", "Hello, this is an Eloquence speech synthesis test.");
        }
        setResult(RESULT_OK, result);
        finish();
    }
}

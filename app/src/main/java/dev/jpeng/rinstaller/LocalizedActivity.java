package dev.jpeng.rinstaller;

import android.app.Activity;
import android.content.Context;

abstract class LocalizedActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppAppearance.wrap(AppLanguage.wrap(newBase)));
    }
}

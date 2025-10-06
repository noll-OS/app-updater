package app.seamlessupdate.client;

import android.app.Application;

public class UpdaterApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHandler.createNotificationChannels(this);
    }
}

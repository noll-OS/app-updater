package app.seamlessupdate.client;

import android.net.Network;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference;
import androidx.preference.ListPreference;
import androidx.preference.SwitchPreference;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import static java.util.Objects.requireNonNull;

public class Settings extends CollapsingToolbarBaseActivity {
    private static final String KEY_CHANNEL = "channel";
    private static final String KEY_USE_SECURITY_PREVIEW_CHANNEL = "use_security_preview_channel";
    private static final String KEY_NETWORK_TYPE = "network_type";
    private static final String KEY_BATTERY_NOT_LOW = "battery_not_low";
    private static final String KEY_REQUIRES_CHARGING = "requires_charging";
    private static final String KEY_IDLE_REBOOT = "idle_reboot";
    private static final String KEY_CHECK_FOR_UPDATES = "check_for_updates";
    static final String KEY_WAITING_FOR_REBOOT = "waiting_for_reboot";

    static SharedPreferences getPreferences(final Context context) {
        final Context deviceContext = context.createDeviceProtectedStorageContext();
        return PreferenceManager.getDefaultSharedPreferences(deviceContext);
    }

    static String getChannel(final Context context) {
        String base = getPreferences(context).getString(KEY_CHANNEL,
                context.getString(R.string.channel_default));
        if (shouldUseSecurityPreviewChannel(context)) {
            return base + "-security-preview";
        } else {
            return base;
        }
    }

    static boolean shouldUseSecurityPreviewChannel(final Context context) {
        int val = getPreferences(context).getInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, -1);
        switch (val) {
            case 0:
                return false;
            case 1:
                return true;
            default:
                return false;
        }
    }

    static int getNetworkType(final Context context) {
        return getPreferences(context).getInt(KEY_NETWORK_TYPE,
                Integer.parseInt(context.getString(R.string.network_type_default)));
    }

    static boolean getBatteryNotLow(final Context context) {
        return getPreferences(context).getBoolean(KEY_BATTERY_NOT_LOW,
                Boolean.parseBoolean(context.getString(R.string.battery_not_low_default)));
    }

    static boolean getRequiresCharging(final Context context) {
        return getPreferences(context).getBoolean(KEY_REQUIRES_CHARGING,
                Boolean.parseBoolean(context.getString(R.string.requires_charging_default)));
    }

    static boolean getIdleReboot(final Context context) {
        return getPreferences(context).getBoolean(KEY_IDLE_REBOOT,
                Boolean.parseBoolean(context.getString(R.string.idle_reboot_default)));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserManager userManager = (UserManager) getSystemService(USER_SERVICE);
        if (!userManager.isSystemUser()) {
            throw new SecurityException("system user only");
        }

        setContentView(R.layout.settings_activity);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Closes the activity when the up action is clicked
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static String TAG = "SettingsFragment";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setStorageDeviceProtected();
            setPreferencesFromResource(R.xml.settings, rootKey);

            requirePreference(KEY_CHECK_FOR_UPDATES).setOnPreferenceClickListener(pref -> {
                final Context context = requireContext();
                if (!getPreferences(context).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    final ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
                    final Network network = connectivityManager.getActiveNetwork();
                    if (network == null) {
                        Log.w(TAG, "checkForUpdates.onClickListener – network will be unavailable");
                    }
                    final var intent = new Intent(context, Service.class);
                    intent.putExtra(Service.INTENT_EXTRA_IS_USER_INITIATED, true);
                    intent.putExtra(Service.INTENT_EXTRA_NETWORK, network);
                    context.startForegroundService(intent);
                }
                return true;
            });

            requirePreference(KEY_NETWORK_TYPE).setOnPreferenceChangeListener((pref, newValue) -> {
                final int value = Integer.parseInt((String) newValue);
                getPreferences(requireContext()).edit().putInt(KEY_NETWORK_TYPE, value).apply();
                if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    PeriodicJob.schedule(requireContext());
                }
                return true;
            });

            final SwitchPreference useSecurityPreviewChannel =
                    requirePreference(KEY_USE_SECURITY_PREVIEW_CHANNEL);
            useSecurityPreviewChannel.setChecked(shouldUseSecurityPreviewChannel(requireContext()));
            useSecurityPreviewChannel.setOnPreferenceChangeListener((pref, newValue) -> {
                // This preference is intentionally marked as persistent=false in XML to avoid
                // automatic clobbering of the default value. Handle persistence manually.
                Context context = requireContext();
                SharedPreferences prefs = getPreferences(context);
                boolean res = prefs.edit()
                        .putInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, ((boolean) newValue) ? 1 : 0)
                        .commit();
                if (res) {
                    if (!prefs.getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext());
                    }
                }
                return res;
            });
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            switch (key) {
                case KEY_CHANNEL:
                case KEY_BATTERY_NOT_LOW:
                case KEY_REQUIRES_CHARGING:
                    if (!getPreferences(requireContext()).getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                        PeriodicJob.schedule(requireContext());
                    }
                    break;
                case KEY_IDLE_REBOOT:
                    if (!getIdleReboot(requireContext())) {
                        IdleReboot.cancel(requireContext());
                    }
                    break;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
            final ListPreference networkType = (ListPreference) findPreference(KEY_NETWORK_TYPE);
            networkType.setValue(Integer.toString(getNetworkType(requireContext())));
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        private <T extends Preference> T requirePreference(String key) {
            return requireNonNull(findPreference(key));
        }
    }
}

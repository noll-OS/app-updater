package app.seamlessupdate.client;

import static java.util.Objects.requireNonNull;

import static app.seamlessupdate.client.Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL;
import static app.seamlessupdate.client.Settings.KEY_WAITING_FOR_REBOOT;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupcompat.util.WizardManagerHelper;
import com.google.android.setupdesign.GlifPreferenceLayout;
import com.google.android.setupdesign.transition.TransitionHelper;
import com.google.android.setupdesign.util.ThemeHelper;

public class SecurityPreviewSettings extends FragmentActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // This will ensure GlifPreferenceLayout is used for the preference fragment layout
        // Putting this after super.onCreate will result in a crash whenever configuration changes
        // e.g. portrait is changed to landscape
        setTheme(R.style.GlifV4Theme_DayNight);
        ThemeHelper.trySetDynamicColor(this);

        super.onCreate(savedInstanceState);

        final boolean isAnySetupWizard = WizardManagerHelper.isAnySetupWizard(getIntent());
        if (isAnySetupWizard) {
            TransitionHelper.applyForwardTransition(this);
            TransitionHelper.applyBackwardTransition(this);
        }

        UserManager userManager = (UserManager) getSystemService(USER_SERVICE);
        if (!userManager.isSystemUser()) {
            throw new SecurityException("system user only");
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        theme.applyStyle(R.style.SetupWizardPartnerResource, true);
        super.onApplyThemeResource(theme, resid, first);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String TAG = "SecPreviewSettingsFrag";
        // set the preference to default to true, but not persisted immediately
        private static final boolean DEFAULT_SECURITY_PREVIEW_WHEN_UNSET = true;

        private boolean isUsingPreviewChannel;

        private static boolean shouldUseSecurityPreviewChannel(final Context context) {
            int val = Settings.getPreferences(context).getInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, -1);
            return switch (val) {
                case 0 -> false;
                case 1 -> true;
                default -> DEFAULT_SECURITY_PREVIEW_WHEN_UNSET;
            };
        }

        @NonNull
        @Override
        public RecyclerView onCreateRecyclerView(@NonNull LayoutInflater inflater,
                @NonNull ViewGroup parent, @Nullable Bundle savedInstanceState) {
            GlifPreferenceLayout layout = (GlifPreferenceLayout) parent;
            return layout.onCreateRecyclerView(inflater, parent, savedInstanceState);
        }

        @NonNull
        @Override
        protected RecyclerView.Adapter<?> onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
            return new RV(preferenceScreen);
        }

        // Fix padding issue between the Glif header and the individual;
        // could subclass com.android.settingslib.widget.SettingsPreferenceGroupAdapter for
        // expressive in 16qpr1? but padding still looked weird
        static class RV extends PreferenceGroupAdapter {
            public RV(@NonNull PreferenceGroup preferenceGroup) {
                super(preferenceGroup);
            }

            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                View view = holder.itemView;
                Context context = view.getContext();
                try (TypedArray a = context.obtainStyledAttributes(
                        new int[]{R.attr.sudMarginStart, R.attr.sudMarginEnd}
                )) {
                    int layoutStart = a.getDimensionPixelSize(0, view.getPaddingStart());
                    int layoutEnd = a.getDimensionPixelSize(1, view.getPaddingEnd());
                    view.setPaddingRelative(
                            layoutStart, view.getPaddingTop(), layoutEnd, view.getPaddingBottom());
                }
            }
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            final Activity activity = requireActivity();

            GlifPreferenceLayout layout = (GlifPreferenceLayout) view;

            layout.setIcon(activity.getDrawable(R.drawable.ic_updater_glif));
            layout.setHeaderText(R.string.security_preview_settings_title);
            layout.setDescriptionText(R.string.security_preview_settings_description);

            FooterBarMixin footer = layout.getMixin(FooterBarMixin.class);
            final boolean isAnySetupWizard = WizardManagerHelper.isAnySetupWizard(activity.getIntent());
            final int buttonType = isAnySetupWizard ? FooterButton.ButtonType.NEXT : FooterButton.ButtonType.DONE;
            final int buttonTextRes = isAnySetupWizard ? R.string.next : R.string.save;
            FooterButton primary = new FooterButton.Builder(activity)
                    .setText(getString(buttonTextRes))
                    .setButtonType(buttonType)
                    .setListener(v -> onPrimaryAction())
                    .build();
            footer.setPrimaryButton(primary);
        }

        private void onPrimaryAction() {
            Activity activity = requireActivity();

            NotificationHandler.cancelSetSecurityPreviewNotification(activity);
            SharedPreferences prefs = Settings.getPreferences(activity);
            boolean res = prefs.edit()
                    .putInt(KEY_USE_SECURITY_PREVIEW_CHANNEL, isUsingPreviewChannel ? 1 : 0)
                    .commit();
            if (res) {
                if (!prefs.getBoolean(KEY_WAITING_FOR_REBOOT, false)) {
                    PeriodicJob.schedule(requireContext());
                }
            } else {
                // Don't trap the user in SetupWizard if a rare error occurs
                Toast.makeText(activity, R.string.security_preview_settings_error_saving, Toast.LENGTH_LONG).show();
                Log.e(TAG, "error saving " + KEY_USE_SECURITY_PREVIEW_CHANNEL + ", pref editor commit returned false");
            }

            activity.setResult(Activity.RESULT_OK);
            activity.finish();
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            getPreferenceManager().setStorageDeviceProtected();
            setPreferencesFromResource(R.xml.security_preview_settings, rootKey);

            if (savedInstanceState != null) {
                isUsingPreviewChannel = savedInstanceState.getBoolean(KEY_USE_SECURITY_PREVIEW_CHANNEL, true);
            } else {
                isUsingPreviewChannel = shouldUseSecurityPreviewChannel(requireContext());
            }

            final CheckBoxPreference useSecurityPreviewChannel =
                    requirePreference(KEY_USE_SECURITY_PREVIEW_CHANNEL);
            useSecurityPreviewChannel.setChecked(isUsingPreviewChannel);
            useSecurityPreviewChannel.setOnPreferenceChangeListener((pref, newValue) -> {
                // persistence is handled when user presses primary button
                isUsingPreviewChannel = (boolean) newValue;
                return true;
            });
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(KEY_USE_SECURITY_PREVIEW_CHANNEL, isUsingPreviewChannel);
        }

        private <T extends Preference> T requirePreference(String key) {
            return requireNonNull(findPreference(key));
        }
    }
}

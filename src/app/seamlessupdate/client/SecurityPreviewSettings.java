package app.seamlessupdate.client;

import static java.util.Objects.requireNonNull;

import static app.seamlessupdate.client.Settings.KEY_USE_SECURITY_PREVIEW_CHANNEL;
import static app.seamlessupdate.client.Settings.KEY_WAITING_FOR_REBOOT;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupcompat.util.WizardManagerHelper;
import com.google.android.setupdesign.GlifLayout;
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

        setContentView(R.layout.security_preview_settings_activity);
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        theme.applyStyle(R.style.SetupWizardPartnerResource, true);
        super.onApplyThemeResource(theme, resid, first);
    }

    public static class SettingsFragment extends Fragment {
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
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.security_preview_settings_fragment, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            final Activity activity = requireActivity();

            GlifLayout layout = (GlifLayout) view;

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

            if (savedInstanceState != null) {
                isUsingPreviewChannel = savedInstanceState.getBoolean(
                        KEY_USE_SECURITY_PREVIEW_CHANNEL, DEFAULT_SECURITY_PREVIEW_WHEN_UNSET);
            } else {
                isUsingPreviewChannel = shouldUseSecurityPreviewChannel(requireContext());
            }

            final LinearLayout container =
                    requireNonNull(layout.findViewById(R.id.enabled_container));
            final CheckBox checkbox = requireNonNull(layout.findViewById(R.id.checkbox_enabled));

            updateUi(checkbox);
            container.setOnClickListener((v) -> {
                isUsingPreviewChannel = !isUsingPreviewChannel;
                updateUi(checkbox);
            });
            checkbox.setOnClickListener((v) -> {
                isUsingPreviewChannel = !isUsingPreviewChannel;
                updateUi(checkbox);
            });
        }

        private void updateUi(CheckBox checkbox) {
            checkbox.setChecked(isUsingPreviewChannel);
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
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putBoolean(KEY_USE_SECURITY_PREVIEW_CHANNEL, isUsingPreviewChannel);
        }
    }
}

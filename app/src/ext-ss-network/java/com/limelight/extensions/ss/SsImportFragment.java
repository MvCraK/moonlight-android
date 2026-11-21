// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
package com.limelight.extensions.ss;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;

/**
 * Headless fragment that receives the config picker result, so PcView's own
 * onActivityResult stays untouched (framework fragments get their results directly).
 */
public final class SsImportFragment extends Fragment {
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SsPanel.IMPORT && resultCode == Activity.RESULT_OK && data != null
                && data.getData() != null && getActivity() != null) {
            SsPanel.of(getActivity()).importConfig(data.getData());
        }
    }
}

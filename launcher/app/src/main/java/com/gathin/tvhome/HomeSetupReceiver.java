package com.gathin.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Explicit, shell-only installer bridge, protected by the platform DUMP permission. */
public final class HomeSetupReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.gathin.tvhome.HOME_SETUP";
    private static final String PREFIX = "peach-home-v1:";

    @Override
    public void onReceive(Context context, Intent intent) {
        setResultCode(Activity.RESULT_CANCELED);
        try {
            if (!ACTION.equals(intent.getAction())) throw new IllegalArgumentException("Invalid Home setup action");
            Bundle extras = intent.getExtras();
            Object operation = extras == null ? null : extras.get("operation");
            if (!(operation instanceof String requestedOperation))
                throw new IllegalArgumentException("Missing Home setup operation");
            HomeSetupState state;
            if ("read".equals(requestedOperation)) {
                state = HomeSetupState.read(context);
            } else if ("write".equals(requestedOperation)) {
                Object stock = extras.get("stock_package"), filter = extras.get("filter_keys");
                if (!(stock instanceof String stockPackage) || !(filter instanceof Boolean filterKeys))
                    throw new IllegalArgumentException("Invalid Home setup fields");
                state = HomeSetupState.requested(stockPackage, filterKeys, context.getPackageName());
                if (!state.stockPackage.isEmpty()) {
                    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .setPackage(state.stockPackage);
                    if (context.getPackageManager().queryIntentActivities(home,
                            PackageManager.MATCH_DISABLED_COMPONENTS).isEmpty())
                        throw new IllegalArgumentException("Package does not provide a Home activity");
                }
                if (!state.persist(context)) throw new IllegalStateException("Could not persist Home setup");
                HomeKeyService.refreshHomeSetup();
            } else {
                throw new IllegalArgumentException("Unsupported Home setup operation");
            }
            JSONObject result = new JSONObject().put("version", 1)
                    .put("stock_package", state.stockPackage).put("filter_keys", state.filterKeys)
                    .put("bound", HomeKeyService.isBound())
                    .put("applied_stock_package", HomeKeyService.appliedStockPackage())
                    .put("filter_keys_active", HomeKeyService.keyFilterActive());
            setResultData(PREFIX + Base64.encodeToString(result.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            setResultCode(Activity.RESULT_OK);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException invalid) {
            // Intent fields and firmware package/persistence refusals are the external request boundary.
            String error = "{\"version\":1,\"error\":" + JSONObject.quote(invalid.getMessage()) + "}";
            setResultData(PREFIX + Base64.encodeToString(error.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible); // the response uses only owned, typed values
        }
    }
}

package mx.octomind.progol;

import android.content.Context;
import android.content.SharedPreferences;

/** Guarda la sesión (tokens de Cognito) y la preferencia biométrica. */
public final class Session {
    private static final String PREFS = "octomind_session";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_BIOMETRIC = "biometric_enabled";
    private static final String KEY_SELECTED_DRAW = "selected_draw";

    private Session() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context context, AuthRepository.Tokens tokens) {
        prefs(context).edit()
                .putString(KEY_ACCESS, tokens.idToken())
                .putString(KEY_REFRESH, tokens.refreshToken())
                .apply();
    }

    public static String accessToken(Context context) {
        return prefs(context).getString(KEY_ACCESS, "");
    }

    public static String refreshToken(Context context) {
        return prefs(context).getString(KEY_REFRESH, "");
    }

    public static boolean hasRefreshToken(Context context) {
        return !refreshToken(context).isEmpty();
    }

    public static boolean isLoggedIn(Context context) {
        return !accessToken(context).isEmpty();
    }

    /** Cierra la sesión (borra el access token) pero conserva refresh y biometría. */
    public static void logout(Context context) {
        prefs(context).edit().remove(KEY_ACCESS).apply();
    }

    public static boolean biometricEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BIOMETRIC, false);
    }

    public static void setBiometricEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply();
    }

    /** Sorteo seleccionado persistido ("WEEKEND" o "MS"). */
    public static String selectedDraw(Context context) {
        return prefs(context).getString(KEY_SELECTED_DRAW, "MS");
    }

    public static void setSelectedDraw(Context context, String draw) {
        prefs(context).edit().putString(KEY_SELECTED_DRAW, draw).apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}

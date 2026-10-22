package mx.octomind.progol;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

/** Acceso biométrico (huella/rostro) usando la API del framework (API 29+). */
public final class BiometricHelper {
    private BiometricHelper() {}

    public static boolean isAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 29) return false;
        BiometricManager manager = context.getSystemService(BiometricManager.class);
        return manager != null
                && manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void prompt(Activity activity, Runnable onSuccess, Runnable onCancel) {
        BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle("Acceso biométrico")
                .setSubtitle("Usa tu huella o rostro para entrar")
                .setNegativeButton("Usar contraseña", activity.getMainExecutor(),
                        (dialog, which) -> onCancel.run())
                .build();
        prompt.authenticate(
                new CancellationSignal(),
                activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        onSuccess.run();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        onCancel.run();
                    }
                }
        );
    }
}

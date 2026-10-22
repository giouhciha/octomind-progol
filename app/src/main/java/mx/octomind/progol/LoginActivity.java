package mx.octomind.progol;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

public final class LoginActivity extends Activity {
    private EditText emailInput;
    private EditText passwordInput;
    private EditText codeInput;
    private Button confirmButton;
    private Button loginButton;
    private Button registerButton;
    private Button biometricButton;
    private CheckBox biometricCheck;
    private TextView subtitle;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        codeInput = findViewById(R.id.codeInput);
        confirmButton = findViewById(R.id.confirmButton);
        loginButton = findViewById(R.id.loginButton);
        registerButton = findViewById(R.id.registerButton);
        biometricButton = findViewById(R.id.biometricButton);
        biometricCheck = findViewById(R.id.biometricCheck);
        subtitle = findViewById(R.id.subtitle);
        status = findViewById(R.id.loginStatus);

        loginButton.setOnClickListener(view -> login());
        registerButton.setOnClickListener(view -> register());
        confirmButton.setOnClickListener(view -> confirm());
        biometricButton.setOnClickListener(view -> authenticate());
        findViewById(R.id.privacyLink).setOnClickListener(view -> openPrivacyPolicy());

        boolean biometricActive = Session.biometricEnabled(this)
                && Session.hasRefreshToken(this)
                && BiometricHelper.isAvailable(this);
        if (biometricActive) {
            showBiometricOnly();
        } else {
            showForm();
        }
    }

    private void openPrivacyPolicy() {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://octomind-progol-policies.s3.us-east-1.amazonaws.com/privacy.html")));
    }

    private void showBiometricOnly() {
        subtitle.setText("Bienvenido de nuevo");
        emailInput.setVisibility(View.GONE);
        passwordInput.setVisibility(View.GONE);
        loginButton.setVisibility(View.GONE);
        registerButton.setVisibility(View.GONE);
        biometricCheck.setVisibility(View.GONE);
        codeInput.setVisibility(View.GONE);
        confirmButton.setVisibility(View.GONE);
        biometricButton.setVisibility(View.VISIBLE);
    }

    private void showForm() {
        subtitle.setText("Inicia sesión o regístrate para analizar");
        emailInput.setVisibility(View.VISIBLE);
        passwordInput.setVisibility(View.VISIBLE);
        loginButton.setVisibility(View.VISIBLE);
        registerButton.setVisibility(View.VISIBLE);
        biometricCheck.setVisibility(BiometricHelper.isAvailable(this) ? View.VISIBLE : View.GONE);
        codeInput.setVisibility(View.GONE);
        confirmButton.setVisibility(View.GONE);
        biometricButton.setVisibility(View.GONE);
    }

    private void authenticate() {
        BiometricHelper.prompt(this,
                () -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                },
                () -> showForm()); // si cancela, cae al formulario de contraseña
    }

    private void showStatus(String message, boolean ok) {
        status.setText(message);
        status.setTextColor(getColor(ok ? R.color.primary : R.color.result_error));
    }

    private void login() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        final boolean useBiometric = biometricCheck.isChecked();
        if (email.isEmpty() || password.isEmpty()) {
            showStatus("Ingresa correo y contraseña", false);
            return;
        }
        showStatus("Iniciando sesión…", true);
        new Thread(() -> {
            try {
                AuthRepository.Tokens tokens = new AuthRepository().login(email, password);
                Session.save(this, tokens);
                Session.setBiometricEnabled(this, useBiometric);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> showStatus(
                        message == null || message.isBlank() ? "Error al iniciar sesión" : message, false));
            }
        }).start();
    }

    private void register() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            showStatus("Ingresa correo y contraseña para registrarte", false);
            return;
        }
        showStatus("Registrando…", true);
        new Thread(() -> {
            try {
                new AuthRepository().signUp(email, password);
                runOnUiThread(() -> {
                    codeInput.setVisibility(View.VISIBLE);
                    confirmButton.setVisibility(View.VISIBLE);
                    showStatus("Te enviamos un código a tu correo", true);
                });
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> showStatus(
                        message == null || message.isBlank() ? "Error al registrar" : message, false));
            }
        }).start();
    }

    private void confirm() {
        String email = emailInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        if (email.isEmpty() || code.isEmpty()) {
            showStatus("Ingresa el código de verificación", false);
            return;
        }
        showStatus("Confirmando…", true);
        new Thread(() -> {
            try {
                new AuthRepository().confirmSignUp(email, code);
                runOnUiThread(() -> {
                    codeInput.setVisibility(View.GONE);
                    confirmButton.setVisibility(View.GONE);
                    codeInput.setText("");
                    showStatus("Cuenta confirmada, ya puedes iniciar sesión", true);
                });
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> showStatus(
                        message == null || message.isBlank() ? "Error al confirmar" : message, false));
            }
        }).start();
    }
}

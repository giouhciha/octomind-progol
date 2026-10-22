package mx.octomind.progol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Login contra Cognito (flujo USER_PASSWORD_AUTH, sin SDK externo). */
public final class AuthRepository {
    public static final String CLIENT_ID = "155411dpvj85c41rgijqa28vu3";
    private static final String ENDPOINT = "https://cognito-idp.us-east-1.amazonaws.com/";

    public record Tokens(String accessToken, String idToken, String refreshToken) {}

    public Tokens login(String email, String password) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("ClientId", CLIENT_ID);
        payload.put("AuthFlow", "USER_PASSWORD_AUTH");
        JSONObject parameters = new JSONObject();
        parameters.put("USERNAME", email);
        parameters.put("PASSWORD", password);
        payload.put("AuthParameters", parameters);
        String body = post(
                ENDPOINT,
                "AWSCognitoIdentityProviderService.InitiateAuth",
                payload.toString()
        );
        return parseAuthResponse(body);
    }

    static Tokens parseAuthResponse(String json) throws Exception {
        JSONObject result = new JSONObject(json).getJSONObject("AuthenticationResult");
        return new Tokens(
                result.getString("AccessToken"),
                result.getString("IdToken"),
                result.optString("RefreshToken", "")
        );
    }

    /** Registra un nuevo usuario (Cognito envía un código de verificación al correo). */
    public void signUp(String email, String password) throws Exception {
        JSONArray attributes = new JSONArray();
        attributes.put(new JSONObject().put("Name", "email").put("Value", email));
        JSONObject payload = new JSONObject();
        payload.put("ClientId", CLIENT_ID);
        payload.put("Username", email);
        payload.put("Password", password);
        payload.put("UserAttributes", attributes);
        post(ENDPOINT, "AWSCognitoIdentityProviderService.SignUp", payload.toString());
    }

    /** Confirma el registro con el código recibido por correo. */
    public void confirmSignUp(String email, String code) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("ClientId", CLIENT_ID);
        payload.put("Username", email);
        payload.put("ConfirmationCode", code);
        post(ENDPOINT, "AWSCognitoIdentityProviderService.ConfirmSignUp", payload.toString());
    }

    /** Renueva la sesión a partir del refresh token. */
    public Tokens refresh(String refreshToken) throws Exception {
        JSONObject parameters = new JSONObject();
        parameters.put("REFRESH_TOKEN", refreshToken);
        JSONObject payload = new JSONObject();
        payload.put("ClientId", CLIENT_ID);
        payload.put("AuthFlow", "REFRESH_TOKEN_AUTH");
        payload.put("AuthParameters", parameters);
        return parseAuthResponse(
                post(ENDPOINT, "AWSCognitoIdentityProviderService.InitiateAuth", payload.toString()));
    }

    private static String post(String url, String target, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-amz-json-1.1");
        connection.setRequestProperty("X-Amz-Target", target);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException(extractMessage(text));
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String extractMessage(String json) {
        try {
            return new JSONObject(json).optString("message", json);
        } catch (Exception ignored) {
            return json;
        }
    }

    static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

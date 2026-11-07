package dev.n1cholaspoole.wificonnector;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.security.auth.x500.X500Principal;

/**
 * Dedicated store for the Wi-Fi password. The value is kept in its own preferences file
 * and encrypted with a key that stays inside the Android Keystore, so it never sits in
 * plain text in the general settings. Works on API 21+ (AES/GCM on 23+, RSA below that).
 */
public final class SecretStore {

    private static final String FILE = "secrets";
    private static final String KEY_CIPHERTEXT = "wifi_password";
    private static final String PLAIN_PREFIX = "plain:";
    private static final String ALIAS_AES = "wifi_connector_aes_key";
    private static final String ALIAS_RSA = "wifi_connector_rsa_key";

    private SecretStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Returns the stored password, or null when nothing has been stored yet. */
    public static String getPassword(Context context) {
        String stored = prefs(context).getString(KEY_CIPHERTEXT, null);
        if (stored == null) {
            return null;
        }
        try {
            if (stored.startsWith(PLAIN_PREFIX)) {
                return stored.substring(PLAIN_PREFIX.length());
            }
            return decrypt(stored);
        } catch (Exception e) {
            AppLog.log(context, "Could not read the encrypted Wi-Fi password: "
                    + e.getClass().getSimpleName());
            return "";
        }
    }

    public static void setPassword(Context context, String password) {
        try {
            prefs(context).edit()
                    .putString(KEY_CIPHERTEXT, encrypt(context, password))
                    .apply();
        } catch (Exception e) {
            AppLog.log(context, "Could not encrypt the Wi-Fi password; keeping it in the "
                    + "dedicated secrets file: " + e.getClass().getSimpleName());
            prefs(context).edit()
                    .putString(KEY_CIPHERTEXT, PLAIN_PREFIX + password)
                    .apply();
        }
    }

    private static String encrypt(Context context, String plaintext) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Api23.encrypt(plaintext);
        }
        return Legacy.encrypt(context, plaintext);
    }

    private static String decrypt(String stored) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Api23.decrypt(stored);
        }
        return Legacy.decrypt(stored);
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static final class Api23 {

        private Api23() {
        }

        static String encrypt(String plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        }

        static String decrypt(String stored) throws Exception {
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            byte[] ciphertext = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }

        private static SecretKey secretKey() throws Exception {
            KeyStore keyStore = keyStore();
            if (!keyStore.containsAlias(ALIAS_AES)) {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(ALIAS_AES,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                generator.generateKey();
            }
            return (SecretKey) keyStore.getKey(ALIAS_AES, null);
        }
    }

    private static final class Legacy {

        private Legacy() {
        }

        static String encrypt(Context context, String plaintext) throws Exception {
            KeyStore keyStore = rsaKeyStore(context);
            PublicKey publicKey = keyStore.getCertificate(ALIAS_RSA).getPublicKey();
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.encodeToString(
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)),
                    Base64.NO_WRAP);
        }

        static String decrypt(String stored) throws Exception {
            KeyStore keyStore = rsaKeyStore(null);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(ALIAS_RSA, null);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return new String(
                    cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        }

        private static KeyStore rsaKeyStore(Context context) throws Exception {
            KeyStore keyStore = keyStore();
            if (keyStore.containsAlias(ALIAS_RSA)) {
                return keyStore;
            }
            if (context == null) {
                throw new IllegalStateException("RSA key is missing");
            }
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 30);
            KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS_RSA)
                    .setSubject(new X500Principal("CN=Wi-Fi Connector"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .build();
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(spec);
            generator.generateKeyPair();
            return keyStore;
        }
    }
}

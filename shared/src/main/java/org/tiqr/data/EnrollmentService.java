package org.tiqr.data;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.tiqr.Constants;
import org.tiqr.R;
import org.tiqr.authenticator.auth.EnrollmentChallenge;
import org.tiqr.authenticator.datamodel.DbAdapter;
import org.tiqr.authenticator.exceptions.SecurityFeaturesException;
import org.tiqr.authenticator.exceptions.UserException;
import org.tiqr.authenticator.security.Encryption;
import org.tiqr.authenticator.security.Secret;
import org.tiqr.data.EnrollmentError.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKey;
import javax.inject.Inject;

/**
 * Enrollment data service.
 */
public class EnrollmentService {
    public interface OnEnrollmentListener {
        public void onEnrollmentSuccess();
        public void onEnrollmentError(EnrollmentError error);
    }

    protected @Inject NotificationService _notificationService;
    protected @Inject Context _context;

    private String _keyToHex(SecretKey secret) {
        byte[] buf = secret.getEncoded();
        StringBuffer strbuf = new StringBuffer(buf.length * 2);
        int i;

        for (i = 0; i < buf.length; i++) {
            if (((int)buf[i] & 0xff) < 0x10)
                strbuf.append("0");

            strbuf.append(Long.toString((int)buf[i] & 0xff, 16));
        }

        return strbuf.toString();
    }

    /**
     * Send enrollment request to server.
     *
     * @param challenge Enrollment challenge.
     * @param password  Password / PIN.
     * @param listener  Completion listener.
     */
    public AsyncTask<?, ?, ?> enroll(final EnrollmentChallenge challenge, final String password, final OnEnrollmentListener listener) {
        AsyncTask<Void, Void, EnrollmentError> task = new AsyncTask<Void, Void, EnrollmentError>() {
            @Override
            protected EnrollmentError doInBackground(Void... voids) {
                try {
                    SecretKey sessionKey = Encryption.keyFromPassword(_context, password);

                    SecretKey secret = _generateSecret();

                    HttpPost httpPost = new HttpPost(challenge.getEnrollmentURL());

                    List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(3);
                    nameValuePairs.add(new BasicNameValuePair("secret", _keyToHex(secret)));
                    nameValuePairs.add(new BasicNameValuePair("language", Locale.getDefault().getLanguage()));
                    String notificationAddress = _notificationService.getNotificationToken();
                    if (notificationAddress != null) {
                        nameValuePairs.add(new BasicNameValuePair("notificationType", "C2DM"));
                        nameValuePairs.add(new BasicNameValuePair("notificationAddress", notificationAddress));
                    }

                    nameValuePairs.add(new BasicNameValuePair("operation", "register"));

                    httpPost.setHeader("ACCEPT", "application/json");
                    httpPost.setHeader("X-TIQR-Protocol-Version", Constants.PROTOCOL_VERSION);

                    httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairs, HTTP.UTF_8));

                    DefaultHttpClient httpClient = new DefaultHttpClient();
                    HttpResponse httpResponse = httpClient.execute(httpPost);

                    EnrollmentError error = null;

                    Header versionHeader = httpResponse.getFirstHeader("X-TIQR-Protocol-Version");
                    if (versionHeader == null || versionHeader.getValue().equals("1")) {
                        // v1 protocol (ascii)
                        error = _parseV1Response(EntityUtils.toString(httpResponse.getEntity()));
                    } else {
                        // v2 protocol (json)
                        error = _parseV2Response(EntityUtils.toString(httpResponse.getEntity()));
                    }

                    if (error == null) {
                        _storeIdentityAndIdentityProvider(challenge, secret, sessionKey);
                        return null;
                    } else {
                        return error;
                    }
                } catch (Exception ex) {
                    return new EnrollmentError(Type.CONNECTION, _context.getString(R.string.error_enroll_connect_error), _context.getString(R.string.error_enroll_connect_error));
                }
            }

            @Override
            protected void onPostExecute(EnrollmentError error) {
                if (error == null) {
                    listener.onEnrollmentSuccess();
                } else {
                    listener.onEnrollmentError(error);
                }
            }
        };

        task.execute();
        return task;
    }


    /**
     * Parse v1 response format (ascii), return error object when unsuccessful.
     *
     * @param response
     *
     * @return Error object on failure.
     */
    private EnrollmentError _parseV1Response(String response) {
        if (response != null && response.equals("OK")) {
            return null;
        } else {
            return new EnrollmentError(Type.UNKNOWN, _context.getString(R.string.enrollment_failure_title), response);
        }
    }

    /**
     * Parse v2 response format (json), return error object when unsuccessful.
     *
     * @param response
     *
     * @return Error object on failure.
     */
    private EnrollmentError _parseV2Response(String response) {
        try {
            JSONObject object = new JSONObject(response);

            int responseCode = object.getInt("responseCode");
            if (responseCode == 1) {
                return null; // success, no error
            }

            Type type = Type.UNKNOWN;
            String message = object.optString("message", "");

            if (message.length() == 0) {
                switch (responseCode) {
                    case 100:
                        type = Type.VERIFICATION_REQUIRED;
                        message = _context.getString(R.string.error_enroll_verification_needed);
                        break;
                    case 102:
                        type = Type.USERNAME_TAKEN;
                        message = _context.getString(R.string.error_enroll_username_taken);
                        break;
                    default:
                        message = _context.getString(R.string.error_enroll_unknown);
                        break;
                }
            }

            return new EnrollmentError(type, _context.getString(R.string.enrollment_failure_title), message);
        } catch (JSONException e) {
            return new EnrollmentError(Type.INVALID_RESPONSE, _context.getString(R.string.enrollment_failure_title),  _context.getString(R.string.error_enroll_invalid_response));
        }

    }

    /**
     * Generate identity secret.
     *
     * @return secret key
     *
     * @throws UserException
     */
    private SecretKey _generateSecret() throws UserException {
        try {
            return Encryption.generateRandomSecretKey();
        } catch (Exception ex) {
            throw new UserException(_context.getString(R.string.error_enroll_failed_to_generate_secret));
        }
    }

    /**
     * Store identity (and identity provider if needed).
     */
    private void _storeIdentityAndIdentityProvider(EnrollmentChallenge challenge, SecretKey secret, SecretKey sessionKey) throws UserException {
        DbAdapter db = new DbAdapter(_context);
        if (challenge.getIdentityProvider().isNew() && !db.insertIdentityProvider(challenge.getIdentityProvider())) {
            throw new UserException(_context.getString(R.string.error_enroll_failed_to_store_identity_provider));
        }

        if (!db.insertIdentityForIdentityProvider(challenge.getIdentity(), challenge.getIdentityProvider())) {
            throw new UserException(_context.getString(R.string.error_enroll_failed_to_store_identity));
        }

        Secret secretStore = Secret.secretForIdentity(challenge.getIdentity(), _context);
        secretStore.setSecret(secret);
        try {
            secretStore.storeInKeyStore(sessionKey);
        } catch (SecurityFeaturesException e) {
            throw new UserException(_context.getString(R.string.error_device_incompatible_with_security_standards));
        }
    }
}
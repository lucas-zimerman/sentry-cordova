package io.sentry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;

import io.sentry.Sentry;
import io.sentry.UncaughtExceptionHandlerIntegration;
import io.sentry.android.core.AnrIntegration;
import io.sentry.android.core.NdkIntegration;
import io.sentry.android.core.SentryAndroid;
import io.sentry.protocol.User;

public class SentryCordova extends CordovaPlugin {
  private static final String TAG = "Sentry";

  final static Logger logger = Logger.getLogger("sentry-cordova");

  private SentryOptions sentryOptions;

  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);
    Log.d(TAG, "Initializing Sentry");
  }

  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {
    // Special case for crash outside the try/catch block
    if (action.equals("crash")) {
      crash();
      return true;
    }

    try {
      switch (action) {
      case "startWithOptions":
        JSONObject jsonOptions = args.getJSONObject(0);

        startWithOptions(jsonOptions, callbackContext);

        break;
      case "captureEnvelope":
        String envelope = args.getString(0);

        captureEnvelope(envelope, callbackContext);

        break;
      case "setUser":
        JSONObject jsonUser = null;
        JSONObject otherUserKeys = null;

        if (!args.isNull(0)) {
          jsonUser = args.getJSONObject(0);
        }
        if (!args.isNull(1)) {
          otherUserKeys = args.getJSONObject(1);
        }

        setUser(jsonUser, otherUserKeys, callbackContext);

        break;
      case "addBreadcrumb":
        JSONObject jsonBreadcrumb = args.getJSONObject(0);

        addBreadcrumb(jsonBreadcrumb, callbackContext);

        break;
      case "clearBreadcrumbs":
        clearBreadcrumbs(callbackContext);
        break;
      case "getStringBytesLength":
        String payload = args.getString(0);

        int length = getStringBytesLength(payload);

        callbackContext.sendPluginResult(new PluginResult(Status.OK, length));

        break;
      case "setTag":
        String tagKey = args.getString(0);
        String tag = args.getString(1);

        setTag(tagKey, tag, callbackContext);

        break;
      case "setContext":
        String contextKey = args.getString(0);
        JSONObject contextObject = null;
        if (!args.isNull(1)) {
          contextObject = args.getJSONObject(1);
        }

        setContext(contextKey, contextObject, callbackContext);

        break;
      case "setExtra":
        String extraKey = args.getString(0);
        String extra = args.getString(1);

        setExtra(extraKey, extra, callbackContext);

        break;
      default:
        callbackContext.sendPluginResult(new PluginResult(Status.ERROR, "not implemented"));
        break;
      }

    } catch (JSONException e) {
      logger.log(Level.SEVERE, "Error parsing JSON from native bridge");

      callbackContext.sendPluginResult(new PluginResult(Status.ERROR, false));

      return false;
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Error occurred on native bridge: ", e);

      callbackContext.sendPluginResult(new PluginResult(Status.ERROR, false));

      return false;
    }

    return true;
  }

  private void startWithOptions(final JSONObject jsonOptions, final CallbackContext callbackContext) {
    String dsn = jsonOptions.optString("dsn", null);

    if (dsn == null) {
      logger.log(Level.SEVERE, "No DSN passed through native bridge, native Android SDK will not start.");

      callbackContext.sendPluginResult(new PluginResult(Status.ERROR, "Missing dsn"));
    } else {
      SentryAndroid.init(this.cordova.getActivity().getApplicationContext(), options -> {
        try {
          logger.info(String.format("Starting with DSN: '%s'", dsn));
          options.setDsn(dsn);

          boolean debug = jsonOptions.optBoolean("debug", false);
          options.setDebug(debug);

          if (!jsonOptions.isNull("environment")) {
            options.setEnvironment(jsonOptions.getString("environment"));
          }
          if (!jsonOptions.isNull("release")) {
            options.setRelease(jsonOptions.getString("release"));
          }
          if (!jsonOptions.isNull("dist")) {
            options.setDist(jsonOptions.getString("dist"));
          }
          if (jsonOptions.has("maxBreadcrumbs")) {
            options.setMaxBreadcrumbs(jsonOptions.getInt("maxBreadcrumbs"));
          }
          if (jsonOptions.has("enableAutoSessionTracking")) {
            options.setEnableSessionTracking(jsonOptions.getBoolean("enableAutoSessionTracking"));
          }
          if (jsonOptions.has("sessionTrackingIntervalMillis")) {
            options.setSessionTrackingIntervalMillis(jsonOptions.getInt("sessionTrackingIntervalMillis"));
          }
          if (jsonOptions.has("enableNdkScopeSync")) {
            options.setEnableScopeSync(jsonOptions.getBoolean("enableNdkScopeSync"));
          }
          if (jsonOptions.has("attachStacktrace")) {
            options.setAttachStacktrace(jsonOptions.getBoolean("attachStacktrace"));
          }
          if (jsonOptions.has("attachThreads")) {
            // JS use top level stacktraces and android attaches Threads which
            // hides them so by default we hide.
            options.setAttachThreads(jsonOptions.getBoolean("attachThreads"));
          }

          boolean enableNativeCrashHandling = jsonOptions.optBoolean("enableNativeCrashHandling", true);
          if (!enableNativeCrashHandling) {
            options.setEnableUncaughtExceptionHandler(false);
            options.setAnrEnabled(false);
            options.setEnableNdk(false);
          }

          sentryOptions = options;
        } catch (JSONException e) {
          logger.info("Error parsing options JSON sent over native bridge.");
          callbackContext.sendPluginResult(new PluginResult(Status.ERROR, false));
        }
      });

      callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
    }
  }

  private void captureEnvelope(String envelope, final CallbackContext callbackContext) {
    try {
      File installation = new File(sentryOptions.getOutboxPath(), UUID.randomUUID().toString());
      try (FileOutputStream out = new FileOutputStream(installation)) {
        out.write(envelope.getBytes(Charset.forName("UTF-8")));
      }
    } catch (Exception e) {
      logger.info("Error reading envelope");
      callbackContext.sendPluginResult(new PluginResult(Status.ERROR, false));
    }

    callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
  }

  private void setUser(final JSONObject jsonUser, final JSONObject otherUserKeys, final CallbackContext callbackContext) {
    Sentry.configureScope(scope -> {
      try {
        if (jsonUser == null && otherUserKeys == null) {
          scope.setUser(null);
        } else {
          User userInstance = new User();

          if (jsonUser != null) {
            if (jsonUser.has("email")) {
              userInstance.setEmail(jsonUser.getString("email"));
            }

            if (jsonUser.has("id")) {
              userInstance.setId(jsonUser.getString("id"));
            }

            if (jsonUser.has("username")) {
              userInstance.setUsername(jsonUser.getString("username"));
            }

            if (jsonUser.has("ip_address")) {
              userInstance.setIpAddress(jsonUser.getString("ip_address"));
            }
          }

          if (otherUserKeys != null) {
            HashMap<String, String> otherUserKeysMap = new HashMap<String, String>();
            Iterator<String> it = otherUserKeys.keys();
            while (it.hasNext()) {
              String key = it.next();
              String value = otherUserKeys.getString(key);

              otherUserKeysMap.put(key, value);
            }

            userInstance.setOthers(otherUserKeysMap);
          }

          scope.setUser(userInstance);
        }
      } catch (JSONException e) {
        logger.info("Error deserializing user");
      }
    });

    callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
  }

  private void addBreadcrumb(final JSONObject jsonBreadcrumb, final CallbackContext callbackContext) {
    Sentry.configureScope(scope -> {
      try {
        Breadcrumb breadcrumb = new Breadcrumb();

        if (jsonBreadcrumb.has("message")) {
          breadcrumb.setMessage(jsonBreadcrumb.getString("message"));
        }

        if (jsonBreadcrumb.has("type")) {
          breadcrumb.setType(jsonBreadcrumb.getString("type"));
        }

        if (jsonBreadcrumb.has("category")) {
          breadcrumb.setCategory(jsonBreadcrumb.getString("category"));
        }

        if (jsonBreadcrumb.has("level")) {
          breadcrumb.setLevel(getSentryLevelFromString(jsonBreadcrumb.getString("level")));
        }

        if (jsonBreadcrumb.has("data")) {
          JSONObject data = jsonBreadcrumb.getJSONObject("data");
          Iterator<String> it = data.keys();
          while (it.hasNext()) {
            String key = it.next();
            String value = data.getString(key);

            breadcrumb.setData(key, value);
          }
        }

        scope.addBreadcrumb(breadcrumb);
        logger.info("Send breadcrumb successful");
      } catch (JSONException e) {
        logger.info("Error deserializing breadcrumb");
      }
    });
  }

  private void clearBreadcrumbs(final CallbackContext callbackContext) {
    Sentry.configureScope(scope -> { scope.clearBreadcrumbs(); });
    callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
  }

  public void setExtra(String key, String extra, final CallbackContext callbackContext) {
    Sentry.configureScope(scope -> { scope.setExtra(key, extra); });

    callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
  }

  public void setTag(String key, String value, final CallbackContext callbackContext) {
    Sentry.configureScope(scope -> { scope.setTag(key, value); });

    callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
  }

  public void setContext(String contextKey, JSONObject jsonContext, final CallbackContext callbackContext) throws JSONException {
    if (jsonContext == null) {
      Sentry.configureScope(scope -> { scope.removeContexts(contextKey); });
    } else {
      final Map<String, String> contextMap = new HashMap<>();

      Iterator<String> it = jsonContext.keys();
      while (it.hasNext()) {
        String key = it.next();
        String value = jsonContext.getString(key);

        contextMap.put(key, value);
      }

      Sentry.configureScope(scope -> { scope.setContexts(contextKey, contextMap); });
    }

    callbackContext.sendPluginResult(new PluginResult(Status.OK, true));
  }

  private int getStringBytesLength(String payload) throws UnsupportedEncodingException { return payload.getBytes("UTF-8").length; }

  private SentryLevel getSentryLevelFromString(String level) {
    switch (level) {
    case "fatal":
      return SentryLevel.FATAL;
    case "warning":
      return SentryLevel.WARNING;
    case "info":
      return SentryLevel.INFO;
    case "debug":
      return SentryLevel.DEBUG;
    case "error":
      return SentryLevel.ERROR;
    default:
      return SentryLevel.ERROR;
    }
  }

  private void crash() { Sentry.captureException(new RuntimeException("TEST - Sentry Silent Client Crash")); }
}

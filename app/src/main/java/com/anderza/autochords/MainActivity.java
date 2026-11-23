package com.anderza.autochords;

import android.Manifest;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.view.WindowManager;
import android.os.Process;
import java.net.URLEncoder;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.view.View;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private static final int FILE_REQUEST = 1001;
    private static final int MICROPHONE_REQUEST = 1002;
    private static final int NATIVE_TUNER_REQUEST = 1003;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest audioRequest;
    private volatile boolean tunerRunning;
    private volatile AudioRecord tunerRecorder;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF090D17);
        webView.addJavascriptInterface(new LyricsBridge(), "AutoChordsNative");
        webView.addJavascriptInterface(new TunerBridge(), "AutoChordsTuner");
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(
                    WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!"appassets.androidplatform.net".equals(request.getOrigin().getHost())) {
                    request.deny();
                    return;
                }
                boolean audioOnly = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audioOnly = true;
                }
                if (!audioOnly) { request.deny(); return; }
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (audioRequest != null) audioRequest.deny();
                        audioRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                                MICROPHONE_REQUEST);
                    }
                });
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (audioRequest == request) audioRequest = null;
            }
        });
        webView.loadUrl(HOME);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == NATIVE_TUNER_REQUEST) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startNativeTuner();
            else reportTunerState("denied");
            return;
        }
        if (requestCode == MICROPHONE_REQUEST && audioRequest != null) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                audioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                audioRequest.deny();
            }
            audioRequest = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    /** A fixed-origin LRCLIB fallback when browser CORS/network blocks search. */
    private class LyricsBridge {

        @JavascriptInterface public void searchOvh(String artist, String title, int requestId) {
            if (artist == null || title == null || artist.length() > 150 || title.length() > 150
                    || requestId < 1 || artist.trim().isEmpty() || title.trim().isEmpty()) {
                sendLyricsReply(requestId, 400, "{}");
                return;
            }
            new Thread(() -> {
                HttpURLConnection connection = null;
                int status = 503;
                String payload = "{}";
                try {
                    String path = URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
                            + "/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20");
                    URL url = new URL("https://api.lyrics.ovh/v1/" + path);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "AutoChords/0.9.1 (https://github.com/anderza/autochords)");
                    status = connection.getResponseCode();
                    try (InputStream input = status >= 200 && status < 300
                            ? connection.getInputStream() : connection.getErrorStream()) {
                        if (input != null) {
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) > 0) {
                                if (output.size() + count > 1500000) throw new Exception("Lyrics response too large");
                                output.write(buffer, 0, count);
                            }
                            payload = new String(output.toByteArray(), StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception ignored) { status = 503; }
                finally { if (connection != null) connection.disconnect(); }
                sendLyricsReply(requestId, status, payload);
            }, "AutoChords-LyricsOvh").start();
        }

        @JavascriptInterface public void searchLyrics(String query, int requestId) {
            if (requestId < 1 || query == null || query.length() > 600
                    || !query.matches("[A-Za-z0-9%&=+_.~*-]+")) {
                sendLyricsReply(requestId, 400, "[]");
                return;
            }
            new Thread(() -> {
                HttpURLConnection connection = null;
                int code = 503;
                String result = "[]";
                try {
                    URL url = new URL("https://lrclib.net/api/search?" + query);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "AutoChords/0.9.1 (https://github.com/anderza/autochords)");
                    code = connection.getResponseCode();
                    try (InputStream input = code >= 200 && code < 300
                            ? connection.getInputStream() : connection.getErrorStream()) {
                        if (input != null) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) > 0) {
                                if (out.size() + count > 1500000) throw new Exception("Lyrics response too large");
                                out.write(buffer, 0, count);
                            }
                            result = new String(out.toByteArray(), StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception ignored) {
                    code = 503;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                sendLyricsReply(requestId, code, result);
            }, "AutoChords-Lyrics").start();
        }
    }

    private void sendLyricsReply(int requestId, int code, String response) {
        final String safe = JSONObject.quote(response == null ? "[]" : response);
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(
                    "window.__autoChordsLyricsReply && window.__autoChordsLyricsReply("
                    + requestId + "," + code + "," + safe + ");", null);
        });
    }


    private class TunerBridge {
        @JavascriptInterface public void startTuner() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    reportTunerState("permission");
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, NATIVE_TUNER_REQUEST);
                } else startNativeTuner();
            });
        }
        @JavascriptInterface public void stopTuner() {
            runOnUiThread(() -> stopNativeTuner());
        }
    }

    private void reportTunerState(String state) {
        final String escaped = JSONObject.quote(state);
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(
                    "window.__autoChordsTunerState && window.__autoChordsTunerState(" + escaped + ");", null);
        });
    }

    private void reportTunerPitch(double hz, double rms) {
        if (!tunerRunning) return;
        final double safeHz = Double.isFinite(hz) && hz > 0 ? hz : 0;
        final double safeRms = Double.isFinite(rms) && rms > 0 ? rms : 0;
        runOnUiThread(() -> {
            if (webView != null && tunerRunning) webView.evaluateJavascript(
                    "window.__autoChordsTunerPitch && window.__autoChordsTunerPitch("
                    + safeHz + "," + safeRms + ");", null);
        });
    }

    private void startNativeTuner() {
        if (tunerRunning) { reportTunerState("ready"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportTunerState("denied");
            return;
        }
        final int sampleRate = 44100;
        final int minimum = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            reportTunerState("error:record buffer unsupported");
            return;
        }
        final AudioRecord recorder;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, 16384 * 2));
        } catch (Exception e) {
            reportTunerState("error:" + e.getClass().getSimpleName());
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            reportTunerState("error:AudioRecord init failed");
            return;
        }
        tunerRecorder = recorder;
        tunerRunning = true;
        Thread worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            final short[] samples = new short[8192];
            try {
                recorder.startRecording();
                reportTunerState("ready");
                while (tunerRunning && recorder == tunerRecorder) {
                    int read = recorder.read(samples, 0, samples.length);
                    if (read < 2048) {
                        if (read < 0) break;
                        continue;
                    }
                    double squared = 0;
                    for (int i = 0; i < read; i++) {
                        double x = samples[i] / 32768.0;
                        squared += x * x;
                    }
                    double rms = Math.sqrt(squared / read);
                    double hz = rms > 0.0025 ? detectGuitarPitch(samples, read, sampleRate) : 0;
                    reportTunerPitch(hz, rms);
                }
            } catch (Exception ex) {
                if (tunerRunning) reportTunerState("error:" + ex.getClass().getSimpleName());
            } finally {
                if (tunerRecorder == recorder) {
                    tunerRunning = false;
                    tunerRecorder = null;
                }
                try { recorder.stop(); } catch (Exception ignored) { }
                recorder.release();
            }
        }, "AutoChords-NativeTuner");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopNativeTuner() {
        tunerRunning = false;
        AudioRecord recorder = tunerRecorder;
        tunerRecorder = null;
        if (recorder != null) try { recorder.stop(); } catch (Exception ignored) { }
    }

    private double detectGuitarPitch(short[] samples, int n, int sampleRate) {
        if (n < 2048) return 0;
        final int minLag = sampleRate / 425;
        final int maxLag = Math.min(sampleRate / 65, n / 2 - 2);
        if (maxLag <= minLag) return 0;
        final int window = Math.min(4096, n - maxLag);
        double[] normalized = new double[maxLag + 2];
        double running = 0;
        for (int lag = 1; lag <= maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i < window; i += 4) {
                double difference = (samples[i] - samples[i + lag]) / 32768.0;
                sum += difference * difference;
            }
            running += sum;
            normalized[lag] = running > 0 ? sum * lag / running : 1.0;
        }
        int chosen = -1;
        double minimum = 1;
        for (int lag = minLag; lag < maxLag; lag++) {
            double value = normalized[lag];
            if (value < minimum) { minimum = value; chosen = lag; }
            if (value < 0.16) {
                while (lag < maxLag - 1 && normalized[lag + 1] < normalized[lag]) lag++;
                chosen = lag;
                minimum = normalized[lag];
                break;
            }
        }
        if (chosen < 1 || minimum > 0.32) return 0;
        double a = normalized[chosen - 1], b = normalized[chosen], c = normalized[chosen + 1];
        double denom = a - 2 * b + c;
        double correction = Math.abs(denom) > 1e-10
                ? Math.max(-0.5, Math.min(0.5, (a - c) / (2 * denom))) : 0;
        double frequency = sampleRate / (chosen + correction);
        return frequency >= 64 && frequency <= 435 ? frequency : 0;
    }

    @Override protected void onPause() {
        stopNativeTuner();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopNativeTuner();
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (audioRequest != null) { audioRequest.deny(); audioRequest = null; }
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}

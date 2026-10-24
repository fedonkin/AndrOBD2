package com.fr3ts0n.ecu.gui.androbd;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.LinearLayout;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayInputStream;

/** Offline reference only: no bridge to ELM, no remote resources, no external navigation. */
public class JaguarManualActivity extends AppCompatActivity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        setTitle(R.string.jag_manual_title);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        Button back = new Button(this); back.setText(R.string.back_action); back.setOnClickListener(v -> finish()); root.addView(back);
        web = new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        ModernUiUtils.applyEdgeToEdgeInsets(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.setWebViewClient(new WebViewClient() {
            private boolean navigate(Uri uri) {
                if ("https".equals(uri.getScheme()) && "manual.invalid".equals(uri.getHost())
                        && uri.getPath() != null && uri.getPath().matches("/page/[0-9]+")) {
                    int page = Integer.parseInt(uri.getLastPathSegment());
                    if (page >= 1 && page <= 309) startActivity(new Intent(JaguarManualActivity.this,
                            JaguarManualPageActivity.class).putExtra("page",page));
                }
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return navigate(Uri.parse(url)); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { return navigate(req.getUrl()); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String url=req.getUrl().toString();
                if (url.startsWith("file:///android_asset/jaguar_manual/")) return null;
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        String code=getIntent().getStringExtra("code");
        boolean ru="ru".equals(getResources().getConfiguration().locale.getLanguage());
        String url=state!=null?state.getString("manual_url"):null;
        if(url==null||!url.startsWith("file:///android_asset/jaguar_manual/index.html?"))
            url="file:///android_asset/jaguar_manual/index.html?lang="+(ru?"ru":"en")
                    +"&code="+Uri.encode(code==null?"":code);
        web.loadUrl(url);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);if(web!=null)out.putString("manual_url",web.getUrl());
    }
    @Override protected void onDestroy() { if(web!=null){web.stopLoading();web.destroy();}super.onDestroy(); }
}

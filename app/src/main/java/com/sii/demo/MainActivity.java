package com.sii.demo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 垂直布局：上面是网页，下面是工具栏
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);

        web = new WebView(this);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web);

        // 底部工具栏：后退 | 主页 | 刷新 | 前进
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        bar.addView(mkBtn("后退", new View.OnClickListener() {
            @Override
            public void onClick(View v) { web.goBack(); }
        }));
        bar.addView(mkBtn("主页", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.loadUrl("file:///android_asset/index.html");
            }
        }));
        bar.addView(mkBtn("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) { web.reload(); }
        }));
        bar.addView(mkBtn("前进", new View.OnClickListener() {
            @Override
            public void onClick(View v) { web.goForward(); }
        }));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
    }

    private Button mkBtn(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(p);
        b.setOnClickListener(listener);
        return b;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 按返回键 = 网页后退，退无可退才退出应用
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

package com.sii.browser.core.webview

/**
 * 网页渲染核心包（com.sii.browser.core.webview）。
 *
 * 本包集中承载所有 WebView 相关逻辑：渲染容器、WebViewClient/ChromeClient 桥接、
 * 资源请求拦截（广告拦截/脚本注入入口）、缓存策略。
 *
 * 约束：禁止在本包之外直接操作 WebView；禁止用 WebView 加载本地 HTML 作为应用界面。
 */

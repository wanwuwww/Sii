package com.sii.browser.ui;

import android.net.Uri;
import android.os.Bundle;

import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.CustomTabsSessionToken;

import java.util.List;

/**
 * Custom Tabs 服务。
 *
 * Sii 不做预热/预加载优化，只实现协议要求的方法，
 * 让其他 App 能把 Sii 当作 Custom Tabs 提供方。
 * 设置里开启「禁用 Custom Tabs」后，所有会话拒绝建立（外部链接回到主界面打开）。
 */
public class SiiCustomTabsService extends CustomTabsService {

    /** 读取设置（服务可能在 AppServices 初始化前后被调用，失败按未禁用处理）。 */
    private boolean cctDisabled() {
        try {
            return com.sii.browser.di.AppServices.INSTANCE.getSettings().cctDisabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected boolean warmup(long flags) {
        return !cctDisabled();
    }

    @Override
    protected boolean newSession(CustomTabsSessionToken sessionToken) {
        return !cctDisabled();
    }

    @Override
    protected boolean mayLaunchUrl(
            CustomTabsSessionToken sessionToken,
            Uri url,
            Bundle extras,
            List<Bundle> otherLikelyBundles
    ) {
        return true;
    }

    @Override
    protected Bundle extraCommand(String commandName, Bundle args) {
        return null;
    }

    @Override
    protected boolean updateVisuals(CustomTabsSessionToken sessionToken, Bundle bundle) {
        return true;
    }

    @Override
    protected boolean requestPostMessageChannel(CustomTabsSessionToken sessionToken, Uri postMessageOrigin) {
        return true;
    }

    @Override
    protected int postMessage(CustomTabsSessionToken sessionToken, String message, Bundle extras) {
        return 0;
    }

    @Override
    protected boolean validateRelationship(
            CustomTabsSessionToken sessionToken,
            int relation,
            Uri origin,
            Bundle extras
    ) {
        return true;
    }

    @Override
    protected boolean receiveFile(
            CustomTabsSessionToken sessionToken,
            Uri uri,
            int purpose,
            Bundle extras
    ) {
        return false;
    }
}

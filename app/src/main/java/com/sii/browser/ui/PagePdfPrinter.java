package com.sii.browser.ui;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.webkit.WebView;

import java.io.File;

/**
 * 用 WebView 打印适配器把当前页面渲染成 PDF 文件，由系统自动分页。
 * Kotlin 访问不到回调构造，故用 Java 实现。
 */
public final class PagePdfPrinter {

    public interface Callback {
        void onDone(boolean ok);
    }

    private PagePdfPrinter() {
    }

    public static void print(final WebView webView, PrintAttributes attrs, final File file,
                             final Callback callback) {
        final PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("Sii");
        adapter.onLayout(null, attrs, null,
                new PrintDocumentAdapter.LayoutResultCallback() {
                    @Override
                    public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                        final ParcelFileDescriptor descriptor;
                        try {
                            descriptor = ParcelFileDescriptor.open(
                                    file,
                                    ParcelFileDescriptor.MODE_CREATE
                                            | ParcelFileDescriptor.MODE_READ_WRITE
                                            | ParcelFileDescriptor.MODE_TRUNCATE
                            );
                        } catch (Exception e) {
                            callback.onDone(false);
                            return;
                        }
                        adapter.onWrite(
                                new PageRange[]{PageRange.ALL_PAGES},
                                descriptor,
                                new CancellationSignal(),
                                new PrintDocumentAdapter.WriteResultCallback() {
                                    @Override
                                    public void onWriteFinished(PageRange[] pages) {
                                        close(descriptor);
                                        callback.onDone(true);
                                    }

                                    @Override
                                    public void onWriteFailed(CharSequence error) {
                                        close(descriptor);
                                        callback.onDone(false);
                                    }

                                    @Override
                                    public void onWriteCancelled() {
                                        close(descriptor);
                                        callback.onDone(false);
                                    }
                                }
                        );
                    }

                    @Override
                    public void onLayoutFailed(CharSequence error) {
                        callback.onDone(false);
                    }
                },
                null
        );
    }

    private static void close(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (Exception ignored) {
        }
    }
}

/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.core.cache;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import com.github.adamantcheese.chan.Chan;
import com.github.adamantcheese.chan.core.di.NetModule;
import com.github.adamantcheese.chan.core.saf.file.RawFile;
import com.github.adamantcheese.chan.utils.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class FileCacheDownloader implements Runnable {
    private static final String TAG = "FileCacheDownloader";
    private static final long BUFFER_SIZE = 8192;
    private static final long NOTIFY_SIZE = BUFFER_SIZE * 8;

    private final String url;
    private final RawFile output;
    private final Handler handler;

    // Main thread only.
    private final Callback callback;
    private final List<FileCacheListener> listeners = new ArrayList<>();

    // Main and worker thread.
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean cancel = new AtomicBoolean(false);

    // Worker thread.
    private Call call;
    private ResponseBody body;

    public FileCacheDownloader(Callback callback, String url, RawFile output) {
        this.callback = callback;
        this.url = url;
        this.output = output;

        handler = new Handler(Looper.getMainLooper());
    }

    @MainThread
    public String getUrl() {
        return url;
    }

    @MainThread
    public void addListener(FileCacheListener callback) {
        listeners.add(callback);
    }

    /**
     * Cancel this download.
     */
    @MainThread
    public void cancel() {
        if (cancel.compareAndSet(false, true)) {
            // Did not start running yet, mark finished here.
            if (!running.get() && callback != null) {
                callback.downloaderFinished(this);
            }
        }
    }

    @AnyThread
    private void log(String message) {
        Logger.d(TAG, logPrefix() + message);
    }

    @AnyThread
    private void log(String message, Exception e) {
        Logger.e(TAG, logPrefix() + message, e);
    }

    private String logPrefix() {
        return "[" + url.substring(0, Math.min(url.length(), 45)) + "] ";
    }

    @Override
    @WorkerThread
    public void run() {
        log("start");
        running.set(true);
        execute();
    }

    @WorkerThread
    private void execute() {
        Closeable sourceCloseable = null;
        Closeable sinkCloseable = null;
        OutputStream outputFileOutputStream = null;

        try {
            checkCancel();

            ResponseBody body = getBody();

            Source source = body.source();
            sourceCloseable = source;

            outputFileOutputStream = output.getOutputStream();
            if (outputFileOutputStream == null) {
                throw new IOException("Couldn't get output file's OutputStream");
            }

            BufferedSink sink = Okio.buffer(Okio.sink(outputFileOutputStream));
            sinkCloseable = sink;

            checkCancel();

            log("got input stream");

            pipeBody(source, sink);

            log("done");

            long fileLen = output.getLength();

            handler.post(() -> {
                if (callback != null) {
                    callback.downloaderAddedFile(fileLen);
                    callback.downloaderFinished(this);
                }
                for (FileCacheListener callback : listeners) {
                    callback.onSuccess(output);
                    callback.onEnd();
                }
            });
        } catch (IOException e) {
            boolean isNotFound = false;
            boolean cancelled = false;
            if (e instanceof HttpCodeIOException) {
                int code = ((HttpCodeIOException) e).code;
                log("exception: http error, code: " + code, e);
                isNotFound = code == 404;
            } else if (e instanceof CancelException) {
                // Don't log the stack.
                log("exception: cancelled");
                cancelled = true;
            } else {
                log("exception", e);
            }

            final boolean finalIsNotFound = isNotFound;
            final boolean finalCancelled = cancelled;
            handler.post(() -> {
                purgeOutput();
                for (FileCacheListener callback : listeners) {
                    if (finalCancelled) {
                        callback.onCancel();
                    } else {
                        callback.onFail(finalIsNotFound);
                    }

                    callback.onEnd();
                }
                if (callback != null) {
                    callback.downloaderFinished(this);
                }
            });
        } finally {
            Util.closeQuietly(sourceCloseable);
            Util.closeQuietly(sinkCloseable);
            Util.closeQuietly(outputFileOutputStream);

            if (call != null) {
                call.cancel();
            }

            if (body != null) {
                Util.closeQuietly(body);
            }
        }
    }

    @WorkerThread
    private ResponseBody getBody() throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", NetModule.USER_AGENT)
                .build();

        //we want to use the proxy instance here
        call = ((NetModule.ProxiedOkHttpClient) Chan.injector().instance(OkHttpClient.class))
                .getProxiedClient().newCall(request);
        Response response = call.execute();

        if (!response.isSuccessful()) {
            throw new HttpCodeIOException(response.code());
        }

        checkCancel();

        body = response.body();
        if (body == null) {
            throw new IOException("body == null");
        }

        checkCancel();

        return body;
    }

    @WorkerThread
    private void pipeBody(Source source, BufferedSink sink) throws IOException {
        long contentLength = body.contentLength();

        long read;
        long total = 0;
        long notifyTotal = 0;

        Buffer buffer = new Buffer();

        while ((read = source.read(buffer, BUFFER_SIZE)) != -1) {
            sink.write(buffer, read);
            total += read;

            if (total >= notifyTotal + NOTIFY_SIZE) {
                notifyTotal = total;
                log("progress " + (total / (float) contentLength));
                postProgress(total, contentLength <= 0 ? total : contentLength);
            }

            checkCancel();
        }

        Util.closeQuietly(source);
        Util.closeQuietly(sink);

        call = null;
        Util.closeQuietly(body);
        body = null;
    }

    @WorkerThread
    private void checkCancel() throws IOException {
        if (cancel.get()) {
            throw new CancelException();
        }
    }

    @WorkerThread
    private void purgeOutput() {
        if (output.exists()) {
            final boolean deleteResult = output.delete();

            if (!deleteResult) {
                log("could not delete the file in purgeOutput");
            }
        }
    }

    @WorkerThread
    private void postProgress(final long downloaded, final long total) {
        handler.post(() -> {
            for (FileCacheListener callback : listeners) {
                callback.onProgress(downloaded, total);
            }
        });
    }

    private static class CancelException extends IOException {
    }

    private static class HttpCodeIOException extends IOException {
        private int code;

        public HttpCodeIOException(int code) {
            this.code = code;
        }
    }

    public interface Callback {
        void downloaderFinished(FileCacheDownloader fileCacheDownloader);

        void downloaderAddedFile(long fileLen);
    }
}

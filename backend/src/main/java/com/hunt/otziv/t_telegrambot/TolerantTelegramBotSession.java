package com.hunt.otziv.t_telegrambot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.telegram.telegrambots.Constants;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.facilities.TelegramHttpClientBuilder;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.BackOff;
import org.telegram.telegrambots.meta.generics.BotOptions;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.meta.generics.LongPollingBot;
import org.telegram.telegrambots.updatesreceivers.ExponentialBackOff;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TolerantTelegramBotSession implements BotSession {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentLinkedDeque<Update> receivedUpdates = new ConcurrentLinkedDeque<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReaderThread readerThread;
    private HandlerThread handlerThread;
    private LongPollingBot callback;
    private String token;
    private int lastReceivedUpdate = 0;
    private DefaultBotOptions options;

    @Override
    public synchronized void start() {
        if (running.get()) {
            throw new IllegalStateException("Session already running");
        }

        running.set(true);
        lastReceivedUpdate = 0;

        readerThread = new ReaderThread();
        readerThread.setName(callback.getBotUsername() + " Telegram Connection");
        readerThread.start();

        handlerThread = new HandlerThread();
        handlerThread.setName(callback.getBotUsername() + " Telegram Executor");
        handlerThread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running.get()) {
            throw new IllegalStateException("Session already stopped");
        }

        running.set(false);
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (handlerThread != null) {
            handlerThread.interrupt();
        }
        if (callback != null) {
            callback.onClosing();
        }
    }

    @Override
    public void setOptions(BotOptions options) {
        if (this.options != null) {
            throw new InvalidParameterException("BotOptions has already been set");
        }
        this.options = (DefaultBotOptions) options;
    }

    @Override
    public void setToken(String token) {
        if (this.token != null) {
            throw new InvalidParameterException("Token has already been set");
        }
        this.token = token;
    }

    @Override
    public void setCallback(LongPollingBot callback) {
        if (this.callback != null) {
            throw new InvalidParameterException("Callback has already been set");
        }
        this.callback = callback;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private List<Update> drainUpdates() {
        List<Update> updates = new ArrayList<>();
        for (Iterator<Update> iterator = receivedUpdates.iterator(); iterator.hasNext(); ) {
            updates.add(iterator.next());
            iterator.remove();
        }
        return updates;
    }

    private final class ReaderThread extends Thread {
        private CloseableHttpClient httpClient;
        private BackOff backOff;
        private RequestConfig requestConfig;

        @Override
        public synchronized void start() {
            httpClient = TelegramHttpClientBuilder.build(options);
            requestConfig = options.getRequestConfig();
            backOff = options.getBackOff();
            if (backOff == null) {
                backOff = new ExponentialBackOff.Builder().build();
            }
            int pollingSocketTimeoutMillis = Math.max(
                    Constants.SOCKET_TIMEOUT,
                    (options.getGetUpdatesTimeout() + 15) * 1000
            );
            if (requestConfig == null) {
                requestConfig = RequestConfig.copy(RequestConfig.custom().build())
                        .setSocketTimeout(pollingSocketTimeoutMillis)
                        .setConnectTimeout(Constants.SOCKET_TIMEOUT)
                        .setConnectionRequestTimeout(Constants.SOCKET_TIMEOUT)
                        .build();
            } else {
                requestConfig = RequestConfig.copy(requestConfig)
                        .setSocketTimeout(pollingSocketTimeoutMillis)
                        .build();
            }
            super.start();
        }

        @Override
        public void interrupt() {
            if (httpClient != null) {
                try {
                    httpClient.close();
                } catch (IOException e) {
                    log.debug("Telegram polling HTTP client close failed: {}", concise(e), e);
                }
            }
            super.interrupt();
        }

        @Override
        public void run() {
            setPriority(Thread.MIN_PRIORITY);
            while (running.get()) {
                try {
                    List<Update> updates = getUpdatesFromServer();
                    if (updates.isEmpty()) {
                        Thread.sleep(500);
                    } else {
                        updates.removeIf(update -> update.getUpdateId() < lastReceivedUpdate);
                        lastReceivedUpdate = updates.stream()
                                .map(Update::getUpdateId)
                                .max(Integer::compareTo)
                                .orElse(0);
                        receivedUpdates.addAll(updates);
                        synchronized (receivedUpdates) {
                            receivedUpdates.notifyAll();
                        }
                    }
                } catch (InterruptedException e) {
                    if (!running.get()) {
                        receivedUpdates.clear();
                    }
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    long waitMillis = backOff.nextBackOffMillis();
                    if (isTransientPollingException(e)) {
                        log.warn("Telegram polling temporarily unavailable: {}. Retry in {} ms", concise(e), waitMillis);
                        log.debug("Telegram polling transient exception", e);
                    } else {
                        log.error("Telegram polling failed: {}", concise(e), e);
                    }
                    sleepBeforeRetry(waitMillis);
                }
            }
            log.debug("Telegram polling reader thread closed");
        }

        private List<Update> getUpdatesFromServer() throws Exception {
            GetUpdates request = GetUpdates.builder()
                    .limit(options.getGetUpdatesLimit())
                    .timeout(options.getGetUpdatesTimeout())
                    .offset(lastReceivedUpdate + 1)
                    .build();

            if (options.getAllowedUpdates() != null) {
                request.setAllowedUpdates(options.getAllowedUpdates());
            }

            HttpPost httpPost = new HttpPost(options.getBaseUrl() + token + "/" + GetUpdates.PATH);
            httpPost.addHeader("charset", StandardCharsets.UTF_8.name());
            httpPost.setConfig(requestConfig);
            httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(request), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = httpClient.execute(httpPost, options.getHttpContext())) {
                String responseContent = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (response.getStatusLine().getStatusCode() >= 500) {
                    log.warn("Telegram polling server response {}: {}",
                            response.getStatusLine().getStatusCode(), responseContent);
                    return Collections.emptyList();
                }

                List<Update> updates = request.deserializeResponse(responseContent);
                backOff.reset();
                return updates;
            } catch (TelegramApiRequestException e) {
                if (Integer.valueOf(409).equals(e.getErrorCode())) {
                    throw new TransientTelegramPollingException("another getUpdates request is active", e);
                }
                throw e;
            } catch (InternalError e) {
                if (e.getCause() instanceof InvocationTargetException invocationTargetException) {
                    Throwable cause = invocationTargetException.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new RuntimeException(cause);
                }
                throw e;
            }
        }

        private void sleepBeforeRetry(long waitMillis) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                if (!running.get()) {
                    receivedUpdates.clear();
                }
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class HandlerThread extends Thread {
        @Override
        public void run() {
            setPriority(Thread.MIN_PRIORITY);
            while (running.get()) {
                try {
                    List<Update> updates = drainUpdates();
                    if (updates.isEmpty()) {
                        synchronized (receivedUpdates) {
                            receivedUpdates.wait();
                        }
                        updates = drainUpdates();
                    }
                    if (!updates.isEmpty()) {
                        callback.onUpdatesReceived(updates);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.error("Telegram update handler failed: {}", concise(e), e);
                }
            }
            log.debug("Telegram update handler thread closed");
        }
    }

    private static boolean isTransientPollingException(Throwable throwable) {
        return throwable instanceof TransientTelegramPollingException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectTimeoutException
                || throwable instanceof NoHttpResponseException
                || throwable instanceof SocketException
                || throwable instanceof SSLException
                || throwable instanceof InvalidObjectException;
    }

    private static String concise(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static final class TransientTelegramPollingException extends Exception {
        private TransientTelegramPollingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

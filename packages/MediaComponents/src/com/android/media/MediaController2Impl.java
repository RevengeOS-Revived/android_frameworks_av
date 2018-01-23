/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.IMediaSession2;
import android.media.IMediaSession2Callback;
import android.media.MediaController2;
import android.media.MediaController2.ControllerCallback;
import android.media.MediaPlayerBase;
import android.media.MediaSessionService2;
import android.media.SessionToken;
import android.media.session.PlaybackState;
import android.media.update.MediaController2Provider;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.GuardedBy;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class MediaController2Impl implements MediaController2Provider {
    private static final String TAG = "MediaController2";
    private static final boolean DEBUG = true; // TODO(jaewan): Change

    private final MediaController2 mInstance;

    /**
     * Flag used by MediaController2Record to filter playback callback.
     */
    static final int CALLBACK_FLAG_PLAYBACK = 0x1;

    static final int REQUEST_CODE_ALL = 0;

    private final Object mLock = new Object();

    private final Context mContext;
    private final MediaSession2CallbackStub mSessionCallbackStub;
    private final SessionToken mToken;
    private final ControllerCallback mCallback;
    private final Executor mCallbackExecutor;
    private final IBinder.DeathRecipient mDeathRecipient;

    @GuardedBy("mLock")
    private final List<PlaybackListenerHolder> mPlaybackListeners = new ArrayList<>();
    @GuardedBy("mLock")
    private SessionServiceConnection mServiceConnection;
    @GuardedBy("mLock")
    private boolean mIsReleased;

    // Assignment should be used with the lock hold, but should be used without a lock to prevent
    // potential deadlock.
    // Postfix -Binder is added to explicitly show that it's potentially remote process call.
    // Technically -Interface is more correct, but it may misread that it's interface (vs class)
    // so let's keep this postfix until we find better postfix.
    @GuardedBy("mLock")
    private volatile IMediaSession2 mSessionBinder;

    // TODO(jaewan): Require session activeness changed listener, because controller can be
    //               available when the session's player is null.
    public MediaController2Impl(MediaController2 instance, Context context, SessionToken token,
            ControllerCallback callback, Executor executor) {
        mInstance = instance;

        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor shouldn't be null");
        }
        mContext = context;
        mSessionCallbackStub = new MediaSession2CallbackStub(this);
        mToken = token;
        mCallback = callback;
        mCallbackExecutor = executor;
        mDeathRecipient = () -> {
            mInstance.release();
        };

        mSessionBinder = null;

        if (token.getSessionBinder() == null) {
            mServiceConnection = new SessionServiceConnection();
            connectToService();
        } else {
            mServiceConnection = null;
            connectToSession(token.getSessionBinder());
        }
    }

    // Should be only called by constructor.
    private void connectToService() {
        // Service. Needs to get fresh binder whenever connection is needed.
        final Intent intent = new Intent(MediaSessionService2.SERVICE_INTERFACE);
        intent.setClassName(mToken.getPackageName(), mToken.getServiceName());

        // Use bindService() instead of startForegroundService() to start session service for three
        // reasons.
        // 1. Prevent session service owner's stopSelf() from destroying service.
        //    With the startForegroundService(), service's call of stopSelf() will trigger immediate
        //    onDestroy() calls on the main thread even when onConnect() is running in another
        //    thread.
        // 2. Minimize APIs for developers to take care about.
        //    With bindService(), developers only need to take care about Service.onBind()
        //    but Service.onStartCommand() should be also taken care about with the
        //    startForegroundService().
        // 3. Future support for UI-less playback
        //    If a service wants to keep running, it should be either foreground service or
        //    bounded service. But there had been request for the feature for system apps
        //    and using bindService() will be better fit with it.
        // TODO(jaewan): Use bindServiceAsUser()??
        boolean result = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!result) {
            Log.w(TAG, "bind to " + mToken + " failed");
        } else if (DEBUG) {
            Log.d(TAG, "bind to " + mToken + " success");
        }
    }

    private void connectToSession(IMediaSession2 sessionBinder) {
        try {
            sessionBinder.connect(mContext.getPackageName(), mSessionCallbackStub);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call connection request. Framework will retry"
                    + " automatically");
        }
    }

    @Override
    public void release_impl() {
        final IMediaSession2 binder;
        synchronized (mLock) {
            if (mIsReleased) {
                // Prevent re-enterance from the ControllerCallback.onDisconnected()
                return;
            }
            mIsReleased = true;
            if (mServiceConnection != null) {
                mContext.unbindService(mServiceConnection);
                mServiceConnection = null;
            }
            mPlaybackListeners.clear();
            binder = mSessionBinder;
            mSessionBinder = null;
            mSessionCallbackStub.destroy();
        }
        if (binder != null) {
            try {
                binder.asBinder().unlinkToDeath(mDeathRecipient, 0);
                binder.release(mSessionCallbackStub);
            } catch (RemoteException e) {
                // No-op.
            }
        }
        mCallbackExecutor.execute(() -> {
            mCallback.onDisconnected();
        });
    }

    @Override
    public SessionToken getSessionToken_impl() {
        return mToken;
    }

    @Override
    public boolean isConnected_impl() {
        final IMediaSession2 binder = mSessionBinder;
        return binder != null;
    }

    @Override
    public void play_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.play(mSessionCallbackStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void pause_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.pause(mSessionCallbackStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void stop_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.stop(mSessionCallbackStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void skipToPrevious_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.skipToPrevious(mSessionCallbackStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void skipToNext_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.skipToNext(mSessionCallbackStub);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public PlaybackState getPlaybackState_impl() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                return binder.getPlaybackState();
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
        // TODO(jaewan): What to return for error case?
        return null;
    }

    @Override
    public void addPlaybackListener_impl(
            MediaPlayerBase.PlaybackListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener shouldn't be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler shouldn't be null");
        }
        boolean registerCallback;
        synchronized (mLock) {
            if (PlaybackListenerHolder.contains(mPlaybackListeners, listener)) {
                throw new IllegalArgumentException("listener is already added. Ignoring.");
            }
            registerCallback = mPlaybackListeners.isEmpty();
            mPlaybackListeners.add(new PlaybackListenerHolder(listener, handler));
        }
        if (registerCallback) {
            registerCallbackForPlaybackNotLocked();
        }
    }

    @Override
    public void removePlaybackListener_impl(MediaPlayerBase.PlaybackListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener shouldn't be null");
        }
        boolean unregisterCallback;
        synchronized (mLock) {
            int idx = PlaybackListenerHolder.indexOf(mPlaybackListeners, listener);
            if (idx >= 0) {
                mPlaybackListeners.get(idx).removeCallbacksAndMessages(null);
                mPlaybackListeners.remove(idx);
            }
            unregisterCallback = mPlaybackListeners.isEmpty();
        }
        if (unregisterCallback) {
            final IMediaSession2 binder = mSessionBinder;
            if (binder != null) {
                // Lazy unregister
                try {
                    binder.unregisterCallback(mSessionCallbackStub, CALLBACK_FLAG_PLAYBACK);
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot connect to the service or the session is gone", e);
                }
            }
        }
    }

    ///////////////////////////////////////////////////
    // Protected or private methods
    ///////////////////////////////////////////////////
    // Should be used without a lock to prevent potential deadlock.
    private void registerCallbackForPlaybackNotLocked() {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.registerCallback(mSessionCallbackStub,
                        CALLBACK_FLAG_PLAYBACK, REQUEST_CODE_ALL);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot connect to the service or the session is gone", e);
            }
        }
    }

    private void pushPlaybackStateChanges(final PlaybackState state) {
        synchronized (mLock) {
            for (int i = 0; i < mPlaybackListeners.size(); i++) {
                mPlaybackListeners.get(i).postPlaybackChange(state);
            }
        }
    }

    // Called when the result for connecting to the session was delivered.
    // Should be used without a lock to prevent potential deadlock.
    private void onConnectionChangedNotLocked(IMediaSession2 sessionBinder, long commands) {
        if (DEBUG) {
            Log.d(TAG, "onConnectionChangedNotLocked sessionBinder=" + sessionBinder);
        }
        boolean release = false;
        try {
            if (sessionBinder == null) {
                // Connection rejected.
                release = true;
                return;
            }
            boolean registerCallbackForPlaybackNeeded;
            synchronized (mLock) {
                if (mIsReleased) {
                    return;
                }
                if (mSessionBinder != null) {
                    Log.e(TAG, "Cannot be notified about the connection result many times."
                            + " Probably a bug or malicious app.");
                    release = true;
                    return;
                }
                mSessionBinder = sessionBinder;
                try {
                    // Implementation for the local binder is no-op,
                    // so can be used without worrying about deadlock.
                    mSessionBinder.asBinder().linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    release = true;
                    return;
                }
                registerCallbackForPlaybackNeeded = !mPlaybackListeners.isEmpty();
            }
            // TODO(jaewan): Keep commands to prevents illegal API calls.
            mCallbackExecutor.execute(() -> {
                mCallback.onConnected(commands);
            });
            if (registerCallbackForPlaybackNeeded) {
                registerCallbackForPlaybackNotLocked();
            }
        } finally {
            if (release) {
                // Trick to call release() without holding the lock, to prevent potential deadlock
                // with the developer's custom lock within the ControllerCallback.onDisconnected().
                mInstance.release();
            }
        }
    }

    private static class MediaSession2CallbackStub extends IMediaSession2Callback.Stub {
        private final WeakReference<MediaController2Impl> mController;

        private MediaSession2CallbackStub(MediaController2Impl controller) {
            mController = new WeakReference<>(controller);
        }

        private MediaController2Impl getController() throws IllegalStateException {
            final MediaController2Impl controller = mController.get();
            if (controller == null) {
                throw new IllegalStateException("Controller is released");
            }
            return controller;
        }

        public void destroy() {
            mController.clear();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) throws RuntimeException {
            final MediaController2Impl controller = getController();
            controller.pushPlaybackStateChanges(state);
        }

        @Override
        public void onConnectionChanged(IMediaSession2 sessionBinder, long commands)
                throws RuntimeException {
            final MediaController2Impl controller;
            try {
                controller = getController();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Don't fail silently here. Highly likely a bug");
                return;
            }
            controller.onConnectionChangedNotLocked(sessionBinder, commands);
        }
    }

    // This will be called on the main thread.
    private class SessionServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Note that it's always main-thread.
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected " + name + " " + this);
            }
            // Sanity check
            if (!mToken.getPackageName().equals(name.getPackageName())) {
                Log.wtf(TAG, name + " was connected, but expected pkg="
                        + mToken.getPackageName() + " with id=" + mToken.getId());
                return;
            }
            final IMediaSession2 sessionBinder = IMediaSession2.Stub.asInterface(service);
            connectToSession(sessionBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Temporal lose of the binding because of the service crash. System will automatically
            // rebind, so just no-op.
            // TODO(jaewan): Really? Either disconnect cleanly or
            if (DEBUG) {
                Log.w(TAG, "Session service " + name + " is disconnected.");
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Permanent lose of the binding because of the service package update or removed.
            // This SessionServiceRecord will be removed accordingly, but forget session binder here
            // for sure.
            mInstance.release();
        }
    }
}

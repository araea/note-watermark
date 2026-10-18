package com.jy.notewatermark;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * The settings screen's line to the framework itself, which is the one channel that does
 * not depend on a notes app being awake. ColorOS refuses to start another app's process
 * for a content provider, so a probe sent to a notes app that is not running goes
 * unanswered; the framework, though, keeps the module's settings (remote preferences),
 * knows its scope and sees which target processes are running with the module loaded.
 *
 * The framework hands this process a binder when it starts, if the module is enabled.
 * Everything here is quiet when there is none: the screen then falls back to probing.
 */
final class Remote {
    /** The preference group the hook reads; keys are the ones in {@link ConfigContract}. */
    static final String GROUP = ConfigContract.REMOTE_GROUP;

    interface Listener {
        void onChanged();
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static volatile XposedService service;
    private static boolean registered;

    private Remote() {
    }

    /** Safe to call from every screen start; the framework allows one listener, so there is one. */
    static synchronized void init() {
        if (registered) return;
        registered = true;
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService bound) {
                    service = bound;
                    changed();
                }

                @Override
                public void onServiceDied(XposedService died) {
                    if (service == died) service = null;
                    changed();
                }
            });
        } catch (Throwable t) {
            registered = false;
        }
    }

    static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    static void unlisten(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void changed() {
        for (Listener listener : LISTENERS) listener.onChanged();
    }

    static boolean bound() {
        return service != null;
    }

    /** The packages the framework applies the module to, or null when it cannot be asked. Blocking. */
    static List<String> scope() {
        XposedService bound = service;
        if (bound == null) return null;
        try {
            return bound.getScope();
        } catch (Throwable t) {
            return null;
        }
    }

    /** What the framework says about a running, module-loaded process of this app. */
    static final class Running {
        final boolean any;
        final boolean stale;

        Running(boolean any, boolean stale) {
            this.any = any;
            this.stale = stale;
        }
    }

    /**
     * Whether a process of the app is up with the module loaded. Null when the framework
     * does not say (older than API 102, or no binder), which callers treat as "unknown".
     */
    static Running running(NotesApp app) {
        XposedService bound = service;
        if (bound == null) return null;
        try {
            if (bound.getApiVersion() < XposedService.API_102) return null;
            boolean any = false, stale = false;
            for (HookedTarget target : bound.getRunningTargets()) {
                String name = target.getProcessName();
                if (!name.equals(app.pkg) && !name.startsWith(app.pkg + ":")) continue;
                any = true;
                stale |= target.getState() != HookedTarget.State.UP_TO_DATE;
            }
            return new Running(any, stale);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Asks the framework (and through it the user) to add apps to the module's scope. Blocking until handed over. */
    static void requestScope(List<String> packages, final Runnable done) {
        XposedService bound = service;
        if (bound == null) return;
        try {
            bound.requestScope(new ArrayList<String>(packages), new XposedService.OnScopeEventListener() {
                @Override
                public void onScopeRequestApproved(List<String> approved) {
                    done.run();
                }

                @Override
                public void onScopeRequestFailed(String message) {
                    done.run();
                }
            });
        } catch (Throwable t) {
            // the framework cannot take the request; the status keeps asking for it
        }
    }

    /** Hands the footer settings to the framework, where every hooked process can read them. */
    static void publishSettings(String text, boolean keepBlank) {
        XposedService bound = service;
        if (bound == null) return;
        try {
            bound.getRemotePreferences(GROUP).edit()
                    .putString(ConfigContract.KEY_WATERMARK_TEXT, text)
                    .putBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, keepBlank)
                    .apply();
        } catch (Throwable t) {
            // not every framework keeps remote preferences
        }
    }

    /** Leaves an export request for the app to find when it next starts. */
    static void publishExportRequest(NotesApp app, long time) {
        XposedService bound = service;
        if (bound == null) return;
        try {
            bound.getRemotePreferences(GROUP).edit()
                    .putString(ConfigContract.KEY_EXPORT_TARGET, app.pkg)
                    .putLong(ConfigContract.KEY_EXPORT_REQUEST, time)
                    .apply();
        } catch (Throwable t) {
            // the module provider remains the other way to carry it
        }
    }

    static SharedPreferences remotePreferences() {
        XposedService bound = service;
        if (bound == null) return null;
        try {
            return bound.getRemotePreferences(GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    static List<String> none() {
        return Collections.emptyList();
    }
}

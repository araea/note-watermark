package com.jy.notewatermark;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLException;
import org.json.JSONException;

/**
 * Keeps this module up to date from the module repository: look up the newest release,
 * download it, make sure it really is this app signed by the same key, and hand it to
 * the system installer, which asks the user once and replaces the running app.
 *
 * One per process, so an update keeps going while the screen rotates or closes. All work
 * runs on one background thread, which is also the only place the state changes; the
 * screen draws immutable snapshots, always delivered on the main thread.
 *
 * The downloaded APK is kept until the installed version catches up, so declining the
 * system dialog and tapping 「更新」 again installs at once instead of downloading twice.
 */
final class Updater {
    interface Listener { void changed(State state); }

    /** Where an update is: nothing running, downloading, waiting for the install permission, installing. */
    static final int IDLE = 0, DOWNLOADING = 1, PERMISSION = 2, INSTALLING = 3;

    /** Everything the screen needs to draw the update card and the version row. */
    static final class State {
        /** The newest release the repository listed at the last successful check; may be null. */
        final Release release;
        /** That release is newer than this app. */
        final boolean newer;
        final boolean checking;
        /** When the last check succeeded; 0 = never. */
        final long checkedAt;
        final String checkError;
        final int phase;
        final long done, total;
        /** Why the last update attempt stopped; empty when it did not fail. */
        final String failure;
        /** A gentle word about the last attempt, e.g. that the install was cancelled. */
        final String note;
        /** The system's confirmation screen, waiting for the settings screen to show it. */
        final Intent confirm;

        State(Release release, boolean newer, boolean checking, long checkedAt, String checkError, int phase,
              long done, long total, String failure, String note, Intent confirm) {
            this.release = release;
            this.newer = newer;
            this.checking = checking;
            this.checkedAt = checkedAt;
            this.checkError = checkError;
            this.phase = phase;
            this.done = done;
            this.total = total;
            this.failure = failure;
            this.note = note;
            this.confirm = confirm;
        }
    }

    static final String PREFS = "update";
    static final String ACTION_STATUS = "com.jy.notewatermark.action.INSTALL_STATUS";
    /** A successful look is good for an hour; a failed one is retried after ten minutes. */
    private static final long FRESH_MS = 60L * 60 * 1000, RETRY_MS = 10L * 60 * 1000;
    private static final int CONNECT_MS = 10_000, READ_MS = 20_000;
    /** The repository page is about 30 KB; anything far bigger is not that page. */
    private static final int PAGE_LIMIT = 2 * 1024 * 1024;

    private static Updater instance;

    static synchronized Updater get(Context context) {
        if (instance == null) instance = new Updater(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long installed;
    private final String agent;

    // Changed on the io thread only.
    private Release release;
    private boolean checking, lastFailed;
    private long checkedAt, attemptedAt;
    private String checkError = "", failure = "", note = "";
    private int phase = IDLE;
    private long done, total;
    private Intent confirm;
    private File ready;
    private volatile boolean cancelled;
    private volatile State current;

    private Updater(Context context) {
        app = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long code = 0;
        String name = "";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            code = versionCode(info);
            name = info.versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            // It is this app.
        }
        installed = code;
        agent = "NoteWatermark/" + name + " (Android " + Build.VERSION.RELEASE + ")";
        release = Release.fromJson(prefs.getString("release", "{}"));
        checkedAt = prefs.getLong("checked", 0);
        current = snapshot();
        io.execute(() -> {
            // A session left behind by a process that was killed mid-install would only take space.
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            for (PackageInstaller.SessionInfo session : installer.getMySessions()) {
                try { installer.abandonSession(session.getSessionId()); } catch (RuntimeException gone) { }
            }
            tidy();
            publish();
        });
    }

    // ------------------------------------------------------------- subscribing

    State state() { return current; }

    void listen(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.changed(current);
    }

    void unlisten(Listener listener) { listeners.remove(listener); }

    // ------------------------------------------------------------- preferences

    /** Checking on open and once a day in the background; on unless the user turns it off. */
    boolean auto() { return prefs.getBoolean("auto", true); }

    void setAuto(boolean on) {
        prefs.edit().putBoolean("auto", on).apply();
        UpdateJob.schedule(app, on);
    }

    /** Android 13+ asks before showing notifications: asked once, when the first new version turns up. */
    boolean askNotifications() { return auto() && !prefs.getBoolean("asked", false); }

    void markAsked() { prefs.edit().putBoolean("asked", true).apply(); }

    boolean notified(String tag) { return tag.equals(prefs.getString("notified", "")); }

    void markNotified(String tag) { prefs.edit().putString("notified", tag).apply(); }

    /** The version this app was just updated to from the screen, said once on the next open. */
    String takeJust() {
        String version = prefs.getString("just", "");
        if (!version.isEmpty()) prefs.edit().remove("just").apply();
        return version;
    }

    /** The new copy of this app is running: if the screen asked for it, remember to say so. */
    String replaced() {
        long pending = prefs.getLong("pending", -1);
        String version = prefs.getString("pendingVersion", "");
        SharedPreferences.Editor edit = prefs.edit().remove("pending").remove("pendingVersion");
        boolean ours = pending == installed && !version.isEmpty();
        if (ours) edit.putString("just", version);
        edit.commit();
        return ours ? version : "";
    }

    /** The system may have brought the screen back before {@link #replaced} ran: tell it now. */
    void announce() { io.execute(this::publish); }

    // ------------------------------------------------------------------ checks

    /** Looks the release up; without {@code force}, only when the last look is stale and checking is on. */
    void check(boolean force) {
        io.execute(() -> {
            if (!force) {
                if (!auto()) return;
                long since = System.currentTimeMillis() - Math.max(attemptedAt, checkedAt);
                if (since < (lastFailed ? RETRY_MS : FRESH_MS)) return;
            }
            fetch();
        });
    }

    /** For the background job: look, then run {@code done} on the io thread. */
    void checkThen(Runnable done) {
        io.execute(() -> {
            fetch();
            done.run();
        });
    }

    private void fetch() {
        checking = true;
        publish();
        attemptedAt = System.currentTimeMillis();
        try {
            Release found;
            try {
                found = Release.parse(text(Release.SOURCE));
            } catch (IOException | JSONException | RuntimeException primary) {
                // The repository page sits behind a CDN that now and then turns callers away.
                try {
                    found = Release.parseGitHub(text(Release.FALLBACK));
                } catch (IOException | JSONException | RuntimeException secondary) {
                    throw primary;
                }
            }
            if (found == null) throw new Explained(app.getString(R.string.update_error_none));
            // What went wrong with another release says nothing about this one.
            if (release == null || !release.tag.equals(found.tag)) failure = note = "";
            release = found;
            checkedAt = System.currentTimeMillis();
            checkError = "";
            lastFailed = false;
            prefs.edit().putString("release", found.toJson()).putLong("checked", checkedAt).apply();
            tidy();
        } catch (IOException error) {
            checkError = error.getMessage();
            lastFailed = true;
        } catch (JSONException | RuntimeException error) {
            checkError = app.getString(R.string.update_error_page);
            lastFailed = true;
        }
        checking = false;
        publish();
    }

    private boolean newer() { return release != null && release.code > installed; }

    /** Nothing newer to install: drop what an earlier attempt left behind. */
    private void tidy() {
        if (phase != IDLE) return;
        if (!newer()) failure = note = "";
        File[] files = dir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!newer() || !file.equals(apk(release))) file.delete();
        }
    }

    private State snapshot() {
        return new State(release, newer(), checking, checkedAt, checkError, phase, done, total,
                failure, note, confirm);
    }

    private void publish() {
        final State state = snapshot();
        current = state;
        main.post(() -> {
            for (Listener listener : listeners) listener.changed(state);
        });
    }

    // ------------------------------------------------------------------ update

    /** Downloads (unless already done) and installs the newer release; again from wherever it stopped. */
    void update() {
        io.execute(() -> {
            if (!newer() || phase == DOWNLOADING) return;
            failure = note = "";
            confirm = null;
            cancelled = false;
            try {
                File apk = apk(release);
                if (!usable(apk, release)) download(release, apk);
                ready = apk;
                install();
            } catch (IOException error) {
                phase = IDLE;
                if (cancelled) note = app.getString(R.string.update_cancelled);
                else failure = error.getMessage();
            }
            cancelled = false;
            publish();
        });
    }

    /** Stops a download in progress; picked up by the download loop. */
    void cancel() { cancelled = true; }

    /** The screen is back: the permission may have been granted in the meantime. */
    void resume() {
        io.execute(() -> {
            if (phase != PERMISSION || !canInstall()) return;
            try {
                install();
            } catch (IOException error) {
                phase = IDLE;
                failure = error.getMessage();
            }
            publish();
        });
    }

    /** The screen showed the system's confirmation; it reports back through {@link #status}. */
    void confirmShown() {
        io.execute(() -> {
            confirm = null;
            publish();
        });
    }

    /** What the system installer reported for the session. */
    void status(int status, String message, Intent next) {
        io.execute(() -> {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                confirm = next;
                publish();
                return;
            }
            phase = IDLE;
            confirm = null;
            prefs.edit().remove("pending").remove("pendingVersion").apply();
            if (status == PackageInstaller.STATUS_SUCCESS) {
                // Normally this process is gone before it hears this; the next one says it.
                tidy();
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                note = app.getString(R.string.update_cancelled);
            } else {
                failure = installError(status, message);
            }
            publish();
        });
    }

    /** The settings page where the user lets this app install apps; null when no longer needed. */
    Intent permissionIntent() {
        if (canInstall()) return null;
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()));
    }

    private boolean canInstall() {
        return app.getPackageManager().canRequestPackageInstalls();
    }

    private void install() throws IOException {
        if (!canInstall()) {
            phase = PERMISSION;
            publish();
            return;
        }
        phase = INSTALLING;
        publish();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo old : installer.getMySessions()) {
            try { installer.abandonSession(old.getSessionId()); } catch (RuntimeException gone) { }
        }
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(ready.length());
        // Lets the system skip the dialog where it allows it (an app updating itself after
        // installing itself once); everywhere else it simply asks as usual.
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id;
        try {
            id = installer.createSession(params);
        } catch (IOException | RuntimeException error) {
            throw new Explained(app.getString(R.string.update_error_session));
        }
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(ready);
                 OutputStream out = session.openWrite("base.apk", 0, ready.length())) {
                copy(in, out);
                session.fsync(out);
            }
            prefs.edit().putLong("pending", release.code).putString("pendingVersion", release.version).commit();
            Intent intent = new Intent(ACTION_STATUS).setClass(app, UpdateReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).getIntentSender());
        } catch (IOException | RuntimeException error) {
            try { installer.abandonSession(id); } catch (RuntimeException gone) { }
            prefs.edit().remove("pending").remove("pendingVersion").apply();
            throw new Explained(app.getString(R.string.update_error_session));
        }
    }

    private String installError(int status, String message) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return app.getString(R.string.update_error_conflict);
            case PackageInstaller.STATUS_FAILURE_STORAGE: return app.getString(R.string.update_error_storage);
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return app.getString(R.string.update_error_incompatible);
            case PackageInstaller.STATUS_FAILURE_INVALID: return app.getString(R.string.update_error_invalid);
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return app.getString(R.string.update_error_blocked);
            default:
                return message == null || message.isEmpty() ? app.getString(R.string.update_error_install)
                        : app.getString(R.string.update_error_install_detail, message);
        }
    }

    // --------------------------------------------------------------- download

    private File dir() { return new File(app.getCacheDir(), "update"); }

    private File apk(Release release) { return new File(dir(), "NoteWatermark-" + release.code + ".apk"); }

    /** A finished earlier download that still checks out. */
    private boolean usable(File apk, Release release) {
        if (!apk.isFile() || apk.length() != release.size) return false;
        try {
            verify(apk, release);
            return true;
        } catch (IOException spoiled) {
            apk.delete();
            return false;
        }
    }

    /** The repository's mirror first, GitHub if the mirror fails; verified before it counts. */
    private void download(Release release, File apk) throws IOException {
        File folder = dir();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new Explained(app.getString(R.string.update_error_storage));
        File part = new File(folder, apk.getName() + ".part");
        phase = DOWNLOADING;
        done = 0;
        total = release.size;
        publish();
        IOException last = null;
        for (String url : new String[] { release.url, release.fallback }) {
            if (url.isEmpty()) continue;
            try {
                fetchTo(url, part, release.size);
                if (!part.renameTo(apk)) throw new Explained(app.getString(R.string.update_error_storage));
                try {
                    verify(apk, release);
                } catch (IOException wrong) {
                    apk.delete();
                    throw wrong;
                }
                return;
            } catch (IOException error) {
                part.delete();
                if (cancelled) throw error;
                last = error;
                done = 0;
                publish();
            }
        }
        throw last != null ? last : new Explained(app.getString(R.string.update_error_none));
    }

    private void fetchTo(String url, File part, long size) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] chunk = new byte[32 * 1024];
                long count = 0, shown = 0;
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (cancelled) throw new InterruptedIOException();
                    count += read;
                    if (count > size) throw new Explained(app.getString(R.string.update_error_mismatch));
                    out.write(chunk, 0, read);
                    long now = System.currentTimeMillis();
                    if (now - shown >= 120 || count == size) {
                        shown = now;
                        done = count;
                        publish();
                    }
                }
                if (count != size) throw new Explained(app.getString(R.string.update_error_incomplete));
            }
        } catch (IOException error) {
            throw explain(error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String text(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = open(url);
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        out.write(chunk, 0, read);
                        if (out.size() > PAGE_LIMIT) throw new Explained(app.getString(R.string.update_error_page));
                    }
                    return out.toString("UTF-8");
                }
            } catch (IOException error) {
                last = explain(error);
                // A missing or refused page will not appear on a second try.
                if (error instanceof Status && ((Status) error).code / 100 == 4) break;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last;
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_MS);
        connection.setReadTimeout(READ_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", agent);
        int code;
        try {
            code = connection.getResponseCode();
        } catch (IOException error) {
            connection.disconnect();
            throw error;
        }
        if (code / 100 != 2) {
            connection.disconnect();
            throw new Status(code);
        }
        return connection;
    }

    private static final class Status extends IOException {
        final int code;
        Status(int code) { super("HTTP " + code); this.code = code; }
    }

    /** Turns a network failure into a sentence a person can act on; one already in words passes through. */
    private IOException explain(IOException error) {
        if (error instanceof Explained) return error;
        String text;
        if (cancelled || error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)) {
            text = app.getString(R.string.update_cancelled);
        } else if (error instanceof Status) {
            text = app.getString(R.string.update_error_http, ((Status) error).code);
        } else if (error instanceof UnknownHostException) {
            text = app.getString(R.string.update_error_offline);
        } else if (error instanceof SocketTimeoutException) {
            text = app.getString(R.string.update_error_timeout);
        } else if (error instanceof SSLException) {
            text = app.getString(R.string.update_error_ssl);
        } else if (error.getMessage() != null && error.getMessage().codePoints().anyMatch(c -> c >= 0x2E80)) {
            text = error.getMessage();
        } else {
            text = app.getString(R.string.update_error_network);
        }
        return new Explained(text);
    }

    private static final class Explained extends IOException {
        Explained(String message) { super(message); }
    }

    // ------------------------------------------------------------------ verify

    /** Same package, the promised version, the same signing key: anything else is not ours to install. */
    private void verify(File apk, Release release) throws IOException {
        PackageManager manager = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null || !app.getPackageName().equals(archive.packageName)) {
            throw new Explained(app.getString(R.string.update_error_not_ours));
        }
        if (versionCode(archive) != release.code) throw new Explained(app.getString(R.string.update_error_mismatch));
        try {
            if (!signers(manager.getPackageInfo(app.getPackageName(), flags)).equals(signers(archive))) {
                throw new Explained(app.getString(R.string.update_error_signature));
            }
        } catch (PackageManager.NameNotFoundException impossible) {
            throw new Explained(app.getString(R.string.update_error_signature));
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) {
        Signature[] list = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
                : info.signatures;
        Set<String> keys = new HashSet<>();
        if (list != null) for (Signature signature : list) keys.add(signature.toCharsString());
        return keys;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
    }
}

package io.github.abd3lraouf.campopup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * provision.sh pipes the recorder's address and login here with {@code content write}, so they travel over
 * ADB's stdin and never appear on a command line or in a shell history. Write-only, one file name, and only
 * the shell can call it (WRITE_SECURE_SETTINGS on the provider). Once the file is closed its keys go into
 * private SharedPreferences and the file is deleted.
 */
public class ConfigProvider extends ContentProvider {
    private static final String NAME = "provision.txt";

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!mode.contains("w") || !NAME.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        final File file = new File(getContext().getNoBackupFilesDir(), NAME);
        try {
            return ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE,
                    new Handler(Looper.getMainLooper()),
                    e -> importFile(file, e == null));
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private void importFile(File file, boolean ok) {
        try {
            if (!ok) return;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            int n = Settings.apply(getContext(), Settings.parseProvision(text));
            Log.i(Settings.TAG, "provisioned " + n + " settings");
            CamService.reload(getContext());
        } catch (IOException | RuntimeException e) {
            Log.w(Settings.TAG, "provisioning failed: " + e.getClass().getSimpleName());
        } finally {
            if (!file.delete()) file.deleteOnExit();
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}

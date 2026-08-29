package com.jy.notewatermark;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Read-only bridge that exposes harmless module settings to the Notes process. */
public final class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        if (!ConfigContract.URI.getPath().equals(uri.getPath())) {
            throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        SharedPreferences prefs = getContext().getSharedPreferences(
                ConfigContract.PREFS, 0);
        MatrixCursor cursor = new MatrixCursor(new String[] {
                ConfigContract.COLUMN_WATERMARK_TEXT,
                ConfigContract.COLUMN_KEEP_BLANK_SPACE,
        });
        cursor.addRow(new Object[] {
                prefs.getString(ConfigContract.KEY_WATERMARK_TEXT, ""),
                prefs.getBoolean(ConfigContract.KEY_KEEP_BLANK_SPACE, true) ? 1 : 0,
        });
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + ConfigContract.AUTHORITY + ".config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }
}

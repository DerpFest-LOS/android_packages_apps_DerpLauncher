/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.launcher3.model;

import static android.provider.BaseColumns._ID;
import static android.util.Base64.NO_PADDING;
import static android.util.Base64.NO_WRAP;

import static com.android.launcher3.DefaultLayoutParser.RES_PARTNER_DEFAULT_LAYOUT;
import static com.android.launcher3.LauncherPrefs.DB_FILE;
import static com.android.launcher3.LauncherPrefs.NO_DB_FILES_RESTORED;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APP_PAIR;
import static com.android.launcher3.LauncherSettings.Favorites.TABLE_NAME;
import static com.android.launcher3.LauncherSettings.Favorites.addTableToDb;
import static com.android.launcher3.LauncherSettings.Settings.BLOB_KEY_PREFIX;
import static com.android.launcher3.LauncherSettings.Settings.LAYOUT_DIGEST_LABEL;
import static com.android.launcher3.LauncherSettings.Settings.LAYOUT_DIGEST_TAG;
import static com.android.launcher3.LauncherSettings.Settings.LAYOUT_PROVIDER_KEY;
import static com.android.launcher3.provider.LauncherDbUtils.tableExists;

import android.app.blob.BlobHandle;
import android.app.blob.BlobStoreManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.launcher3.AutoInstallsLayout;
import com.android.launcher3.AutoInstallsLayout.SourceResources;
import com.android.launcher3.ConstantItem;
import com.android.launcher3.DefaultLayoutParser;
import com.android.launcher3.EncryptionType;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherFiles;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.Utilities;
import com.android.launcher3.backuprestore.LauncherRestoreEventLogger;
import com.android.launcher3.backuprestore.LauncherRestoreEventLogger.RestoreError;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.provider.LauncherDbUtils;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.provider.RestoreDbTask;
import com.android.launcher3.util.IOUtils;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.MainThreadInitializedObject.SandboxContext;
import com.android.launcher3.util.Partner;
import com.android.launcher3.widget.LauncherWidgetHolder;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.util.List;

/**
 * Utility class which maintains an instance of Launcher database and provides utility methods
 * around it.
 */
public class ModelDbController {
    private static final String TAG = "LauncherProvider";

    private static final String EMPTY_DATABASE_CREATED = "EMPTY_DATABASE_CREATED";
    public static final String EXTRA_DB_NAME = "db_name";
    public static final String DATA_TYPE_DB_FILE = "database_file";

    protected DatabaseHelper mOpenHelper;

    private final Context mContext;

    public ModelDbController(Context context) {
        mContext = context;
    }

    private void printDBs(String prefix) {
        try {
            File directory = new File(
                    mContext.getDatabasePath(InvariantDeviceProfile.INSTANCE.get(mContext).dbFile)
                            .getParent()
            );
            if (directory.exists()) {
                for (File file : directory.listFiles()) {
                    Log.d("b/353505773", prefix + "Database file: " + file.getName());
                }
            } else {
                Log.d("b/353505773", prefix + "No files found in the database directory");
            }
        } catch (Exception e) {
            Log.e("b/353505773", prefix + e.getMessage());
        }
    }

    private synchronized void createDbIfNotExists() {
        if (mOpenHelper == null) {
            String dbFile = LauncherPrefs.get(mContext).get(DB_FILE);
            if (dbFile.isEmpty()) {
                dbFile = InvariantDeviceProfile.INSTANCE.get(mContext).dbFile;
            }
            mOpenHelper = createDatabaseHelper(false /* forMigration */, dbFile);
            printDBs("before: ");
            RestoreDbTask.restoreIfNeeded(mContext, this);
            printDBs("after: ");
        }
    }

    protected DatabaseHelper createDatabaseHelper(boolean forMigration, String dbFile) {
        boolean isSandbox = mContext instanceof SandboxContext;
        String dbName = isSandbox ? null : dbFile;

        // Set the flag for empty DB
        Runnable onEmptyDbCreateCallback = forMigration ? () -> { }
                : () -> LauncherPrefs.get(mContext).putSync(getEmptyDbCreatedKey(dbName).to(true));

        DatabaseHelper databaseHelper = new DatabaseHelper(mContext, dbName,
                this::getSerialNumberForUser, onEmptyDbCreateCallback);
        // Table creation sometimes fails silently, which leads to a crash loop.
        // This way, we will try to create a table every time after crash, so the device
        // would eventually be able to recover.
        if (!tableExists(databaseHelper.getReadableDatabase(), Favorites.TABLE_NAME)) {
            Log.e(TAG, "Tables are missing after onCreate has been called. Trying to recreate");
            // This operation is a no-op if the table already exists.
            addTableToDb(databaseHelper.getWritableDatabase(),
                    getSerialNumberForUser(Process.myUserHandle()),
                    true /* optional */);
        }
        databaseHelper.mHotseatRestoreTableExists = tableExists(
                databaseHelper.getReadableDatabase(), Favorites.HYBRID_HOTSEAT_BACKUP_TABLE);

        databaseHelper.initIds();
        return databaseHelper;
    }

    /**
     * Refer {@link SQLiteDatabase#query}
     */
    @WorkerThread
    public Cursor query(String table, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        createDbIfNotExists();
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor result = db.query(
                table, projection, selection, selectionArgs, null, null, sortOrder);

        final Bundle extra = new Bundle();
        extra.putString(EXTRA_DB_NAME, mOpenHelper.getDatabaseName());
        result.setExtras(extra);
        return result;
    }

    /**
     * Refer {@link SQLiteDatabase#insert(String, String, ContentValues)}
     */
    @WorkerThread
    public int insert(String table, ContentValues initialValues) {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        addModifiedTime(initialValues);
        int rowId = mOpenHelper.dbInsertAndCheck(db, table, initialValues);
        if (rowId >= 0) {
            onAddOrDeleteOp(db);
        }
        return rowId;
    }

    /**
     * Refer {@link SQLiteDatabase#delete(String, String, String[])}
     */
    @WorkerThread
    public int delete(String table, String selection, String[] selectionArgs) {
        createDbIfNotExists();
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count = db.delete(table, selection, selectionArgs);
        if (count > 0) {
            onAddOrDeleteOp(db);
        }
        return count;
    }

    /**
     * Refer {@link SQLiteDatabase#update(String, ContentValues, String, String[])}
     */
    @WorkerThread
    public int update(String table, ContentValues values,
            String selection, String[] selectionArgs) {
        createDbIfNotExists();

        addModifiedTime(values);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.update(table, values, selection, selectionArgs);
        return count;
    }

    /**
     * Clears a previously set flag corresponding to empty db creation
     */
    @WorkerThread
    public void clearEmptyDbFlag() {
        createDbIfNotExists();
        clearFlagEmptyDbCreated();
    }

    /**
     * Generates an id to be used for new item in the favorites table
     */
    @WorkerThread
    public int generateNewItemId() {
        createDbIfNotExists();
        return mOpenHelper.generateNewItemId();
    }

    /**
     * Generates an id to be used for new workspace screen
     */
    @WorkerThread
    public int getNewScreenId() {
        createDbIfNotExists();
        return mOpenHelper.getNewScreenId();
    }

    /**
     * Creates an empty DB clearing all existing data
     */
    @WorkerThread
    public void createEmptyDB() {
        createDbIfNotExists();
        mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
        LauncherPrefs.get(mContext).putSync(getEmptyDbCreatedKey().to(true));
    }

    /**
     * Removes any widget which are present in the framework, but not in out internal DB
     */
    @WorkerThread
    public void removeGhostWidgets() {
        createDbIfNotExists();
        mOpenHelper.removeGhostWidgets(mOpenHelper.getWritableDatabase());
    }

    /**
     * Returns a new {@link SQLiteTransaction}
     */
    @WorkerThread
    public SQLiteTransaction newTransaction() {
        createDbIfNotExists();
        return new SQLiteTransaction(mOpenHelper.getWritableDatabase());
    }

    /**
     * Refreshes the internal state corresponding to presence of hotseat table
     */
    @WorkerThread
    public void refreshHotseatRestoreTable() {
        createDbIfNotExists();
        mOpenHelper.mHotseatRestoreTableExists = tableExists(
                mOpenHelper.getReadableDatabase(), Favorites.HYBRID_HOTSEAT_BACKUP_TABLE);
    }


    /**
     * Resets the launcher DB if we should reset it.
     */
    public void resetLauncherDb(@Nullable LauncherRestoreEventLogger restoreEventLogger) {
        if (restoreEventLogger != null) {
            sendMetricsForFailedMigration(restoreEventLogger, getDb());
        }
        FileLog.d(TAG, "Migration failed: resetting launcher database");
        createEmptyDB();
        LauncherPrefs.get(mContext).putSync(
                getEmptyDbCreatedKey(mOpenHelper.getDatabaseName()).to(true));

        // Write the grid state to avoid another migration
        new DeviceGridState(LauncherAppState.getIDP(mContext)).writeToPrefs(mContext);
    }

    /**
     * Determines if we should reset the DB.
     */
    private boolean shouldResetDb() {
        if (isThereExistingDb()) {
            return true;
        }
        if (!isGridMigrationNecessary()) {
            return false;
        }
        if (isCurrentDbSameAsTarget()) {
            return true;
        }
        return false;
    }

    private boolean isThereExistingDb() {
        if (LauncherPrefs.get(mContext).get(getEmptyDbCreatedKey())) {
            // If we already have a new DB, ignore migration
            FileLog.d(TAG, "migrateGridIfNeeded: new DB already created, skipping migration");
            return true;
        }
        return false;
    }

    private boolean isGridMigrationNecessary() {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        if (GridSizeMigrationDBController.needsToMigrate(mContext, idp)) {
            return true;
        }
        FileLog.d(TAG, "migrateGridIfNeeded: no grid migration needed");
        return false;
    }

    private boolean isCurrentDbSameAsTarget() {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        String targetDbName = new DeviceGridState(idp).getDbFile();
        if (TextUtils.equals(targetDbName, mOpenHelper.getDatabaseName())) {
            FileLog.e(TAG, "migrateGridIfNeeded: target db is same as current: " + targetDbName);
            return true;
        }
        return false;
    }

    /**
     * Migrates the DB. If the migration failed, it clears the DB.
     */
    public void attemptMigrateDb(LauncherRestoreEventLogger restoreEventLogger) throws Exception {
        createDbIfNotExists();

        if (shouldResetDb()) {
            resetLauncherDb(restoreEventLogger);
            return;
        }

        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        DatabaseHelper oldHelper = mOpenHelper;

        // We save the existing db's before creating the destination db helper so we know what logic
        // to run in grid migration based on if that grid already existed before migration or not.
        List<String> existingDBs = LauncherFiles.GRID_DB_FILES.stream()
                .filter(dbName -> mContext.getDatabasePath(dbName).exists())
                .toList();

        mOpenHelper = (mContext instanceof SandboxContext) ? oldHelper
                : createDatabaseHelper(true, new DeviceGridState(idp).getDbFile());
        try {
            // This is the current grid we have, given by the mContext
            DeviceGridState srcDeviceState = new DeviceGridState(mContext);
            // This is the state we want to migrate to that is given by the idp
            DeviceGridState destDeviceState = new DeviceGridState(idp);

            boolean isDestNewDb = !existingDBs.contains(destDeviceState.getDbFile());

            GridSizeMigrationLogic gridSizeMigrationLogic = new GridSizeMigrationLogic();
            gridSizeMigrationLogic.migrateGrid(mContext, srcDeviceState, destDeviceState,
                    mOpenHelper, oldHelper.getWritableDatabase(), isDestNewDb);
        } catch (Exception e) {
            resetLauncherDb(restoreEventLogger);
            throw new Exception("Failed to migrate grid", e);
        } finally {
            if (mOpenHelper != oldHelper) {
                oldHelper.close();
            }
        }
    }

    /**
     * Migrates the DB if needed. If the migration failed, it clears the DB.
     */
    public void tryMigrateDB(@Nullable LauncherRestoreEventLogger restoreEventLogger) {
        if (!migrateGridIfNeeded()) {
            if (restoreEventLogger != null) {
                if (LauncherPrefs.get(mContext).get(NO_DB_FILES_RESTORED)) {
                    restoreEventLogger.logLauncherItemsRestoreFailed(DATA_TYPE_DB_FILE, 1,
                            RestoreError.DATABASE_FILE_NOT_RESTORED);
                    LauncherPrefs.get(mContext).put(NO_DB_FILES_RESTORED, false);
                    FileLog.d(TAG, "There is no data to migrate: resetting launcher database");
                } else {
                    restoreEventLogger.logLauncherItemsRestored(DATA_TYPE_DB_FILE, 1);
                    sendMetricsForFailedMigration(restoreEventLogger, getDb());
                }
            }
            FileLog.d(TAG, "Migration failed: resetting launcher database");
            createEmptyDB();
            LauncherPrefs.get(mContext).putSync(
                    getEmptyDbCreatedKey(mOpenHelper.getDatabaseName()).to(true));

            // Write the grid state to avoid another migration
            new DeviceGridState(LauncherAppState.getIDP(mContext)).writeToPrefs(mContext);
        } else if (restoreEventLogger != null) {
            restoreEventLogger.logLauncherItemsRestored(DATA_TYPE_DB_FILE, 1);
        }
    }

    /**
     * Migrates the DB if needed, and returns false if the migration failed
     * and DB needs to be cleared.
     * @return true if migration was success or ignored, false if migration failed
     * and the DB should be reset.
     */
    private boolean migrateGridIfNeeded() {
        createDbIfNotExists();
        if (LauncherPrefs.get(mContext).get(getEmptyDbCreatedKey())) {
            // If we have already create a new DB, ignore migration
            FileLog.d(TAG, "migrateGridIfNeeded: new DB already created, skipping migration");
            return false;
        }
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        if (!GridSizeMigrationDBController.needsToMigrate(mContext, idp)) {
            FileLog.d(TAG, "migrateGridIfNeeded: no grid migration needed");
            return true;
        }
        String targetDbName = new DeviceGridState(idp).getDbFile();
        if (TextUtils.equals(targetDbName, mOpenHelper.getDatabaseName())) {
            FileLog.e(TAG, "migrateGridIfNeeded: target db is same as current: " + targetDbName);
            return false;
        }
        DatabaseHelper oldHelper = mOpenHelper;

        // We save the existing db's before creating the destination db helper so we know what logic
        // to run in grid migration based on if that grid already existed before migration or not.
        List<String> existingDBs = LauncherFiles.GRID_DB_FILES.stream()
                .filter(dbName -> mContext.getDatabasePath(dbName).exists())
                .toList();

        mOpenHelper = (mContext instanceof SandboxContext) ? oldHelper
                : createDatabaseHelper(true /* forMigration */, targetDbName);
        try {
            // This is the current grid we have, given by the mContext
            DeviceGridState srcDeviceState = new DeviceGridState(mContext);
            // This is the state we want to migrate to that is given by the idp
            DeviceGridState destDeviceState = new DeviceGridState(idp);

            boolean isDestNewDb = !existingDBs.contains(destDeviceState.getDbFile());

            return GridSizeMigrationDBController.migrateGridIfNeeded(mContext, srcDeviceState,
                    destDeviceState, mOpenHelper, oldHelper.getWritableDatabase(), isDestNewDb);
        } catch (Exception e) {
            FileLog.e(TAG, "Failed to migrate grid", e);
            return false;
        } finally {
            if (mOpenHelper != oldHelper) {
                oldHelper.close();
            }
        }
    }

    /**
     * In case of migration failure, report metrics for the count of each itemType in the DB.
     * @param restoreEventLogger logger used to report Launcher restore metrics
     */
    private void sendMetricsForFailedMigration(LauncherRestoreEventLogger restoreEventLogger,
            SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT itemType, COUNT(*) AS count FROM favorites GROUP BY itemType",
                null
        )) {
            if (cursor.moveToFirst()) {
                do {
                    restoreEventLogger.logFavoritesItemsRestoreFailed(
                            cursor.getInt(cursor.getColumnIndexOrThrow(ITEM_TYPE)),
                            cursor.getInt(cursor.getColumnIndexOrThrow("count")),
                            RestoreError.GRID_MIGRATION_FAILURE
                    );
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            FileLog.e(TAG, "sendMetricsForFailedDb: Error reading from database", e);
        }
    }

    /**
     * Returns the underlying model database
     */
    public SQLiteDatabase getDb() {
        createDbIfNotExists();
        return mOpenHelper.getWritableDatabase();
    }

    private void onAddOrDeleteOp(SQLiteDatabase db) {
        mOpenHelper.onAddOrDeleteOp(db);
    }

    /**
     * Deletes any empty folder from the DB.
     * @return Ids of deleted folders.
     */
    @WorkerThread
    public IntArray deleteEmptyFolders() {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db)) {
            // Select folders whose id do not match any container value.
            String selection = LauncherSettings.Favorites.ITEM_TYPE + " = "
                    + LauncherSettings.Favorites.ITEM_TYPE_FOLDER + " AND "
                    + LauncherSettings.Favorites._ID +  " NOT IN (SELECT "
                    + LauncherSettings.Favorites.CONTAINER + " FROM "
                    + Favorites.TABLE_NAME + ")";

            IntArray folderIds = LauncherDbUtils.queryIntArray(false, db, Favorites.TABLE_NAME,
                    Favorites._ID, selection, null, null);
            if (!folderIds.isEmpty()) {
                db.delete(Favorites.TABLE_NAME, Utilities.createDbSelectionQuery(
                        LauncherSettings.Favorites._ID, folderIds), null);
            }
            t.commit();
            return folderIds;
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            return new IntArray();
        }
    }

    /**
     * Deletes any app pair that doesn't contain 2 member apps from the DB.
     * @return Ids of deleted app pairs.
     */
    @WorkerThread
    public IntArray deleteBadAppPairs() {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db)) {
            // Select all entries with ITEM_TYPE = ITEM_TYPE_APP_PAIR whose id does not appear
            // exactly twice in the CONTAINER column.
            String selection =
                    ITEM_TYPE + " = " + ITEM_TYPE_APP_PAIR
                            + " AND " + _ID +  " NOT IN"
                            + " (SELECT " + CONTAINER + " FROM " + TABLE_NAME
                            + " GROUP BY " + CONTAINER + " HAVING COUNT(*) = 2)";

            IntArray appPairIds = LauncherDbUtils.queryIntArray(false, db, TABLE_NAME,
                    _ID, selection, null, null);
            if (!appPairIds.isEmpty()) {
                db.delete(TABLE_NAME, Utilities.createDbSelectionQuery(
                        _ID, appPairIds), null);
            }
            t.commit();
            return appPairIds;
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            return new IntArray();
        }
    }

    /**
     * Deletes any app with a container id that doesn't exist.
     * @return Ids of deleted apps.
     */
    @WorkerThread
    public IntArray deleteUnparentedApps() {
        createDbIfNotExists();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db)) {
            // Select all entries whose container id does not appear in the database.
            String selection =
                    CONTAINER + " >= 0"
                            + " AND " + CONTAINER + " NOT IN"
                            + " (SELECT " + _ID + " FROM " + TABLE_NAME + ")";

            IntArray appIds = LauncherDbUtils.queryIntArray(false, db, TABLE_NAME,
                    _ID, selection, null, null);
            if (!appIds.isEmpty()) {
                db.delete(TABLE_NAME, Utilities.createDbSelectionQuery(
                        _ID, appIds), null);
            }
            t.commit();
            return appIds;
        } catch (SQLException ex) {
            Log.e(TAG, ex.getMessage(), ex);
            return new IntArray();
        }
    }

    private static void addModifiedTime(ContentValues values) {
        values.put(LauncherSettings.Favorites.MODIFIED, System.currentTimeMillis());
    }

    private void clearFlagEmptyDbCreated() {
        LauncherPrefs.get(mContext).removeSync(getEmptyDbCreatedKey());
    }

    /**
     * Loads the default workspace based on the following priority scheme:
     *   1) From the app restrictions
     *   2) From a package provided by play store
     *   3) From a partner configuration APK, already in the system image
     *   4) The default configuration for the particular device
     */
    @WorkerThread
    public synchronized void loadDefaultFavoritesIfNecessary() {
        createDbIfNotExists();

        if (LauncherPrefs.get(mContext).get(getEmptyDbCreatedKey())) {
            Log.d(TAG, "loading default workspace");

            LauncherWidgetHolder widgetHolder = mOpenHelper.newLauncherWidgetHolder();
            try {
                AutoInstallsLayout loader = createWorkspaceLoaderFromAppRestriction(widgetHolder);
                if (loader == null) {
                    loader = AutoInstallsLayout.get(mContext, widgetHolder, mOpenHelper);
                }
                if (loader == null) {
                    final Partner partner = Partner.get(mContext.getPackageManager());
                    if (partner != null) {
                        int workspaceResId = partner.getXmlResId(RES_PARTNER_DEFAULT_LAYOUT);
                        if (workspaceResId != 0) {
                            loader = new DefaultLayoutParser(mContext, widgetHolder,
                                    mOpenHelper, partner.getResources(), workspaceResId);
                        }
                    }
                }

                final boolean usingExternallyProvidedLayout = loader != null;
                if (loader == null) {
                    loader = getDefaultLayoutParser(widgetHolder);
                }

                // There might be some partially restored DB items, due to buggy restore logic in
                // previous versions of launcher.
                mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
                // Populate favorites table with initial favorites
                if ((mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(), loader) <= 0)
                        && usingExternallyProvidedLayout) {
                    // Unable to load external layout. Cleanup and load the internal layout.
                    mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
                    mOpenHelper.loadFavorites(mOpenHelper.getWritableDatabase(),
                            getDefaultLayoutParser(widgetHolder));
                }
                clearFlagEmptyDbCreated();
            } finally {
                widgetHolder.destroy();
            }
        }
    }

    /**
     * Creates workspace loader from an XML resource listed in the app restrictions.
     *
     * @return the loader if the restrictions are set and the resource exists; null otherwise.
     */
    private AutoInstallsLayout createWorkspaceLoaderFromAppRestriction(
            LauncherWidgetHolder widgetHolder) {
        ContentResolver cr = mContext.getContentResolver();
        String systemLayoutProvider = Settings.Secure.getString(cr, LAYOUT_PROVIDER_KEY);
        if (TextUtils.isEmpty(systemLayoutProvider)) {
            return null;
        }

        // Try the blob store first
        if (systemLayoutProvider.startsWith(BLOB_KEY_PREFIX)) {
            BlobStoreManager blobManager = mContext.getSystemService(BlobStoreManager.class);
            String blobHandlerDigest = systemLayoutProvider.substring(BLOB_KEY_PREFIX.length());
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                    blobManager.openBlob(BlobHandle.createWithSha256(
                            Base64.decode(blobHandlerDigest, NO_WRAP | NO_PADDING),
                            LAYOUT_DIGEST_LABEL, 0, LAYOUT_DIGEST_TAG)))) {
                return getAutoInstallsLayoutFromIS(in, widgetHolder, new SourceResources() { });
            } catch (Exception e) {
                Log.e(TAG, "Error getting layout from blob handle" , e);
                return null;
            }
        }

        // Try contentProvider based provider
        PackageManager pm = mContext.getPackageManager();
        ProviderInfo pi = pm.resolveContentProvider(systemLayoutProvider, 0);
        if (pi == null) {
            Log.e(TAG, "No provider found for authority " + systemLayoutProvider);
            return null;
        }
        Uri uri = getLayoutUri(systemLayoutProvider, mContext);
        try (InputStream in = cr.openInputStream(uri)) {
            Log.d(TAG, "Loading layout from " + systemLayoutProvider);

            Resources res = pm.getResourcesForApplication(pi.applicationInfo);
            return getAutoInstallsLayoutFromIS(in, widgetHolder, SourceResources.wrap(res));
        } catch (Exception e) {
            Log.e(TAG, "Error getting layout stream from: " + systemLayoutProvider , e);
            return null;
        }
    }

    private AutoInstallsLayout getAutoInstallsLayoutFromIS(InputStream in,
            LauncherWidgetHolder widgetHolder, SourceResources res) throws Exception {
        // Read the full xml so that we fail early in case of any IO error.
        String layout = new String(IOUtils.toByteArray(in));
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(layout));

        return new AutoInstallsLayout(mContext, widgetHolder, mOpenHelper, res,
                () -> parser, AutoInstallsLayout.TAG_WORKSPACE);
    }

    public static Uri getLayoutUri(String authority, Context ctx) {
        InvariantDeviceProfile grid = LauncherAppState.getIDP(ctx);
        return new Uri.Builder().scheme("content").authority(authority).path("launcher_layout")
                .appendQueryParameter("version", "1")
                .appendQueryParameter("gridWidth", Integer.toString(grid.numColumns))
                .appendQueryParameter("gridHeight", Integer.toString(grid.numRows))
                .appendQueryParameter("hotseatSize", Integer.toString(grid.numDatabaseHotseatIcons))
                .build();
    }

    private DefaultLayoutParser getDefaultLayoutParser(LauncherWidgetHolder widgetHolder) {
        InvariantDeviceProfile idp = LauncherAppState.getIDP(mContext);
        int defaultLayout = idp.demoModeLayoutId != 0
                && mContext.getSystemService(UserManager.class).isDemoUser()
                ? idp.demoModeLayoutId : idp.defaultLayoutId;

        return new DefaultLayoutParser(mContext, widgetHolder,
                mOpenHelper, mContext.getResources(), defaultLayout);
    }

    private ConstantItem<Boolean> getEmptyDbCreatedKey() {
        return getEmptyDbCreatedKey(mOpenHelper.getDatabaseName());
    }

    /**
     * Re-composite given key in respect to database. If the current db is
     * {@link LauncherFiles#LAUNCHER_DB}, return the key as-is. Otherwise append the db name to
     * given key. e.g. consider key="EMPTY_DATABASE_CREATED", dbName="minimal.db", the returning
     * string will be "EMPTY_DATABASE_CREATED@minimal.db".
     */
    private ConstantItem<Boolean> getEmptyDbCreatedKey(String dbName) {
        if (mContext instanceof SandboxContext) {
            return LauncherPrefs.nonRestorableItem(EMPTY_DATABASE_CREATED,
                    false /* default value */, EncryptionType.ENCRYPTED);
        }
        String key = TextUtils.equals(dbName, LauncherFiles.LAUNCHER_DB)
                ? EMPTY_DATABASE_CREATED : EMPTY_DATABASE_CREATED + "@" + dbName;
        return LauncherPrefs.backedUpItem(key, false /* default value */, EncryptionType.ENCRYPTED);
    }

    /**
     * Returns the serial number for the provided user
     */
    public long getSerialNumberForUser(UserHandle user) {
        return UserCache.INSTANCE.get(mContext).getSerialNumberForUser(user);
    }
}

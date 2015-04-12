package com.oxycode.nekosms.app;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.view.*;
import android.widget.AdapterView;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import com.oxycode.nekosms.R;
import com.oxycode.nekosms.data.*;
import com.oxycode.nekosms.provider.NekoSmsContract;

import java.util.Map;

public class FilterListActivity extends ListActivity implements LoaderManager.LoaderCallbacks<Cursor> {
    private static class FilterListItemTag {
        public SmsFilterData mFilterData;
        public TextView mPatternTextView;
        public TextView mFieldTextView;
        public TextView mModeTextView;
        public TextView mCaseSensitiveTextView;
    }

    private static class FilterAdapter extends ResourceCursorAdapter {
        private int[] mColumns;
        private Map<SmsFilterField, String> mSmsFilterFieldMap;
        private Map<SmsFilterMode, String> mSmsFilterModeMap;

        public FilterAdapter(Context context) {
            super(context, R.layout.listitem_filter_list, null, 0);
            mSmsFilterFieldMap = FilterEnumMaps.getFieldMap(context);
            mSmsFilterModeMap = FilterEnumMaps.getModeMap(context);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View view = super.newView(context, cursor, parent);
            FilterListItemTag tag = new FilterListItemTag();
            tag.mPatternTextView = (TextView)view.findViewById(R.id.listitem_filter_list_pattern_textview);
            tag.mFieldTextView = (TextView)view.findViewById(R.id.listitem_filter_list_field_textview);
            tag.mModeTextView = (TextView)view.findViewById(R.id.listitem_filter_list_mode_textview);
            tag.mCaseSensitiveTextView = (TextView)view.findViewById(R.id.listitem_filter_list_case_sensitive_textview);
            view.setTag(tag);
            return view;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            if (mColumns == null) {
                mColumns = SmsFilterLoader.getColumns(cursor);
            }

            FilterListItemTag tag = (FilterListItemTag)view.getTag();
            SmsFilterData filterData = SmsFilterLoader.getFilterData(cursor, mColumns, tag.mFilterData);
            tag.mFilterData = filterData;

            SmsFilterField field = filterData.getField();
            SmsFilterMode mode = filterData.getMode();
            String pattern = filterData.getPattern();
            boolean caseSensitive = filterData.isCaseSensitive();

            tag.mPatternTextView.setText("Pattern: " + pattern);
            tag.mFieldTextView.setText("Field: " + mSmsFilterFieldMap.get(field));
            tag.mModeTextView.setText("Mode: " + mSmsFilterModeMap.get(mode));
            tag.mCaseSensitiveTextView.setText("Case sensitive: " + caseSensitive);
        }
    }

    private static final int INIT_STATUS_CHECK_TIMEOUT = 3000;
    private static final int INIT_RESULT_TIMEOUT = 1;
    private static final int INIT_RESULT_ERROR = 2;
    private static final String REPORT_BUG_URL = "https://bitbucket.org/crossbowffs/nekosms/issues/new";

    private FilterAdapter mAdapter;
    private Handler mHandler;
    private Runnable mShowInitFailedDialogAction;
    private ProgressDialog mStatusCheckDialog;
    private AlertDialog mInitFailedDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_list);

        LoaderManager loaderManager = getLoaderManager();
        loaderManager.initLoader(0, null, this);

        FilterAdapter adapter = new FilterAdapter(this);
        setListAdapter(adapter);
        mAdapter = adapter;

        registerForContextMenu(getListView());

        mHandler = new Handler();
        mShowInitFailedDialogAction = new Runnable() {
            @Override
            public void run() {
                hideStatusCheckDialog();
                showInitFailedDialog(INIT_RESULT_TIMEOUT);
            }
        };

        beginStatusCheck();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_filter_list, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_item_create_filter:
            startFilterEditorActivity(-1);
            return true;
        case R.id.menu_item_view_blocked_messages:
            startBlockedSmsListActivity();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.context_filter_list, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo)item.getMenuInfo();
        long rowId = info.id;
        switch (item.getItemId()) {
        case R.id.contextmenu_filter_list_edit:
            startFilterEditorActivity(rowId);
            return true;
        case R.id.contextmenu_filter_list_delete:
            deleteFilter(rowId);
            Toast.makeText(this, R.string.filter_deleted, Toast.LENGTH_SHORT).show();
            return true;
        default:
            return super.onContextItemSelected(item);
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String[] from = {
            NekoSmsContract.Filters._ID,
            NekoSmsContract.Filters.PATTERN,
            NekoSmsContract.Filters.MODE,
            NekoSmsContract.Filters.FIELD,
            NekoSmsContract.Filters.CASE_SENSITIVE
        };
        Uri uri = NekoSmsContract.Filters.CONTENT_URI;
        return new CursorLoader(this, uri, from, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.changeCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.changeCursor(null);
    }

    private void finishTryTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAfterTransition();
        } else {
            finish();
        }
    }

    private void startBlockedSmsListActivity() {
        Intent intent = new Intent(this, BlockedSmsListActivity.class);
        startActivity(intent);
    }

    private void startFilterEditorActivity(long filterId) {
        Intent intent = new Intent(this, FilterEditorActivity.class);
        if (filterId >= 0) {
            Uri filtersUri = NekoSmsContract.Filters.CONTENT_URI;
            Uri filterUri = ContentUris.withAppendedId(filtersUri, filterId);
            intent.setData(filterUri);
        }
        startActivity(intent);
    }

    private void deleteFilter(long filterId) {
        ContentResolver contentResolver = getContentResolver();
        Uri filtersUri = NekoSmsContract.Filters.CONTENT_URI;
        Uri filterUri = ContentUris.withAppendedId(filtersUri, filterId);
        int deletedRows = contentResolver.delete(filterUri, null, null);
        // TODO: Check return value
    }

    private void beginStatusCheck() {
        ResultReceiver resultReceiver = new ResultReceiver(mHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                cancelDelayedShowInitFailedDialog();
                hideStatusCheckDialog();

                boolean initSuccess = resultCode == Intents.RESULT_INIT_SUCCESSFUL;
                if (initSuccess) {
                    hideInitFailedDialog();
                } else {
                    showInitFailedDialog(INIT_RESULT_ERROR);
                }
            }
        };

        Intent intent = new Intent(Intents.ACTION_GET_MODULE_STATUS);
        intent.putExtra(Intents.EXTRA_RESULT_RECEIVER, resultReceiver);
        sendBroadcast(intent);
        showStatusCheckDialog();
        delayedShowInitFailedDialog(INIT_STATUS_CHECK_TIMEOUT);
    }

    private void showStatusCheckDialog() {
        if (mStatusCheckDialog == null) {
            mStatusCheckDialog = new ProgressDialog(this);
            mStatusCheckDialog.setIndeterminate(true);
            mStatusCheckDialog.setMessage(getString(R.string.checking_module_status));
            mStatusCheckDialog.setCancelable(false);
        }

        mStatusCheckDialog.show();
    }

    private void hideStatusCheckDialog() {
        if (mStatusCheckDialog != null) {
            mStatusCheckDialog.dismiss();
            mStatusCheckDialog = null;
        }
    }

    private void delayedShowInitFailedDialog(int timeout) {
        mHandler.postDelayed(mShowInitFailedDialogAction, timeout);
    }

    private void cancelDelayedShowInitFailedDialog() {
        mHandler.removeCallbacks(mShowInitFailedDialogAction);
    }

    private void showInitFailedDialog(int reason) {
        AlertDialog.Builder builder = new AlertDialog.Builder(FilterListActivity.this)
            .setTitle(R.string.module_init_failed)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setCancelable(false)
            .setPositiveButton(R.string.exit, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finishTryTransition();
                }
            })
            .setNeutralButton(R.string.report_bug, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finishTryTransition();
                    Uri url = Uri.parse(REPORT_BUG_URL);
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, url);
                    startActivity(browserIntent);
                }
            })
            .setNegativeButton(R.string.ignore, null);

        if (reason == INIT_RESULT_TIMEOUT) {
            builder.setMessage(R.string.module_init_failed_timeout);
        } else if (reason == INIT_RESULT_ERROR) {
            builder.setMessage(R.string.module_init_failed_error);
        }

        mInitFailedDialog = builder.create();
        mInitFailedDialog.show();
    }

    private void hideInitFailedDialog() {
        if (mInitFailedDialog != null) {
            mInitFailedDialog.dismiss();
            mInitFailedDialog = null;
        }
    }
}
